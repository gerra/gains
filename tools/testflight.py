#!/usr/bin/env python3
"""Signing, archiving and uploading the iOS app, for .github/workflows/testflight.yml.

One command per step of that workflow, in the order it runs them:

  paths            Where the archive, export, keychain and API key live on this runner.
  check-secrets    Fail early, with a clear message, when a secret is missing.
  settings         Read TEAM_ID, BUNDLE_ID and MARKETING_VERSION out of Config.xcconfig.
  install-signing  Put the distribution certificate in a throwaway keychain and the
                   App Store profile where Xcode looks for it.
  install-api-key  Write the App Store Connect API key the upload authenticates with.
  archive          xcodebuild archive, signed manually with that certificate and profile.
  upload           xcodebuild -exportArchive, sending the build to App Store Connect.
  cleanup          Remove every piece of signing material from the runner.

The secrets arrive through the environment, never on the command line, and the one command
that must pass a secret to `security` is not echoed. One-time setup and the list of secrets:
docs/testflight.md
"""

import argparse
import base64
import os
import pathlib
import plistlib
import shutil
import uuid

import gha

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "iosApp/Configuration/Config.xcconfig"

SECRETS = [
    "APP_STORE_CONNECT_API_KEY_ID",
    "APP_STORE_CONNECT_API_ISSUER_ID",
    "APP_STORE_CONNECT_API_KEY_P8_BASE64",
    "IOS_DISTRIBUTION_CERT_P12_BASE64",
    "IOS_DISTRIBUTION_CERT_PASSWORD",
    "IOS_APP_STORE_PROFILE_BASE64",
]

# Where Xcode looks for provisioning profiles; both, because the location moved.
PROFILE_DIRS = [
    pathlib.Path.home() / "Library/MobileDevice/Provisioning Profiles",
    pathlib.Path.home() / "Library/Developer/Xcode/UserData/Provisioning Profiles",
]


def archive_path():
    return gha.temp("Gains.xcarchive")


def export_path():
    return gha.temp("export")


def keychain_path():
    return gha.temp("testflight.keychain-db")


def api_key_path():
    return gha.temp("AuthKey.p8")


def setting(name):
    """A setting from Config.xcconfig, e.g. TEAM_ID."""
    for line in CONFIG.read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == name:
            return value.strip()
    gha.fail(f"{name} is not set in {CONFIG.relative_to(ROOT)}")


def decode_secret(name, destination):
    """Write a base64 secret out as a file, readable only by this user."""
    destination.write_bytes(base64.b64decode(gha.secret(name)))
    destination.chmod(0o600)
    return destination


# --- the steps --------------------------------------------------------------


def paths(args):
    """The runner context is not available in job-level env, so the paths are set here."""
    gha.export(
        ARCHIVE_PATH=archive_path(),
        EXPORT_PATH=export_path(),
        KEYCHAIN_PATH=keychain_path(),
        API_KEY_PATH=api_key_path(),
    )


def check_secrets(args):
    missing = [name for name in SECRETS if not os.environ.get(name)]
    for name in missing:
        print(f"::error::Repository secret {name} is not set (see docs/testflight.md)")
    if missing:
        raise SystemExit(1)
    print(f"All {len(SECRETS)} signing secrets are set.")


def settings(args):
    gha.export(
        TEAM_ID=setting("TEAM_ID"),
        BUNDLE_ID=setting("BUNDLE_ID"),
        MARKETING_VERSION=setting("MARKETING_VERSION"),
    )
    gha.run("xcodebuild", "-version")
    gha.run("java", "-version")


def install_signing(args):
    keychain = keychain_path()
    password = str(uuid.uuid4())
    certificate = decode_secret("IOS_DISTRIBUTION_CERT_P12_BASE64", gha.temp("distribution.p12"))
    profile = decode_secret("IOS_APP_STORE_PROFILE_BASE64", gha.temp("appstore.mobileprovision"))

    # A throwaway keychain holding only the distribution certificate.
    gha.run("security", "create-keychain", "-p", password, keychain, show=False)
    gha.run("security", "set-keychain-settings", "-lut", "21600", keychain)
    gha.run("security", "unlock-keychain", "-p", password, keychain, show=False)
    gha.run(
        "security", "import", certificate,
        "-P", gha.secret("IOS_DISTRIBUTION_CERT_PASSWORD"),
        "-A", "-t", "cert", "-f", "pkcs12", "-k", keychain,
        show=False,
    )
    gha.run(
        "security", "set-key-partition-list", "-S", "apple-tool:,apple:", "-s",
        "-k", password, keychain,
        show=False,
    )
    gha.run("security", "list-keychain", "-d", "user", "-s", keychain, "login.keychain-db")
    gha.run("security", "find-identity", "-v", "-p", "codesigning", keychain)

    # Install the App Store profile where Xcode looks for it and remember its name. The
    # profile is CMS-signed; `security cms -D` unwraps the plist inside it.
    details_path = gha.temp("profile.plist")
    with open(details_path, "wb") as handle:
        gha.run("security", "cms", "-D", "-i", profile, stdout=handle)
    details = plistlib.loads(details_path.read_bytes())
    for directory in PROFILE_DIRS:
        directory.mkdir(parents=True, exist_ok=True)
        shutil.copy(profile, directory / f"{details['UUID']}.mobileprovision")

    gha.export(PROFILE_NAME=details["Name"])
    print(f"Installed profile '{details['Name']}' ({details['UUID']})")
    certificate.unlink()


def install_api_key(args):
    decode_secret("APP_STORE_CONNECT_API_KEY_P8_BASE64", api_key_path())


def archive(args):
    build = os.environ["BUILD_NUMBER"]
    print(
        f"Archiving Gains {os.environ['MARKETING_VERSION']} ({build}) for "
        f"{os.environ['BUNDLE_ID']} with profile '{os.environ['PROFILE_NAME']}'"
    )
    gha.run(
        "xcodebuild", "archive",
        "-project", "iosApp/iosApp.xcodeproj",
        "-scheme", "iosApp",
        "-configuration", "Release",
        "-destination", "generic/platform=iOS",
        "-archivePath", archive_path(),
        "CODE_SIGN_STYLE=Manual",
        "CODE_SIGN_IDENTITY=Apple Distribution",
        f"DEVELOPMENT_TEAM={os.environ['TEAM_ID']}",
        f"PROVISIONING_PROFILE_SPECIFIER={os.environ['PROFILE_NAME']}",
        f"CURRENT_PROJECT_VERSION={build}",
        cwd=ROOT,
    )


def upload(args):
    build = os.environ["BUILD_NUMBER"]
    version = os.environ["MARKETING_VERSION"]

    # The same options as iosApp/ExportOptions.plist, but signed with the installed profile
    # and sent straight to App Store Connect instead of written to disk.
    options = gha.temp("ExportOptions.plist")
    options.write_bytes(
        plistlib.dumps(
            {
                "method": "app-store-connect",
                "destination": "upload",
                "teamID": os.environ["TEAM_ID"],
                "signingStyle": "manual",
                "signingCertificate": "Apple Distribution",
                "provisioningProfiles": {os.environ["BUNDLE_ID"]: os.environ["PROFILE_NAME"]},
                "uploadSymbols": True,
            }
        )
    )

    gha.run(
        "xcodebuild", "-exportArchive",
        "-archivePath", archive_path(),
        "-exportOptionsPlist", options,
        "-exportPath", export_path(),
        "-allowProvisioningUpdates",
        "-authenticationKeyPath", api_key_path(),
        "-authenticationKeyID", gha.secret("APP_STORE_CONNECT_API_KEY_ID"),
        "-authenticationKeyIssuerID", gha.secret("APP_STORE_CONNECT_API_ISSUER_ID"),
    )
    gha.summary(
        f"Uploaded **Gains {version} ({build})** to App Store Connect. "
        "It shows up under TestFlight once processing finishes.",
        echo=False,
    )


def cleanup(args):
    """Leave nothing behind, whether or not the upload worked."""
    gha.run("security", "delete-keychain", keychain_path(), check=False)
    api_key_path().unlink(missing_ok=True)
    for directory in PROFILE_DIRS:
        shutil.rmtree(directory, ignore_errors=True)


COMMANDS = {
    "paths": paths,
    "check-secrets": check_secrets,
    "settings": settings,
    "install-signing": install_signing,
    "install-api-key": install_api_key,
    "archive": archive,
    "upload": upload,
    "cleanup": cleanup,
}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=list(COMMANDS))
    args = parser.parse_args(argv)
    COMMANDS[args.command](args)


if __name__ == "__main__":
    main()

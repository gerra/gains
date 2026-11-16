#!/usr/bin/env python3
"""The weekly round for external testers, for .github/workflows/external-testing.yml.

The release rounds (tools/release.py) upload a new version to TestFlight up to eight times a
day. Internal testers get every one; an external group would need Beta App Review for each, so
it gets one build a week instead, through one command:

  distribute  Take the newest processed build on App Store Connect (or the one asked for),
              write its "What to Test" from the commits since the build the group got last,
              add it to the external group and submit it for Beta App Review. The group's
              testers get it once Apple approves it, usually within a day. A build that is
              already in the group is not added again, so running twice in a week is harmless.

The App Store Connect API is called with the standard library and `openssl`, which signs the
API key's ES256 token, using the same three secrets as the TestFlight upload. How it fits with
the release rounds: docs/testflight.md#external-testers-once-a-week
"""

import argparse
import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

import gha
import play
import release

API = "https://api.appstoreconnect.apple.com/v1"
SECRETS = [
    "APP_STORE_CONNECT_API_KEY_ID",
    "APP_STORE_CONNECT_API_ISSUER_ID",
    "APP_STORE_CONNECT_API_KEY_P8_BASE64",
]
DEFAULT_GROUP = "Friends & Family"
# TestFlight cuts "What to Test" off at 4,000 characters.
WHAT_TO_TEST_LIMIT = 4000


# --- the token ----------------------------------------------------------------


def unsigned_token(key_id, issuer, now):
    """`header.claims` of the API key's token, as bytes to sign. Apple allows 20 minutes."""
    header = {"alg": "ES256", "kid": key_id, "typ": "JWT"}
    claims = {"iss": issuer, "iat": now, "exp": now + 20 * 60, "aud": "appstoreconnect-v1"}
    return ".".join(
        play.base64url(json.dumps(part, separators=(",", ":")).encode())
        for part in (header, claims)
    ).encode()


def raw_signature(der, size=32):
    """openssl's DER ECDSA signature as the 64 bytes `r || s` a JWT carries.

    DER is SEQUENCE { INTEGER r, INTEGER s }; each integer may have a leading zero byte, or be
    shorter than 32 bytes, so both are stripped and padded to the curve's size.
    """
    if der[0] != 0x30:
        raise ValueError("not a DER sequence")
    position = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    parts = []
    for _ in range(2):
        if der[position] != 0x02:
            raise ValueError("not a DER integer")
        length = der[position + 1]
        value = der[position + 2 : position + 2 + length].lstrip(b"\0")
        parts.append(value.rjust(size, b"\0"))
        position += 2 + length
    return b"".join(parts)


def token(key_path, key_id, issuer, sign=play.sign_with_openssl, now=None):
    unsigned = unsigned_token(key_id, issuer, int(now if now is not None else time.time()))
    signature = play.base64url(raw_signature(sign(key_path, unsigned)))
    return unsigned.decode() + "." + signature


# --- the API ------------------------------------------------------------------


class ApiError(Exception):
    def __init__(self, method, url, status, detail):
        super().__init__(f"{method} {url} answered {status}: {detail}")
        self.status = status


def http(method, url, body=None, token=None):
    """One request to App Store Connect, answered as parsed JSON ({} for 204)."""
    headers = {"Authorization": f"Bearer {token}"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            text = response.read().decode()
    except urllib.error.HTTPError as error:
        raise ApiError(method, url, error.code, error.read().decode(errors="replace")) from None
    return json.loads(text) if text else {}


def query(path, **params):
    """`/apps` + filter_bundleId=x -> '/apps?filter[bundleId]=x'. `__` in a name is a dot."""
    pairs = []
    for name, value in params.items():
        name = name.replace("__", ".")
        if name.startswith(("filter_", "fields_")):
            prefix, _, rest = name.partition("_")
            name = f"{prefix}[{rest}]"
        pairs.append((name, value))
    return path + ("?" + urllib.parse.urlencode(pairs, safe="[],.") if pairs else "")


def version_of(build, included):
    """(marketing version, build number) of a build from a response that included its
    preReleaseVersion."""
    versions = {
        item["id"]: item["attributes"]["version"]
        for item in included
        if item["type"] == "preReleaseVersions"
    }
    related = build["relationships"]["preReleaseVersion"]["data"]
    return versions.get(related["id"]) if related else None, build["attributes"]["version"]


class AppStoreConnect:
    """The calls a round takes. `request` is `http` here and a fake in the tests."""

    def __init__(self, token, request=http):
        self.token = token
        self.request = request

    def call(self, method, path, body=None):
        return self.request(method, API + path, body=body, token=self.token)

    def app(self, bundle_id):
        found = self.call("GET", query("/apps", filter_bundleId=bundle_id))["data"]
        if not found:
            gha.fail(f"App Store Connect has no app with the bundle id {bundle_id}")
        return found[0]

    def external_group(self, app_id, name):
        groups = self.call("GET", query(f"/apps/{app_id}/betaGroups", limit=200))["data"]
        for group in groups:
            if group["attributes"]["name"] == name:
                if group["attributes"].get("isInternalGroup"):
                    gha.fail(f"'{name}' is an internal group; it gets every build already")
                return group
        names = ", ".join(f"'{g['attributes']['name']}'" for g in groups) or "none"
        gha.fail(f"There is no TestFlight group named '{name}' (groups: {names})")

    def newest_build(self, app_id, number=None):
        """The newest processed, unexpired build, or the one with that build number."""
        params = dict(
            filter_app=app_id, sort="-uploadedDate", limit=1, include="preReleaseVersion"
        )
        if number:
            params["filter_version"] = number
        else:
            params.update(filter_processingState="VALID", filter_expired="false")
        answer = self.call("GET", query("/builds", **params))
        if not answer["data"]:
            gha.fail(
                f"App Store Connect has no build {number}" if number
                else "App Store Connect has no processed build to send yet"
            )
        build = answer["data"][0]
        state = build["attributes"].get("processingState")
        if state != "VALID":
            gha.fail(f"Build {number} is {state} on App Store Connect, not ready to send")
        if build["attributes"].get("expired"):
            gha.fail(f"Build {number} has expired")
        return build, version_of(build, answer.get("included", []))

    def last_in_group(self, group_id):
        """The newest build the group already has, with its version, or (None, None)."""
        answer = self.call(
            "GET",
            query(
                "/builds", filter_betaGroups=group_id, sort="-uploadedDate", limit=1,
                include="preReleaseVersion",
            ),
        )
        if not answer["data"]:
            return None, None
        build = answer["data"][0]
        return build, version_of(build, answer.get("included", []))

    def set_what_to_test(self, build_id, locale, text):
        """Fill in What to Test, in the build's existing localization or the app's language."""
        existing = self.call("GET", f"/builds/{build_id}/betaBuildLocalizations")["data"]
        if existing:
            mine = next((l for l in existing if l["attributes"]["locale"] == locale), existing[0])
            self.call(
                "PATCH",
                f"/betaBuildLocalizations/{mine['id']}",
                {"data": {"type": "betaBuildLocalizations", "id": mine["id"],
                          "attributes": {"whatsNew": text}}},
            )
            return
        self.call(
            "POST",
            "/betaBuildLocalizations",
            {"data": {"type": "betaBuildLocalizations",
                      "attributes": {"locale": locale, "whatsNew": text},
                      "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}},
        )

    def add_to_group(self, group_id, build_id):
        self.call(
            "POST",
            f"/betaGroups/{group_id}/relationships/builds",
            {"data": [{"type": "builds", "id": build_id}]},
        )

    def submit_for_review(self, build_id):
        """Submit the build for Beta App Review, unless it is already; returns its state."""
        try:
            existing = self.call("GET", f"/builds/{build_id}/betaAppReviewSubmission")["data"]
        except ApiError as error:
            if error.status != 404:
                raise
            existing = None
        if existing:
            return existing["attributes"]["betaReviewState"]
        submitted = self.call(
            "POST",
            "/betaAppReviewSubmissions",
            {"data": {"type": "betaAppReviewSubmissions",
                      "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}},
        )
        return submitted["data"]["attributes"]["betaReviewState"]


# --- What to Test -------------------------------------------------------------


def what_to_test(version, previous, changes):
    """The note testers read in TestFlight: the version and the changes since their last one.

    `changes` is a list of commit subjects as `- subject` lines, newest first, or empty.
    Lines past TestFlight's limit are dropped whole, with a count of how many.
    """
    if previous and changes:
        head = f"Gains {version}. New since {previous}:"
    else:
        head = f"Gains {version}."
    lines = changes.splitlines() if changes else []
    text = "\n".join([head, *lines])
    while len(text) > WHAT_TO_TEST_LIMIT and lines:
        lines.pop()
        text = "\n".join([head, *lines, "…and more"])
    return text


def tag_of(version, build):
    return f"testflight/{version}/{build}"


def tag_exists(tag):
    found = gha.run(
        "git", "rev-parse", "-q", "--verify", f"refs/tags/{tag}", check=False, capture=True
    )
    return found.returncode == 0


def changes_between(old_tag, new_tag):
    """Commit subjects between two shipped builds, leaving out what never reaches the app."""
    excludes = [f":(exclude){path}" for path in release.IGNORED]
    return gha.stdout(
        "git", "log", "-n", "200", "--no-merges", "--format=- %s",
        f"{old_tag}..{new_tag}", "--", ".", *excludes,
    )


# --- distribute ---------------------------------------------------------------


def distribute(args, api=None):
    key_path = gha.temp("AuthKey-external.p8")
    if api is None:
        for name in SECRETS:
            gha.secret(name)
        key_path.write_bytes(base64.b64decode(os.environ["APP_STORE_CONNECT_API_KEY_P8_BASE64"]))
        key_path.chmod(0o600)
        try:
            api = AppStoreConnect(
                token(
                    key_path,
                    os.environ["APP_STORE_CONNECT_API_KEY_ID"],
                    os.environ["APP_STORE_CONNECT_API_ISSUER_ID"],
                )
            )
        finally:
            key_path.unlink(missing_ok=True)

    app = api.app(release.setting("BUNDLE_ID"))
    group = api.external_group(app["id"], args.group)
    build, (version, number) = api.newest_build(app["id"], args.build or None)
    label = f"Gains {version} ({number})"
    last, last_version = api.last_in_group(group["id"])

    if last and last["id"] == build["id"]:
        print(f"{label} is already in '{args.group}'")
    else:
        if args.what_to_test:
            text = args.what_to_test
        else:
            previous, changes = None, ""
            if last_version:
                previous = f"{last_version[0]} ({last_version[1]})"
                old, new = tag_of(*last_version), tag_of(version, number)
                if tag_exists(old) and tag_exists(new):
                    changes = changes_between(old, new)
            text = what_to_test(version, previous, changes)
        print(f"What to Test:\n{text}\n")
        api.set_what_to_test(build["id"], app["attributes"].get("primaryLocale", "en-US"), text)
        api.add_to_group(group["id"], build["id"])

    state = api.submit_for_review(build["id"])
    waiting = {
        "APPROVED": "Beta App Review has approved it, so testers have it now.",
        "WAITING_FOR_REVIEW": "It is waiting for Beta App Review; testers get it once approved, "
        "usually within a day.",
        "IN_REVIEW": "It is in Beta App Review; testers get it once approved.",
        "REJECTED": "Beta App Review rejected it: see App Store Connect > TestFlight.",
    }.get(state, f"Beta App Review state: {state}.")
    gha.summary(f"Sent **{label}** to the TestFlight group **{args.group}**. {waiting}")
    if state == "REJECTED":
        gha.fail(f"{label} was rejected by Beta App Review")


def main(argv=None):
    # Options fall back to environment variables, which is how the workflow passes its inputs
    # without pasting them into a command line.
    def env(name, fallback=""):
        return os.environ.get(name) or fallback

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    send = commands.add_parser("distribute", help="send a build to the external group")
    send.add_argument("--group", default=env("GROUP", DEFAULT_GROUP), help="TestFlight group")
    send.add_argument(
        "--build", default=env("BUILD"), help="build number; default is the newest processed"
    )
    send.add_argument(
        "--what-to-test", default=env("WHAT_TO_TEST"),
        help="the note testers read; default lists the changes since their last build",
    )
    send.set_defaults(handler=distribute)
    args = parser.parse_args(argv)
    args.handler(args)


if __name__ == "__main__":
    main()

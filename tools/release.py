#!/usr/bin/env python3
"""The release process, driven by the two scheduled workflows.

Three commands, one per step that used to be inline shell:

  cut     Branch release/<major>.<minor> off main and commit the new version on it.
          Used by .github/workflows/release-branch.yml, every even hour 08:00-22:00 UTC.
  pick    Choose the release branch to upload and the commit to upload it from.
  finish  Tag the commit that shipped and open the branch's pull request back to main.
          Both used by .github/workflows/release.yml, every odd hour 09:00-23:00 UTC.

How the pieces fit together: docs/testflight.md#releases-through-the-day

The version arithmetic and the branch bookkeeping are plain functions with no git or
Actions in them, so tools/test_release.py can check them directly.
"""

import argparse
import json
import os
import pathlib
import re
import sys

import gha

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "iosApp/Configuration/Config.xcconfig"

VERSION = re.compile(r"^\d+\.\d+$")
BRANCH = re.compile(r"^release/\d+\.\d+$")

BOT_NAME = "github-actions[bot]"
BOT_EMAIL = "41898282+github-actions[bot]@users.noreply.github.com"

# What does not reach the iOS build, and so does not by itself justify cutting a branch.
# The version line differs by design; docs, samples, the workflows, tests and the Android-
# and desktop-only sources never make it into the app. `tools` is here for the same reason
# `.github` is: this file lives in it, and a change to the release process is not a change
# to the app.
IGNORED = [
    "iosApp/Configuration/Config.xcconfig",
    "*.md",
    "docs",
    "samples",
    ".github",
    "tools",
    "composeApp/src/androidMain",
    "composeApp/src/desktopMain",
    "shared/src/androidMain",
    "shared/src/desktopMain",
    "*/src/commonTest/*",
    "*/src/desktopTest/*",
]


# --- the arithmetic, kept free of git and Actions ---------------------------


def parse(version):
    """'1.12' -> (1, 12), for comparing and sorting versions."""
    major, minor = version.split(".")
    return int(major), int(minor)


def sort_versions(versions):
    """The versions in release order, lowest first."""
    return sorted(versions, key=parse)


def next_version(current, versions):
    """One minor above the highest branch for main's major, or above main's own.

    `current` is MARKETING_VERSION on main, `versions` the release branches that exist.
    With main at 1.0 and no branches yet this gives 1.1, then 1.2, and so on; a branch
    for a newer major is left alone, since a new major is started by hand.
    """
    major, minor = parse(current)
    same_major = [m for M, m in map(parse, versions) if M == major]
    return f"{major}.{max(same_major + [minor]) + 1}"


def previous_version(versions, version):
    """The version cut just before this one, or None if it is the first."""
    previous = None
    for candidate in sort_versions(versions):
        if candidate == version:
            break
        previous = candidate
    return previous


# --- the bits that do talk to git -------------------------------------------


def setting(name):
    """A setting from Config.xcconfig, e.g. MARKETING_VERSION."""
    for line in CONFIG.read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == name:
            return value.strip()
    gha.fail(f"{name} is not set in {CONFIG.relative_to(ROOT)}")


def release_versions():
    """Every release/<major>.<minor> branch on origin, as versions, lowest first."""
    refs = gha.stdout(
        "git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin/release/"
    )
    versions = [
        ref.removeprefix("origin/release/")
        for ref in refs.splitlines()
        if VERSION.match(ref.removeprefix("origin/release/"))
    ]
    return sort_versions(versions)


def app_changed_since(version):
    """Has anything that reaches the iOS app changed on main since that branch was cut?"""
    excludes = [f":(exclude){path}" for path in IGNORED]
    unchanged = gha.run(
        "git", "diff", "--quiet", f"origin/release/{version}", "origin/main", "--", ".",
        *excludes,
        check=False,
    )
    return unchanged.returncode != 0


def branch_exists_on_origin(branch):
    found = gha.run(
        "git", "ls-remote", "--exit-code", "--heads", "origin", branch,
        check=False, capture=True,
    )
    return found.returncode == 0


def commit_as_bot():
    gha.run("git", "config", "user.name", BOT_NAME)
    gha.run("git", "config", "user.email", BOT_EMAIL)


# --- cut: branch off main ---------------------------------------------------


def cut(args):
    current = setting("MARKETING_VERSION")
    existing = release_versions()
    newest = existing[-1] if existing else None

    if args.version:
        version = args.version
        if not VERSION.match(version):
            gha.fail(f"Version must look like major.minor, got '{version}'")
    else:
        version = next_version(current, existing)

    if version == current:
        gha.fail(f"main is already at version {version}; pick a higher one")
    if branch_exists_on_origin(f"release/{version}"):
        gha.fail(f"Branch release/{version} already exists")

    if newest and not args.force and not app_changed_since(newest):
        gha.summary(
            f"main has not changed since release/{newest} was cut; "
            "nothing to release this round."
        )
        return

    print(f"Next release: {version} (main is at {current}, newest branch: {newest or 'none'})")

    branch = f"release/{version}"
    commit_as_bot()
    gha.run("git", "switch", "-c", branch)
    CONFIG.write_text(
        re.sub(
            r"^MARKETING_VERSION=.*$",
            f"MARKETING_VERSION={version}",
            CONFIG.read_text(),
            flags=re.M,
        )
    )
    gha.run("git", "add", str(CONFIG.relative_to(ROOT)))
    gha.run("git", "commit", "-m", f"Release {version}")
    gha.run("git", "push", "-u", "origin", branch)

    main = gha.stdout("git", "rev-parse", "--short", "origin/main")
    gha.summary(
        f"Cut **{branch}** from main at {main}. The Release workflow uploads it to "
        "TestFlight in an hour and then opens the pull request to main.",
        echo=False,
    )


# --- pick: which branch, and which commit on it -----------------------------


def pick(args):
    if args.branch:
        branch = args.branch.removeprefix("origin/")
        if not BRANCH.match(branch):
            gha.fail(f"Expected a branch named release/<major>.<minor>, got '{branch}'")
        known = gha.run(
            "git", "rev-parse", "--verify", "-q", f"origin/{branch}",
            check=False, capture=True,
        )
        if known.returncode != 0:
            gha.fail(f"There is no branch {branch} on origin")
    else:
        existing = release_versions()
        if not existing:
            gha.summary("There is no release branch yet; nothing to upload.")
            gha.output(upload="false")
            return
        branch = f"release/{existing[-1]}"

    version = branch.removeprefix("release/")
    sha = gha.stdout("git", "rev-parse", f"origin/{branch}")

    shipped = gha.stdout("git", "tag", "--points-at", sha, "--list", "testflight/*")
    if shipped and not args.force:
        gha.summary(
            f"{branch} at {sha[:7]} already went to TestFlight as "
            f"{' '.join(shipped.split())}; nothing to upload."
        )
        gha.output(upload="false")
        return

    print(f"Releasing {branch} at {sha[:7]} as Gains {version}")
    gha.output(upload="true", branch=branch, version=version, sha=sha)


# --- finish: tag the build and open the pull request ------------------------


def tag_shipped_commit(version, build, sha):
    tag = f"testflight/{version}/{build}"
    known = gha.run(
        "git", "rev-parse", "-q", "--verify", f"refs/tags/{tag}", check=False, capture=True
    )
    if known.returncode == 0:
        print(f"Tag {tag} already exists (re-run)")
        return
    commit_as_bot()
    gha.run("git", "tag", "-a", tag, sha, "-m", f"Gains {version} ({build}) uploaded to TestFlight")
    gha.run("git", "push", "origin", tag)


def pull_request_body(version, build, sha, branch, previous):
    """What the Release <version> pull request says, including the list of changes."""
    lines = [
        f"Gains **{version}** (build {build}) went to TestFlight from {sha[:7]}.",
        "",
        f"Merging brings the version bump, and anything else committed on `{branch}`, "
        "back to `main`.",
    ]
    if previous:
        changes = gha.stdout(
            "git", "log", "-n", "100", "--no-merges", "--format=- %s",
            f"origin/release/{previous}..{sha}",
            "--", ".", ":(exclude)iosApp/Configuration/Config.xcconfig",
        )
        lines += ["", f"## Changes since release/{previous}", "", changes]
    return "\n".join(lines) + "\n"


def open_pull_request(version, build, sha, branch):
    listed = gha.stdout(
        "gh", "pr", "list", "--head", branch, "--base", "main", "--state", "open",
        "--json", "number",
    )
    already_open = json.loads(listed or "[]")
    if already_open:
        number = already_open[0]["number"]
        gha.run(
            "gh", "pr", "comment", str(number),
            "--body", f"Build {build} of Gains {version} went to TestFlight from {sha[:7]}.",
        )
        gha.summary(
            f"Pull request #{number} for {branch} is already open; noted build {build} on it.",
            echo=False,
        )
        return

    previous = previous_version(release_versions(), version)
    body = gha.temp("pr-body.md")
    body.write_text(pull_request_body(version, build, sha, branch, previous))

    created = gha.run(
        "gh", "pr", "create", "--base", "main", "--head", branch,
        "--title", f"Release {version}", "--body-file", str(body),
        check=False, capture=True,
    )
    if created.returncode != 0:
        print(created.stdout, created.stderr, file=sys.stderr)
        gha.fail(
            "Could not open the pull request. Without a RELEASE_TOKEN secret, Settings > "
            "Actions > General > Workflow permissions must allow GitHub Actions to create "
            "pull requests (docs/testflight.md#releases-through-the-day)."
        )
    gha.summary(
        f"Opened {created.stdout.strip()} for Gains {version} (build {build}).", echo=False
    )


def finish(args):
    tag_shipped_commit(args.version, args.build, args.sha)
    open_pull_request(args.version, args.build, args.sha, args.branch)


# --- entry point ------------------------------------------------------------


def main(argv=None):
    # Each option falls back to an environment variable, which is how the workflows pass
    # their inputs: a `${{ inputs.x }}` interpolated into a command line would be pasted
    # into it verbatim, and an input is not trusted enough for that.
    def env(name, fallback=""):
        return os.environ.get(name, fallback)

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    cut_parser = commands.add_parser("cut", help="branch release/<version> off main")
    cut_parser.add_argument(
        "--version", default=env("VERSION"), help="major.minor; default is the next minor"
    )
    cut_parser.add_argument(
        "--force", action="store_true", default=env("FORCE") == "true",
        help="cut even if main has not changed",
    )
    cut_parser.set_defaults(handler=cut)

    pick_parser = commands.add_parser("pick", help="choose the branch and commit to upload")
    pick_parser.add_argument(
        "--branch", default=env("BRANCH"), help="release/<version>; default is the newest"
    )
    pick_parser.add_argument(
        "--force", action="store_true", default=env("FORCE") == "true",
        help="upload even if this commit already shipped",
    )
    pick_parser.set_defaults(handler=pick)

    finish_parser = commands.add_parser("finish", help="tag the build and open the pull request")
    for name in ("branch", "version", "sha", "build"):
        finish_parser.add_argument(
            f"--{name}", default=env(name.upper()), required=not env(name.upper())
        )
    finish_parser.set_defaults(handler=finish)

    args = parser.parse_args(argv)
    args.handler(args)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Say which of CI's Gradle jobs a pull request needs, from the files it changes.

Run by the `changes` job of ci.yml and codeql.yml:  python3 tools/changes.py

Two step outputs, each "true" or "false":

  build         Anything the Gradle builds, their tests or CodeQL could see changed. False only
                when every file is one Gradle never reads: docs, the site, the Python and
                deploy files in tools/ and deploy/, Markdown anywhere, and the other workflows.
  dependencies  A build file or the version catalog changed, so the dependency review has
                something to compare.

The jobs skip themselves with a job-level `if:` on these, never with a workflow's `paths:`:
GitHub reports a job skipped by its condition as passed, so a check made required on `main`
still reports on a docs-only pull request instead of waiting forever.

Only a pull request is ever narrowed. A push to main, the weekly CodeQL run and a manual run
get "true" for both, and so does anything this script can't be sure of: a diff git can't
produce, or an empty one. The Python in tools/ is tested by its own job on every pull request,
because site/'s links and the workflows are checked there.
"""

import os

import gha

# Directories Gradle never reads. tools/ holds Python and deploy/ the server's unit and nginx,
# both run by deploy_server.py; site/ is rsynced as is. Their tests are the Python ones.
NO_BUILD_DIRS = ("docs/", "site/", "tools/", "deploy/", ".github/")

# The workflows that run Gradle themselves: a change to one of them runs it.
BUILD_WORKFLOWS = (".github/workflows/ci.yml", ".github/workflows/codeql.yml")

# What the dependency review compares: the version catalog, the wrapper and the verification
# metadata under gradle/, and every build script and properties file.
DEPENDENCY_DIRS = ("gradle/",)
DEPENDENCY_SUFFIXES = (".gradle.kts", ".gradle", "gradle.properties")
DEPENDENCY_WORKFLOWS = (".github/workflows/ci.yml",)


def needs_build(path):
    """Whether a change to this path can change what Gradle builds, tests or scans."""
    if path in BUILD_WORKFLOWS:
        return True
    if path.endswith(".md") or path == "LICENSE":
        return False
    return not path.startswith(NO_BUILD_DIRS)


def changes_dependencies(path):
    """Whether a change to this path can change the resolved dependencies."""
    return (
        path.startswith(DEPENDENCY_DIRS)
        or path.endswith(DEPENDENCY_SUFFIXES)
        or path in DEPENDENCY_WORKFLOWS
    )


def classify(paths):
    """The two outputs for a list of changed paths. No paths at all means "run everything"."""
    if not paths:
        return {"build": True, "dependencies": True}
    return {
        "build": any(needs_build(p) for p in paths),
        "dependencies": any(changes_dependencies(p) for p in paths),
    }


def changed_paths():
    """The files a pull request changes, or None when that isn't a pull request or git fails.

    Actions checks a pull request out as its merge commit, whose first parent is the base
    branch's tip, so HEAD^1..HEAD is exactly what merging would change (the checkout fetches
    two commits for this). --no-renames lists a moved file under both names, so moving a
    source file into docs/ still counts as touching the source.
    """
    if os.environ.get("GITHUB_EVENT_NAME") != "pull_request":
        return None
    diff = gha.run("git", "diff", "--name-only", "--no-renames", "HEAD^1", "HEAD", capture=True, check=False)
    if diff.returncode != 0:
        print(diff.stderr)
        return None
    return [line for line in diff.stdout.splitlines() if line]


def main():
    paths = changed_paths()
    result = classify(paths or [])
    if paths is None:
        gha.summary("Not a pull request: every job runs.")
    else:
        gha.summary(f"{len(paths)} changed files.")
    for name, value in result.items():
        gha.summary(f"- `{name}`: {'runs' if value else 'skipped'}")
    gha.output(**{name: str(value).lower() for name, value in result.items()})


if __name__ == "__main__":
    main()

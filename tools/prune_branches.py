#!/usr/bin/env python3
"""Delete the branches on origin whose work is already on main.

Two commands:

  list    Show what would be deleted, and why the rest is kept. Changes nothing.
  prune   Delete them, after printing the same list.

Run from the repository root:  python3 tools/prune_branches.py list

A branch is only deleted when its tip is an ancestor of origin/main — every commit on it
is already on main, so nothing can be lost and the branch can be recreated from main's
history. That test is the whole safety story, and it is deliberately not "does the branch
have a merged pull request": a branch that was merged and then pushed to again still
carries the commits added after the merge, and GitHub still calls it merged. Asking git
about ancestry catches that case; asking the pull request does not.

The ancestry test needs the real history, so a shallow clone is deepened first — on a
truncated history the grafted commits have no parents and branches look unmerged.

release/* is never deleted even once merged: release.py reads those refs back to work out
the next version number (see release_versions there), so removing them would change what
the next release is called.
"""

import argparse
import pathlib
import re

import gha

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Branches that stay whatever their ancestry says. The default branch for the obvious
# reason; release/* because release.py counts versions from it.
PROTECTED = re.compile(r"^(main|master|HEAD|release/.*)$")

# Deleting refs one push at a time is a request each; all of them in one push is a single
# oversized request. Ten is small enough to retry cheaply.
BATCH = 10


# --- the choosing, kept free of git ------------------------------------------


def prunable(merged, base="main"):
    """Which of the branches merged into `base` are safe to delete, in name order.

    `merged` is every branch whose tip is already on `base`, base included. What comes
    back is that list minus the protected ones.
    """
    return sorted(branch for branch in merged if branch != base and not PROTECTED.match(branch))


def kept(branches, merged, base="main"):
    """The branches that survive, each with the reason, for the report.

    Unmerged work is the interesting reason: it is the branch someone would otherwise
    lose. Protected branches are listed too so the report accounts for every branch.
    """
    reasons = {}
    for branch in sorted(branches):
        if branch == base:
            continue
        if PROTECTED.match(branch):
            reasons[branch] = "protected"
        elif branch not in merged:
            reasons[branch] = "has commits not on " + base
    return reasons


# --- the bits that do talk to git --------------------------------------------


# The reads below pass show=False: one line of report per branch is the point of this
# script, and echoing the rev-parse behind each one buries it. The pushes still echo.


def remote_branches():
    """Every branch on origin, by short name, as of the last fetch."""
    refs = gha.stdout(
        "git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin/", show=False
    )
    return [
        ref.removeprefix("origin/") for ref in refs.splitlines() if ref != "origin/HEAD"
    ]


def merged_into(base):
    """The branches on origin whose tip is already an ancestor of origin/<base>."""
    refs = gha.stdout("git", "branch", "--remotes", "--merged", f"origin/{base}", show=False)
    return {
        line.strip().removeprefix("origin/")
        for line in refs.splitlines()
        if "->" not in line  # origin/HEAD -> origin/main
    }


def ensure_full_history():
    """Deepen a shallow clone, so ancestry answers are about the real history."""
    if gha.stdout("git", "rev-parse", "--is-shallow-repository", show=False) == "true":
        print("Shallow clone; fetching the full history so ancestry is accurate.")
        gha.run("git", "fetch", "--unshallow", "origin")


def tip(branch):
    return gha.stdout("git", "rev-parse", f"origin/{branch}", show=False)


def delete(branches):
    """Delete the branches from origin, a few refs per push."""
    for start in range(0, len(branches), BATCH):
        batch = branches[start : start + BATCH]
        gha.run("git", "push", "origin", *(f":refs/heads/{branch}" for branch in batch))


# --- the commands -------------------------------------------------------------


def report(base):
    """Fetch, work out what goes and what stays, and print both. Returns what goes."""
    gha.run("git", "fetch", "origin", "--prune")
    ensure_full_history()

    branches = remote_branches()
    merged = merged_into(base)
    going = prunable(merged, base)
    staying = kept(branches, merged, base)

    if staying:
        print(f"\nKeeping {len(staying)}:")
        for branch, reason in staying.items():
            print(f"  {branch}  ({reason})")

    if not going:
        gha.summary(f"\nNo branch is fully merged into {base}; nothing to delete.")
        return going

    print(f"\nMerged into {base}, so safe to delete ({len(going)}):")
    for branch in going:
        date = gha.stdout(
            "git", "log", "-1", "--format=%ad", "--date=short", f"origin/{branch}", show=False
        )
        print(f"  {tip(branch)[:7]}  {date}  {branch}")
    return going


def list_branches(args):
    going = report(args.base)
    if going:
        print(f"\nNothing deleted. `prune` would delete these {len(going)}.")


def prune(args):
    going = report(args.base)
    if not going:
        return

    # The tips first: with these, any branch here can be put back, and a deletion that
    # turns out to be unwanted is a push away from undone.
    manifest = gha.temp("deleted-branches.txt")
    manifest.write_text("".join(f"{tip(branch)} {branch}\n" for branch in going))
    print(f"\nTips recorded in {manifest}, to put any of them back with:")
    print(f"  while read sha branch; do git push origin $sha:refs/heads/$branch; done < {manifest}")

    if not args.yes:
        print(f"\nRe-run with --yes to delete these {len(going)}.")
        return

    print()
    delete(going)
    gha.summary(f"\nDeleted {len(going)} branches already merged into {args.base}.", echo=False)


# --- entry point --------------------------------------------------------------


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", default="main", help="the branch work is merged into")
    commands = parser.add_subparsers(dest="command", required=True)

    list_parser = commands.add_parser("list", help="show what would be deleted")
    list_parser.set_defaults(handler=list_branches)

    prune_parser = commands.add_parser("prune", help="delete the merged branches")
    prune_parser.add_argument(
        "--yes", action="store_true", help="actually delete; without it this only lists"
    )
    prune_parser.set_defaults(handler=prune)

    args = parser.parse_args(argv)
    args.handler(args)


if __name__ == "__main__":
    main()

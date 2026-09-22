#!/usr/bin/env python3
"""Tests for the branch choosing in tools/prune_branches.py.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

Deleting a branch is the one thing here that cannot be taken back by re-running, so what
is worth testing is the decision rather than the push: that the protected branches survive
being merged, and that a branch git does not call merged is never in the list. Nothing
here touches git or the network.
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from prune_branches import kept, prunable


class Prunable(unittest.TestCase):
    def test_merged_branches_go(self):
        self.assertEqual(prunable({"main", "claude/a", "claude/b"}), ["claude/a", "claude/b"])

    def test_the_base_itself_stays(self):
        self.assertEqual(prunable({"main"}), [])

    def test_release_branches_stay_even_when_merged(self):
        """release.py counts the next version from these refs, so they outlive the merge."""
        self.assertEqual(prunable({"main", "release/1.1", "claude/a"}), ["claude/a"])

    def test_master_and_head_stay(self):
        self.assertEqual(prunable({"main", "master", "HEAD", "claude/a"}), ["claude/a"])

    def test_only_what_is_merged_is_offered(self):
        """The input is the merged set; an unmerged branch never reaches this function."""
        self.assertNotIn("claude/unmerged", prunable({"main", "claude/a"}))

    def test_a_base_other_than_main_is_still_kept(self):
        self.assertEqual(prunable({"develop", "claude/a"}, base="develop"), ["claude/a"])

    def test_name_order(self):
        self.assertEqual(prunable({"claude/b", "claude/a", "main"}), ["claude/a", "claude/b"])


class Kept(unittest.TestCase):
    def test_unmerged_branches_are_reported_with_the_reason(self):
        reasons = kept({"main", "claude/open"}, {"main"})
        self.assertEqual(reasons["claude/open"], "has commits not on main")

    def test_a_merged_release_branch_is_reported_as_protected(self):
        reasons = kept({"main", "release/1.1"}, {"main", "release/1.1"})
        self.assertEqual(reasons["release/1.1"], "protected")

    def test_a_branch_that_goes_is_not_reported_as_kept(self):
        self.assertEqual(kept({"main", "claude/a"}, {"main", "claude/a"}), {})

    def test_the_base_is_not_listed(self):
        self.assertNotIn("main", kept({"main"}, {"main"}))

    def test_merged_and_pushed_to_again_counts_as_unmerged(self):
        """The case a merged-pull-request check gets wrong, and ancestry gets right.

        claude/reopened had its pull request merged, then took another commit. GitHub
        still calls it merged; git does not put it in the merged set, so it stays.
        """
        reasons = kept({"main", "claude/reopened"}, {"main"})
        self.assertEqual(reasons["claude/reopened"], "has commits not on main")
        self.assertNotIn("claude/reopened", prunable({"main"}))


if __name__ == "__main__":
    unittest.main()

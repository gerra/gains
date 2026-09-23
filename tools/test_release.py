#!/usr/bin/env python3
"""Tests for the version arithmetic in tools/release.py.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

These are the parts that used to be an awk one-liner inside the workflow, where the only
way to try a change was to let it cut a branch. Nothing here touches git or the network.
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from release import (
    TESTFLIGHT_LINK,
    newest_build,
    next_version,
    parse,
    previous_version,
    pull_request_body,
    release_notes,
    sort_versions,
)


class NextVersion(unittest.TestCase):
    def test_first_branch_comes_from_main(self):
        self.assertEqual(next_version("1.0", []), "1.1")

    def test_counts_up_from_the_newest_branch(self):
        self.assertEqual(next_version("1.0", ["1.1", "1.2"]), "1.3")

    def test_eight_cuts_in_a_day(self):
        """The cadence the schedule now runs at: 1.0 on main walks to 1.8 by 22:00."""
        branches, version = [], "1.0"
        for _ in range(8):
            version = next_version("1.0", branches)
            branches.append(version)
        self.assertEqual(branches, [f"1.{n}" for n in range(1, 9)])

    def test_unmerged_pull_requests_do_not_repeat_a_version(self):
        """main stays at 1.0 while the Release pull requests sit open; versions still climb."""
        self.assertEqual(next_version("1.0", ["1.1", "1.2", "1.3"]), "1.4")

    def test_main_ahead_of_every_branch_wins(self):
        self.assertEqual(next_version("1.5", ["1.1", "1.2"]), "1.6")

    def test_minors_compare_as_numbers_not_text(self):
        """The bug a string sort would bring: 1.9 must beat 1.10, not the other way round."""
        self.assertEqual(next_version("1.0", ["1.9", "1.10"]), "1.11")

    def test_a_newer_major_is_left_alone(self):
        """A 2.x branch cut by hand does not drag main's 1.x line along with it."""
        self.assertEqual(next_version("1.2", ["1.1", "1.2", "2.0"]), "1.3")


class PreviousVersion(unittest.TestCase):
    def test_the_one_before(self):
        self.assertEqual(previous_version(["1.1", "1.2", "1.3"], "1.3"), "1.2")

    def test_nothing_before_the_first(self):
        self.assertIsNone(previous_version(["1.1", "1.2"], "1.1"))

    def test_order_does_not_depend_on_the_input(self):
        self.assertEqual(previous_version(["1.10", "1.2", "1.9"], "1.10"), "1.9")


class Sorting(unittest.TestCase):
    def test_numeric_order(self):
        self.assertEqual(sort_versions(["1.10", "1.2", "2.0", "1.9"]), ["1.2", "1.9", "1.10", "2.0"])

    def test_parse(self):
        self.assertEqual(parse("12.34"), (12, 34))


class PullRequestBody(unittest.TestCase):
    def test_without_a_previous_branch_there_is_no_change_list(self):
        body = pull_request_body("1.1", "42", "abcdef1234", "release/1.1", None)
        self.assertIn("Gains **1.1** (build 42) went to TestFlight from abcdef1.", body)
        self.assertIn("back to `main`", body)
        self.assertNotIn("## Changes since", body)


class ReleaseNotes(unittest.TestCase):
    def test_first_release_has_no_change_list(self):
        notes = release_notes("1.1", "42", "abcdef1234", None)
        self.assertIn("Gains **1.1** (build 42) went to TestFlight from abcdef1.", notes)
        self.assertNotIn("## Changes since", notes)

    def test_leads_with_the_testflight_link(self):
        notes = release_notes("1.1", "42", "abcdef1234", None)
        self.assertTrue(notes.startswith("[![Install with TestFlight]"))
        self.assertIn(f"]({TESTFLIGHT_LINK})", notes.splitlines()[0])
        self.assertIn(f"open **{TESTFLIGHT_LINK}** on your iPhone", notes)

    def test_lists_the_changes_since_the_previous_version(self):
        notes = release_notes("1.2", "43", "abcdef1234", "1.1", "- Fix the timer")
        self.assertIn("## Changes since Gains 1.1\n\n- Fix the timer\n", notes)

    def test_a_rebuild_with_nothing_new_has_no_empty_heading(self):
        notes = release_notes("1.2", "44", "abcdef1234", "1.1", "")
        self.assertNotIn("## Changes since", notes)


class NewestBuild(unittest.TestCase):
    def test_highest_build_number_wins(self):
        self.assertEqual(newest_build(["testflight/1.4/3", "testflight/1.4/12"]), "12")

    def test_no_tags_no_build(self):
        self.assertIsNone(newest_build([]))


if __name__ == "__main__":
    unittest.main()

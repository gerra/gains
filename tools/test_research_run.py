#!/usr/bin/env python3
"""Tests for the arithmetic in tools/research_run.py: picks, the ledger, the backlog's shape.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

The research agent writes the backlog and the orchestrator reads it back to decide what to
build, so the shape check and the picking order are what's worth pinning; the rest is the
ledger arithmetic and the command line, which can't be tried without spending money. Nothing
here touches git, Claude or the network.
"""

import json
import pathlib
import re
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from research_run import (
    LEDGER_COLUMNS,
    append_ledger,
    claude_command,
    fill,
    format_totals,
    item_branch,
    ledger_row,
    next_item_id,
    parse_result,
    pick_items,
    pr_footer,
    read_ledger,
    report_branch,
    report_pr_body,
    run_cost,
    slug,
    totals,
    validate_backlog,
)

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESEARCH = ROOT / "research"


def item(**overrides):
    """A valid backlog item, with whatever the test changes."""
    base = {
        "id": "r001",
        "title": "Rest timer: starts itself when a set is ticked",
        "why": "Asked for in every thread about Strong and Hevy; saves a tap per set at the rack.",
        "area": "workout editor",
        "priority": 4,
        "complexity": 2,
        "status": "new",
        "found_in_run": "20261001-0200",
        "evidence": [{"url": "https://www.reddit.com/r/Fitness/comments/x/", "quote": "auto rest timer", "source": "r/Fitness"}],
    }
    base.update(overrides)
    return base


def result(**overrides):
    base = {"ok": True, "cost_usd": 3.5, "agent_seconds": 600, "turns": 42, "session_id": "s", "result": "", "subtype": "success", "denials": [], "model": "claude-opus-5-5"}
    base.update(overrides)
    return base


class Names(unittest.TestCase):
    def test_slug_keeps_letters_and_digits_only(self):
        self.assertEqual(slug("Rest timer: auto-start after a set"), "rest-timer-auto-start-after-a-set")
        self.assertEqual(slug("  Ünïcode & symbols!! "), "n-code-symbols")

    def test_slug_is_cut_to_the_limit_without_a_trailing_dash(self):
        self.assertEqual(slug("a" * 10 + " " + "b" * 40, limit=12), "aaaaaaaaaa-b")
        self.assertEqual(slug("aaaaaaaaaa bbbb", limit=11), "aaaaaaaaaa")

    def test_slug_never_comes_back_empty(self):
        self.assertEqual(slug("!!!"), "item")

    def test_branches_nest_under_the_run(self):
        self.assertEqual(report_branch("20261001-0200"), "research/20261001-0200/report")
        self.assertEqual(item_branch("20261001-0200", 3, "Plate calculator"), "research/20261001-0200/3-plate-calculator")

    def test_next_id_counts_past_the_highest(self):
        self.assertEqual(next_item_id([]), "r001")
        self.assertEqual(next_item_id([item(id="r007"), item(id="r012"), {"id": "junk"}]), "r013")


class Picks(unittest.TestCase):
    def test_highest_priority_first_then_the_simpler(self):
        items = [
            item(id="r001", priority=3, complexity=1),
            item(id="r002", priority=5, complexity=3),
            item(id="r003", priority=5, complexity=1),
            item(id="r004", priority=4, complexity=2),
        ]
        self.assertEqual([i["id"] for i in pick_items(items, 5)], ["r003", "r002", "r004", "r001"])

    def test_only_new_items_under_the_complexity_cap(self):
        items = [
            item(id="r001", status="done"),
            item(id="r002", status="in-review"),
            item(id="r003", status="blocked"),
            item(id="r004", complexity=4),
            item(id="r005", complexity=3),
        ]
        self.assertEqual([i["id"] for i in pick_items(items, 5)], ["r005"])

    def test_count_and_ties(self):
        items = [item(id=f"r00{n}", priority=5, complexity=2) for n in range(5, 0, -1)]
        self.assertEqual([i["id"] for i in pick_items(items, 2)], ["r001", "r002"])
        self.assertEqual(pick_items(items, 0), [])


class BacklogShape(unittest.TestCase):
    def test_a_good_backlog_passes(self):
        self.assertEqual(validate_backlog({"items": [item(), item(id="r002", status="done", evidence=[])]}), [])

    def test_the_root_must_hold_items(self):
        self.assertTrue(validate_backlog([]))
        self.assertTrue(validate_backlog({"items": "no"}))

    def test_each_defect_is_named(self):
        cases = {
            "id must look like r001": item(id="R1"),
            "priority must be an integer from 1 to 5": item(priority=6),
            "complexity must be an integer from 1 to 5": item(complexity=True),
            "status must be one of": item(status="pending"),
            "a new item needs at least one entry": item(evidence=[]),
            "evidence 0 needs a url": item(evidence=[{"quote": "no link"}]),
            "why must be a non-empty string": item(why="  "),
        }
        for message, bad in cases.items():
            with self.subTest(message=message):
                problems = validate_backlog({"items": [bad]})
                self.assertTrue(any(message in p for p in problems), problems)

    def test_duplicate_ids_are_caught(self):
        problems = validate_backlog({"items": [item(), item()]})
        self.assertTrue(any("duplicate id" in p for p in problems), problems)

    def test_the_seeded_backlog_and_sources_are_valid(self):
        self.assertEqual(validate_backlog(json.loads((RESEARCH / "backlog.json").read_text())), [])
        self.assertIsInstance(json.loads((RESEARCH / "sources.json").read_text())["sources"], list)


class ClaudeResult(unittest.TestCase):
    def test_reads_the_documented_fields(self):
        text = json.dumps({"type": "result", "subtype": "success", "is_error": False, "duration_ms": 65432, "num_turns": 17, "session_id": "abc", "total_cost_usd": 1.2345, "result": "done", "permission_denials": []})
        parsed = parse_result(text)
        self.assertTrue(parsed["ok"])
        self.assertEqual((parsed["cost_usd"], parsed["agent_seconds"], parsed["turns"], parsed["session_id"], parsed["result"]), (1.2345, 65, 17, "abc", "done"))

    def test_skips_a_warning_line_ahead_of_the_json(self):
        text = "Warning: something\n" + json.dumps({"is_error": False, "total_cost_usd": 0.5, "num_turns": 1})
        self.assertEqual(parse_result(text)["cost_usd"], 0.5)

    def test_a_dead_run_is_zeros_and_not_ok(self):
        for text in ("", "not json", "[1, 2]"):
            with self.subTest(text=text):
                parsed = parse_result(text)
                self.assertFalse(parsed["ok"])
                self.assertEqual((parsed["cost_usd"], parsed["turns"]), (0.0, 0))

    def test_an_error_result_keeps_its_cost(self):
        parsed = parse_result(json.dumps({"is_error": True, "total_cost_usd": 2.0, "subtype": "error_max_turns"}))
        self.assertFalse(parsed["ok"])
        self.assertEqual((parsed["cost_usd"], parsed["subtype"]), (2.0, "error_max_turns"))


class Prompts(unittest.TestCase):
    def test_fill_replaces_markers_and_leaves_braces_alone(self):
        self.assertEqual(fill("run <<run_id>>: {\"a\": 1}", run_id="x"), "run x: {\"a\": 1}")

    def test_fill_refuses_a_marker_without_a_value(self):
        with self.assertRaises(ValueError):
            fill("<<run_id>> and <<nope>>", run_id="x")

    def test_the_real_prompts_use_only_the_markers_the_script_fills(self):
        """A marker renamed on one side only would reach the agent as literal text."""
        research = (RESEARCH / "prompts" / "research.md").read_text()
        implement = (RESEARCH / "prompts" / "implement.md").read_text()
        fill(research, run_id="r", date="d", findings_file="f", next_id="r001")
        fill(implement, run_id="r", item_json="{}", pr_body_path="p", branch="b", findings_file="f", verify_command="v")
        self.assertIn("<<findings_file>>", research)
        self.assertIn("<<item_json>>", implement)


class Ledger(unittest.TestCase):
    def test_rows_round_trip_with_a_header_written_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "ledger.csv"
            append_ledger(path, ledger_row("run1", "2026-10-01T02:00:00Z", "research", "", "research", "m", result(cost_usd=4.0, agent_seconds=1200), 1500, "research/run1/report", "", "ok"))
            append_ledger(path, ledger_row("run1", "2026-10-01T02:00:00Z", "item", "r001", "Rest timer", "m", result(cost_usd=6.0, agent_seconds=600), 2400, "research/run1/1-rest-timer", "https://github.com/x/pull/1", "ok"))
            append_ledger(path, ledger_row("run2", "2026-10-08T02:00:00Z", "item", "r002", "Plates", "m", result(cost_usd=1.0, agent_seconds=60), 120, "b", "", "checks failed"))
            rows = read_ledger(path)
            self.assertEqual(path.read_text().count("run_id,"), 1)
            self.assertEqual([row["step"] for row in rows], ["research", "item", "item"])
            self.assertEqual(list(rows[0].keys()), LEDGER_COLUMNS)
            self.assertEqual(rows[1]["cost_usd"], "6.0000")
            self.assertAlmostEqual(run_cost(rows, "run1"), 10.0)
            by_run, overall = totals(rows)
            self.assertEqual(by_run["run1"]["steps"], 2)
            self.assertAlmostEqual(by_run["run1"]["agent_minutes"], 30.0)
            self.assertAlmostEqual(by_run["run1"]["wall_minutes"], 65.0)
            self.assertAlmostEqual(overall["cost_usd"], 11.0)
            table = format_totals(by_run, overall)
            self.assertIn("run1", table)
            self.assertTrue(table.strip().endswith("11.00"))

    def test_a_missing_ledger_is_empty(self):
        self.assertEqual(read_ledger("/nonexistent/ledger.csv"), [])
        self.assertEqual(totals([]), ({}, {"steps": 0, "agent_minutes": 0.0, "wall_minutes": 0.0, "cost_usd": 0.0}))


class PullRequestText(unittest.TestCase):
    def test_the_footer_carries_the_decision_and_the_bill(self):
        footer = pr_footer(item(), "20261001-0200", 2, "https://github.com/x/pull/9", "research/findings/20261001-0200.md", result(cost_usd=7.25, agent_seconds=900, turns=80), 1800)
        for expected in ("Research item r001", "priority 4/5", "complexity 2/5", "stacked on https://github.com/x/pull/9", "item 2 of run", "saves a tap per set", "[r/Fitness](https://www.reddit.com/r/Fitness/comments/x/)", "“auto rest timer”", "$7.25", "80 turns", "agent 15 min", "30 min wall"):
            with self.subTest(expected=expected):
                self.assertIn(expected, footer)

    def test_the_first_item_is_not_stacked_on_anything(self):
        self.assertNotIn("stacked on", pr_footer(item(), "r", 1, "", "f", result(), 0))

    def test_the_report_body_lists_the_picks_or_says_there_are_none(self):
        body = report_pr_body("r", "research/findings/r.md", "Five lines.", result(), 300, [item()])
        self.assertIn("r001 · priority 4 · complexity 2 · Rest timer", body)
        self.assertIn("Five lines.", body)
        self.assertIn("no new item", report_pr_body("r", "f", "", result(), 0, []))


class CommandLine(unittest.TestCase):
    def test_the_prompt_comes_before_the_list_options(self):
        """--disallowedTools and --add-dir take every following argument; a prompt after them is lost."""
        command = claude_command("the prompt", "claude-opus-5-5", 12.5, 300, add_dirs=["/tmp/x"], resume="sess")
        self.assertEqual(command[:3], ["claude", "-p", "the prompt"])
        self.assertEqual(command[command.index("--max-budget-usd") + 1], "12.50")
        self.assertEqual(command[command.index("--max-turns") + 1], "300")
        self.assertEqual(command[command.index("--disallowedTools") + 1], "Bash(git push:*),Bash(gh:*)")
        self.assertEqual(command[command.index("--add-dir") + 1], "/tmp/x")
        self.assertEqual(command[command.index("--resume") + 1], "sess")
        self.assertEqual(command[command.index("--output-format") + 1], "json")


class Units(unittest.TestCase):
    """The workflow and the systemd units run this script; read as text, like the workflows are."""

    def test_the_workflow_runs_the_script_weekly_with_the_inputs_in_the_environment(self):
        workflow = (ROOT / ".github/workflows/research.yml").read_text()
        self.assertIn('cron: "0 2 * * 1"', workflow)
        self.assertIn("run: python3 tools/research_run.py run", workflow)
        self.assertIn("GAINS_RESEARCH_TASKS:", workflow)
        self.assertIn("GAINS_RESEARCH_SKIP_RESEARCH:", workflow)
        self.assertIn("ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}", workflow)
        self.assertIn("timeout-minutes: 360", workflow)
        for line in workflow.splitlines():
            if "uses:" in line and "./.github" not in line:
                self.assertRegex(line, r"@[0-9a-f]{40} # v", f"not pinned to a commit: {line.strip()}")

    def test_the_service_runs_the_script_as_its_own_user(self):
        unit = (ROOT / "deploy" / "gains-research.service").read_text()
        self.assertRegex(unit, r"(?m)^User=gains-research$")
        self.assertRegex(unit, r"(?m)^ExecStart=.*tools/research_run\.py run$")
        self.assertRegex(unit, r"(?m)^EnvironmentFile=")

    def test_the_timer_is_weekly_and_persistent(self):
        timer = (ROOT / "deploy" / "gains-research.timer").read_text()
        self.assertRegex(timer, r"(?m)^OnCalendar=Mon ")
        self.assertRegex(timer, r"(?m)^Persistent=true$")


if __name__ == "__main__":
    unittest.main()

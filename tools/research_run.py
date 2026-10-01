#!/usr/bin/env python3
"""One research-and-build round: what lifters ask gym apps for, then the top items as stacked pull requests.

Run from a clone of its own on the box (research/README.md), as the gains-research timer does:

  python3 tools/research_run.py run                       Research, then the top items (five by default).
  python3 tools/research_run.py run --tasks 0             Research only.
  python3 tools/research_run.py run --skip-research       Build from the backlog as it stands.
  python3 tools/research_run.py run --dry-run             Print the plan and the commands; change nothing.
  python3 tools/research_run.py next                      Which items the next run would take, and why.
  python3 tools/research_run.py totals                    Time and money so far, per run and overall.

Every step is one `claude -p` call: Claude Code, headless, paid for with ANTHROPIC_API_KEY. Its
JSON result carries the dollars and the minutes, which go to research/ledger.csv with the wall
time around it (Gradle included). The orchestration stays here, in plain Python: branches,
commits, pushes and pull requests are this script's, never the agent's, so nothing reaches
GitHub except on a branch of its own, and the owner approves everything by merging.

A run is a stack of pull requests on origin:

  research/<run>/report          The findings, the backlog, the ledger. Base: main.
  research/<run>/1-<slug>        The first item. Base: the report branch.
  research/<run>/2-<slug>        The second. Base: the first.
  ...

Merged in order, each one lands on main and GitHub retargets the next (with "Automatically
delete head branches" on, research/README.md). The ledger and the backlog statuses are only ever
committed on the report branch, so an item branch carries nothing but the item's own work.

The arithmetic (which items to take, what the ledger says, what a branch is called) is plain
functions with no git or network in them, so tools/test_research_run.py checks them directly.
"""

import argparse
import csv
import datetime as dt
import json
import os
import pathlib
import re
import shutil
import subprocess
import time

import gha

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESEARCH = ROOT / "research"
BACKLOG = RESEARCH / "backlog.json"
SOURCES = RESEARCH / "sources.json"
LEDGER = RESEARCH / "ledger.csv"
FINDINGS = RESEARCH / "findings"
PROMPTS = RESEARCH / "prompts"

# Where a run keeps what is not for the repository: the agent's logs, the JSON results, the pull
# request bodies it drafts. One directory per run, under the user running it.
RUNS = pathlib.Path(os.environ.get("GAINS_RESEARCH_RUNS", str(pathlib.Path.home() / ".gains-research" / "runs")))

# The model every step runs on unless the environment or --model says otherwise. The full id,
# not an alias, so the ledger's rows stay comparable when the alias moves to a newer model.
DEFAULT_MODEL = os.environ.get("GAINS_RESEARCH_MODEL", "claude-opus-5-5")

# How the agent may act. bypassPermissions is what an unattended run needs for Gradle, git and
# the web, and is what the systemd unit's sandbox is for (research/README.md, "Permissions");
# GAINS_RESEARCH_PERMISSION_MODE=dontAsk with an allow list in the clone's settings is the
# stricter alternative described there.
PERMISSION_MODE = os.environ.get("GAINS_RESEARCH_PERMISSION_MODE", "bypassPermissions")

# What the agent never does, whatever the permission mode: this script pushes and opens the
# pull requests, so a run is always exactly the stack described above.
DISALLOWED_TOOLS = "Bash(git push:*),Bash(gh:*)"

# Caps per step, in dollars and in agentic turns, and a cap for the whole run. --max-budget-usd
# stops the agent; the run cap stops this script from starting the next item.
RESEARCH_BUDGET_USD = 20.0
TASK_BUDGET_USD = 25.0
RUN_BUDGET_USD = 150.0
RESEARCH_MAX_TURNS = 300
TASK_MAX_TURNS = 400
RESEARCH_TIMEOUT_S = 90 * 60
TASK_TIMEOUT_S = 120 * 60

# Items the headless run takes on. Bigger ones (a schema or sync change, native iOS work) are
# kept in the backlog for the owner but not attempted unattended.
MAX_COMPLEXITY = 3

# What CI runs on Linux, minus the iOS klib compile (the Kotlin/Native toolchain is a
# multi-gigabyte download the box doesn't need; CI compiles it on the pull request anyway).
VERIFY = ["./gradlew", ":shared:desktopTest", ":composeApp:desktopTest", "-Pgains.android=false", "--no-daemon"]

ITEM_ID = re.compile(r"^r\d{3,}$")
STATUSES = ("new", "in-review", "done", "rejected", "blocked")
LEDGER_COLUMNS = [
    "run_id", "started_utc", "step", "item_id", "title", "model", "turns",
    "agent_seconds", "wall_seconds", "cost_usd", "branch", "pull_request", "outcome",
]

# The author of the commits, the agent's and this script's alike: what the repository's
# Claude Code commits already carry.
AUTHOR_NAME = "Claude"
AUTHOR_EMAIL = "noreply@anthropic.com"


# --- the arithmetic, kept free of git, Claude and the network -------------------------------


def run_id_now(now=None):
    """'20261001-0200': sortable, unique per minute, legal in a branch name."""
    now = now or dt.datetime.now(dt.timezone.utc)
    return now.strftime("%Y%m%d-%H%M")


def slug(title, limit=40):
    """'Rest timer: auto-start after a set' -> 'rest-timer-auto-start-after-a-set'."""
    words = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    cut = words[:limit].rstrip("-")
    return cut or "item"


def report_branch(run_id):
    return f"research/{run_id}/report"


def item_branch(run_id, position, title):
    return f"research/{run_id}/{position}-{slug(title)}"


def next_item_id(items):
    """'r001' for an empty backlog, else one past the highest id there is."""
    highest = 0
    for item in items:
        match = re.match(r"^r(\d+)$", str(item.get("id", "")))
        if match:
            highest = max(highest, int(match.group(1)))
    return f"r{highest + 1:03d}"


def pick_items(items, count, max_complexity=MAX_COMPLEXITY):
    """The items a run takes, in order: highest priority first, the simpler one on a tie.

    Only `new` items at or under the complexity cap are candidates. The id breaks the last tie,
    so two runs over the same backlog pick the same things in the same order.
    """
    candidates = [
        item for item in items
        if item.get("status") == "new" and int(item.get("complexity", 9)) <= max_complexity
    ]
    candidates.sort(key=lambda item: (-int(item.get("priority", 0)), int(item.get("complexity", 9)), item.get("id", "")))
    return candidates[:count]


def validate_backlog(data):
    """Everything wrong with a backlog, as messages; an empty list means it can be committed.

    The research agent writes this file, so what it must hold is checked here rather than
    trusted: the next run's picks, the ledger and the pull request bodies all read it.
    """
    problems = []
    if not isinstance(data, dict) or not isinstance(data.get("items"), list):
        return ['the backlog must be an object with an "items" list']
    seen = set()
    for n, item in enumerate(data["items"]):
        if not isinstance(item, dict):
            problems.append(f"item {n}: not an object")
            continue
        item_id = item.get("id")
        where = f"item {item_id or n}"
        if not isinstance(item_id, str) or not ITEM_ID.match(item_id):
            problems.append(f"{where}: id must look like r001")
        elif item_id in seen:
            problems.append(f"{where}: duplicate id")
        seen.add(item_id)
        for key in ("title", "why", "area", "found_in_run"):
            if not isinstance(item.get(key), str) or not item[key].strip():
                problems.append(f"{where}: {key} must be a non-empty string")
        for key in ("priority", "complexity"):
            value = item.get(key)
            if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= 5:
                problems.append(f"{where}: {key} must be an integer from 1 to 5")
        if item.get("status") not in STATUSES:
            problems.append(f"{where}: status must be one of {', '.join(STATUSES)}")
        evidence = item.get("evidence")
        if not isinstance(evidence, list) or (item.get("status") == "new" and not evidence):
            problems.append(f"{where}: evidence must be a list, and a new item needs at least one entry")
        else:
            for m, entry in enumerate(evidence):
                if not isinstance(entry, dict) or not isinstance(entry.get("url"), str) or not entry["url"].startswith("http"):
                    problems.append(f"{where}: evidence {m} needs a url")
    return problems


def parse_result(text):
    """What a `claude -p --output-format json` run says about itself, with zeros for a run that died.

    The output is one JSON object; a warning on stdout ahead of it is skipped by taking the last
    line that parses. Fields: https://code.claude.com/docs/en/headless (`total_cost_usd`,
    `duration_ms`, `num_turns`, `session_id`, `is_error`, `result`, `permission_denials`).
    """
    data = None
    for line in reversed((text or "").strip().splitlines()):
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            data = json.loads(line)
            break
        except ValueError:
            continue
    if data is None:
        try:
            data = json.loads(text)
        except (TypeError, ValueError):
            data = {}
    if not isinstance(data, dict):
        data = {}
    return {
        "ok": bool(data) and not data.get("is_error", False),
        "cost_usd": float(data.get("total_cost_usd") or 0.0),
        "agent_seconds": round(float(data.get("duration_ms") or 0) / 1000),
        "turns": int(data.get("num_turns") or 0),
        "session_id": data.get("session_id") or "",
        "result": data.get("result") or "",
        "subtype": data.get("subtype") or ("error" if data.get("is_error") else ""),
        "denials": data.get("permission_denials") or [],
    }


def fill(template, **values):
    """Replace `<<name>>` markers. Not str.format: the prompts quote JSON, which is full of braces."""
    for name, value in values.items():
        template = template.replace(f"<<{name}>>", str(value))
    missing = re.findall(r"<<([a-z_]+)>>", template)
    if missing:
        raise ValueError(f"prompt placeholders without a value: {', '.join(sorted(set(missing)))}")
    return template


def ledger_row(run_id, started, step, item_id, title, model, result, wall_seconds, branch, pull_request, outcome):
    return {
        "run_id": run_id,
        "started_utc": started,
        "step": step,
        "item_id": item_id,
        "title": title,
        "model": model,
        "turns": result["turns"],
        "agent_seconds": result["agent_seconds"],
        "wall_seconds": int(wall_seconds),
        "cost_usd": f"{result['cost_usd']:.4f}",
        "branch": branch,
        "pull_request": pull_request,
        "outcome": outcome,
    }


def append_ledger(path, row):
    """One more line, with the header the first time."""
    path = pathlib.Path(path)
    new = not path.exists() or path.stat().st_size == 0
    with open(path, "a", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=LEDGER_COLUMNS)
        if new:
            writer.writeheader()
        writer.writerow(row)


def read_ledger(path):
    path = pathlib.Path(path)
    if not path.exists():
        return []
    with open(path, newline="") as handle:
        return list(csv.DictReader(handle))


def totals(rows):
    """Per run, then overall: how many steps, agent and wall minutes, dollars."""
    by_run = {}
    for row in rows:
        run = by_run.setdefault(row["run_id"], {"steps": 0, "agent_minutes": 0.0, "wall_minutes": 0.0, "cost_usd": 0.0})
        run["steps"] += 1
        run["agent_minutes"] += float(row.get("agent_seconds") or 0) / 60
        run["wall_minutes"] += float(row.get("wall_seconds") or 0) / 60
        run["cost_usd"] += float(row.get("cost_usd") or 0)
    overall = {"steps": 0, "agent_minutes": 0.0, "wall_minutes": 0.0, "cost_usd": 0.0}
    for run in by_run.values():
        for key in overall:
            overall[key] += run[key]
    return by_run, overall


def format_totals(by_run, overall):
    lines = [f"{'run':16} {'steps':>5} {'agent min':>9} {'wall min':>8} {'USD':>8}"]
    for run_id in sorted(by_run):
        run = by_run[run_id]
        lines.append(f"{run_id:16} {run['steps']:5d} {run['agent_minutes']:9.0f} {run['wall_minutes']:8.0f} {run['cost_usd']:8.2f}")
    lines.append(f"{'total':16} {overall['steps']:5d} {overall['agent_minutes']:9.0f} {overall['wall_minutes']:8.0f} {overall['cost_usd']:8.2f}")
    return "\n".join(lines)


def run_cost(rows, run_id):
    return sum(float(row.get("cost_usd") or 0) for row in rows if row["run_id"] == run_id)


def evidence_lines(item):
    lines = []
    for entry in item.get("evidence", []):
        quote = str(entry.get("quote", "")).strip()
        source = str(entry.get("source", "")).strip()
        label = f"[{source}]({entry['url']})" if source else entry["url"]
        lines.append(f"- {label}" + (f": “{quote}”" if quote else ""))
    return "\n".join(lines) or "- (none recorded)"


def pr_footer(item, run_id, position, base_pr, report_path, result, wall_seconds):
    """What this script knows and the agent doesn't: where the item came from, what it cost."""
    stack = f"stacked on {base_pr}, " if base_pr else ""
    return "\n".join([
        "",
        "---",
        "",
        f"**Research item {item['id']}** · priority {item['priority']}/5 · complexity {item['complexity']}/5 · "
        f"{stack}item {position} of run `{run_id}` ([report]({report_path})).",
        "",
        f"**Why this one:** {item['why'].strip()}",
        "",
        "**Evidence:**",
        evidence_lines(item),
        "",
        f"**Cost:** ${result['cost_usd']:.2f} · {result['turns']} turns · agent {result['agent_seconds'] / 60:.0f} min · "
        f"{wall_seconds / 60:.0f} min wall, checks included · model `{result.get('model', '')}`.",
    ])


def report_pr_body(run_id, findings_path, summary, result, wall_seconds, picked):
    picks = "\n".join(
        f"- {item['id']} · priority {item['priority']} · complexity {item['complexity']} · {item['title']}" for item in picked
    ) or "- nothing: no new item at or under the complexity cap"
    return "\n".join([
        f"The research round `{run_id}`: what lifters are asking gym apps for, what the popular apps "
        f"already have, and the backlog that follows from it. The report is [`{findings_path}`]({findings_path}); "
        "the backlog, the sources read and the ledger are next to it.",
        "",
        summary.strip() or "(the agent left no summary)",
        "",
        "**Taken on in this run, as the pull requests stacked on this one:**",
        picks,
        "",
        f"**Cost of the research:** ${result['cost_usd']:.2f} · {result['turns']} turns · "
        f"agent {result['agent_seconds'] / 60:.0f} min · {wall_seconds / 60:.0f} min wall · model `{result.get('model', '')}`.",
        "",
        "Merge this one first; each item's pull request retargets to `main` as the one under it merges.",
    ])


def claude_command(prompt, model, budget, max_turns, add_dirs=(), resume=None, permission_mode=PERMISSION_MODE):
    """The `claude -p` invocation. The prompt comes right after -p: the list options take every
    following argument as theirs, so it can't go last."""
    command = [
        "claude", "-p", prompt,
        "--output-format", "json",
        "--model", model,
        "--permission-mode", permission_mode,
        "--max-budget-usd", f"{budget:.2f}",
        "--max-turns", str(max_turns),
        "--disallowedTools", DISALLOWED_TOOLS,
    ]
    for directory in add_dirs:
        command += ["--add-dir", str(directory)]
    if resume:
        command += ["--resume", resume]
    return command


# --- the run ----------------------------------------------------------------------------


def git(*args, **kwargs):
    return gha.run("git", *args, cwd=ROOT, **kwargs)


def git_out(*args):
    return gha.stdout("git", *args, cwd=ROOT)


def say(message):
    print(f"== {message}", flush=True)


def check_tools(dry_run):
    for tool in ("claude", "gh", "git"):
        if shutil.which(tool) is None:
            gha.fail(f"{tool} is not on PATH")
    if git_out("status", "--porcelain"):
        gha.fail("the working tree is not clean; this script needs a clone of its own")
    if not dry_run:
        if not os.environ.get("ANTHROPIC_API_KEY"):
            gha.fail("ANTHROPIC_API_KEY is not set")
        gha.run("gh", "auth", "status", show=False)


def claude_step(prompt, log_path, model, budget, max_turns, timeout, add_dirs=(), resume=None):
    """One headless Claude Code run; its parsed JSON result, with the model and the wall time added."""
    command = claude_command(prompt, model, budget, max_turns, add_dirs, resume)
    started = time.monotonic()
    env = dict(os.environ)
    env.setdefault("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
    # Gradle runs for minutes; the Bash tool's default limit is two.
    env.setdefault("BASH_DEFAULT_TIMEOUT_MS", str(30 * 60 * 1000))
    env.setdefault("BASH_MAX_TIMEOUT_MS", str(60 * 60 * 1000))
    print(f"$ claude -p ... --model {model} --max-budget-usd {budget:.2f} (log: {log_path})", flush=True)
    try:
        completed = subprocess.run(command, cwd=ROOT, env=env, text=True, capture_output=True, timeout=timeout)
        stdout, stderr, timed_out = completed.stdout, completed.stderr, False
    except subprocess.TimeoutExpired as expired:
        stdout = expired.stdout.decode() if isinstance(expired.stdout, bytes) else (expired.stdout or "")
        stderr = expired.stderr.decode() if isinstance(expired.stderr, bytes) else (expired.stderr or "")
        timed_out = True
    log_path = pathlib.Path(log_path)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(stdout + ("\n--- stderr ---\n" + stderr if stderr else ""))
    result = parse_result(stdout)
    result["model"] = model
    result["wall_seconds"] = time.monotonic() - started
    if timed_out:
        result["ok"] = False
        result["subtype"] = "timeout"
    if result["denials"]:
        print(f"   {len(result['denials'])} tool calls were denied; see the log", flush=True)
    return result


def load_backlog():
    return json.loads(BACKLOG.read_text())


def save_backlog(data):
    BACKLOG.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def discard_research_changes():
    """Whatever an agent did under research/ that isn't being kept."""
    git("checkout", "--quiet", "--", "research")
    git("clean", "--quiet", "-fd", "research")


def commit_research_state(run_id, message):
    """The ledger and the backlog statuses, committed on the report branch and pushed at once,
    so a run that dies halfway has still recorded what it spent. Only called on that branch."""
    git("add", "research")
    if not git_out("status", "--porcelain", "research"):
        return
    git("commit", "--quiet", "-m", message)
    git("push", "--quiet", "-u", "origin", report_branch(run_id))


def open_pull_request(head, base, title, body_path):
    return gha.stdout("gh", "pr", "create", "--base", base, "--head", head, "--title", title, "--body-file", str(body_path), cwd=ROOT)


def verify():
    """CI's Linux checks, run here before anything is pushed; the output's tail if they fail."""
    print("$ " + " ".join(VERIFY), flush=True)
    completed = subprocess.run(VERIFY, cwd=ROOT, text=True, capture_output=True)
    if completed.returncode == 0:
        return True, ""
    tail = "\n".join((completed.stdout + "\n" + completed.stderr).splitlines()[-150:])
    return False, tail


def research_prompt(run_id, date):
    return fill(
        (PROMPTS / "research.md").read_text(),
        run_id=run_id,
        date=date,
        findings_file=f"research/findings/{run_id}.md",
        next_id=next_item_id(load_backlog()["items"]),
    )


def implement_prompt(run_id, item, branch, pr_body):
    findings = FINDINGS / f"{run_id}.md"
    return fill(
        (PROMPTS / "implement.md").read_text(),
        run_id=run_id,
        item_json=json.dumps(item, indent=2, ensure_ascii=False),
        pr_body_path=pr_body,
        branch=branch,
        findings_file=f"research/findings/{run_id}.md" if findings.exists() else "research/findings/",
        verify_command=" ".join(VERIFY),
    )


def research_step(run_id, run_dir, args, started):
    """The research prompt, then the files it must leave behind, validated; the result and an outcome."""
    findings_path = FINDINGS / f"{run_id}.md"
    result = claude_step(
        research_prompt(run_id, started[:10]), run_dir / "research.log", args.model, args.research_budget,
        RESEARCH_MAX_TURNS, RESEARCH_TIMEOUT_S,
    )
    if not result["ok"]:
        return result, f"agent stopped: {result['subtype'] or 'error'}"
    if not findings_path.exists():
        return result, "no findings file written"
    try:
        problems = validate_backlog(load_backlog())
        json.loads(SOURCES.read_text())
    except ValueError as error:
        problems = [f"a research file is not valid JSON: {error}"]
    if problems:
        (run_dir / "backlog-problems.txt").write_text("\n".join(problems))
        return result, "backlog invalid: " + "; ".join(problems[:3])
    return result, "ok"


def implement_step(run_id, position, item, base_branch, run_dir, args):
    """One item on its own branch off the previous one: the agent's commits, the checks, the push.

    Returns the branch, the result, an outcome and the pull request body the agent drafted. The
    branch is pushed only on "ok"; otherwise it stays local, with the logs, for anyone curious.
    """
    branch = item_branch(run_id, position, item["title"])
    task_dir = run_dir / f"{position}-{item['id']}"
    task_dir.mkdir(parents=True, exist_ok=True)
    pr_body = task_dir / "pr.md"
    git("checkout", "--quiet", "-B", branch, base_branch)
    result = claude_step(
        implement_prompt(run_id, item, branch, pr_body), task_dir / "implement.log", args.model, args.task_budget,
        TASK_MAX_TURNS, TASK_TIMEOUT_S, add_dirs=[task_dir],
    )
    # The agent commits its own work; anything it left uncommitted is folded in so the branch is
    # whole. Nothing under research/ belongs on an item branch: that is the report branch's.
    discard_research_changes()
    fold_uncommitted(base_branch, f"{item['title']} (research item {item['id']})")
    if git_out("rev-list", "--count", f"{base_branch}..HEAD") == "0":
        return branch, result, "no change" if result["ok"] else f"agent stopped: {result['subtype'] or 'error'}", pr_body
    if not args.no_verify:
        ok, tail = verify()
        if not ok and result["session_id"]:
            say("the checks failed; one more round with the failure")
            (task_dir / "verify-failure.txt").write_text(tail)
            retry = claude_step(
                "The checks failed after your change. Make them pass, then commit the fix on this branch "
                "and refresh the pull request description. Everything else from before still holds: "
                "never push, never use gh.\n\n```\n" + tail + "\n```",
                task_dir / "retry.log", args.model, args.task_budget, TASK_MAX_TURNS // 2, TASK_TIMEOUT_S,
                add_dirs=[task_dir], resume=result["session_id"],
            )
            for key in ("cost_usd", "agent_seconds", "turns", "wall_seconds"):
                result[key] += retry[key]
            discard_research_changes()
            fold_uncommitted(base_branch, f"{item['title']} (research item {item['id']})")
            ok, tail = verify()
        if not ok:
            (task_dir / "verify-failure.txt").write_text(tail)
            return branch, result, "checks failed", pr_body
    if not pr_body.exists():
        pr_body.write_text(f"{item['title']}\n\n(The agent left no description; its final message follows.)\n\n{result['result']}\n")
    git("push", "--quiet", "-u", "origin", branch)
    return branch, result, "ok", pr_body


def fold_uncommitted(base_branch, subject):
    if not git_out("status", "--porcelain"):
        return
    git("add", "-A")
    if git_out("rev-list", "--count", f"{base_branch}..HEAD") == "0":
        git("commit", "--quiet", "-m", subject)
    else:
        git("commit", "--quiet", "--amend", "--no-edit")


def mark(item, **fields):
    """Update one backlog item on disk."""
    data = load_backlog()
    for entry in data["items"]:
        if entry["id"] == item["id"]:
            entry.update(fields)
    save_backlog(data)


def plan(run_id, args):
    """What `run` would do, without doing it: the branches, the picks, the commands."""
    print(f"report branch: {report_branch(run_id)} (from origin/main)")
    if not args.skip_research:
        print("research step:")
        print("  $ " + " ".join(shell_word(w) for w in claude_command("<research prompt>", args.model, args.research_budget, RESEARCH_MAX_TURNS)))
    picked = pick_items(load_backlog()["items"], args.tasks)
    if not picked:
        print(f"items: none (no new item at or under complexity {MAX_COMPLEXITY})")
    base = report_branch(run_id)
    for position, item in enumerate(picked, start=1):
        branch = item_branch(run_id, position, item["title"])
        print(f"item {position}: {item['id']} · priority {item['priority']} · complexity {item['complexity']} · {item['title']}")
        print(f"  branch {branch} off {base}")
        print("  $ " + " ".join(shell_word(w) for w in claude_command("<implement prompt>", args.model, args.task_budget, TASK_MAX_TURNS, add_dirs=[RUNS / run_id / f"{position}-{item['id']}"])))
        if not args.no_verify:
            print("  $ " + " ".join(VERIFY))
        print(f"  $ gh pr create --base {base} --head {branch}")
        base = branch


def shell_word(value):
    return value if re.match(r"^[A-Za-z0-9_./:=@*(),<>-]+$", value) else repr(value)


def run(args):
    check_tools(args.dry_run)
    run_id = args.run_id or run_id_now()
    if args.dry_run:
        plan(run_id, args)
        return
    started = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    run_dir = RUNS / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    say(f"run {run_id} (logs in {run_dir})")
    git("fetch", "--quiet", "origin", "main")
    git("checkout", "--quiet", "-B", report_branch(run_id), "origin/main")
    git("config", "user.name", AUTHOR_NAME)
    git("config", "user.email", AUTHOR_EMAIL)
    findings_rel = f"research/findings/{run_id}.md"

    # 1. Research, on the report branch. With --skip-research the branch still exists on origin
    #    (as main is), so the first item's pull request has a base to point at.
    report_pr = ""
    if args.skip_research:
        git("push", "--quiet", "-u", "origin", report_branch(run_id))
    else:
        say("research")
        research_wall = time.monotonic()
        result, outcome = research_step(run_id, run_dir, args, started)
        wall = time.monotonic() - research_wall
        if outcome != "ok":
            # The agent's half-written research files stay out of the repository; the ledger
            # still records the attempt, so the money is never invisible.
            discard_research_changes()
        append_ledger(LEDGER, ledger_row(run_id, started, "research", "", "research", args.model, result, wall, report_branch(run_id), "", outcome))
        if outcome != "ok":
            commit_research_state(run_id, f"Research {run_id}: the round stopped ({outcome}); ledger only")
            gha.fail(f"research {outcome}")
        picked = pick_items(load_backlog()["items"], args.tasks)
        body = run_dir / "report-pr.md"
        body.write_text(report_pr_body(run_id, findings_rel, result["result"], result, wall, picked))
        commit_research_state(run_id, f"Research {run_id}: what lifters ask gym apps for")
        report_pr = open_pull_request(report_branch(run_id), "main", f"Research {started[:10]}: what lifters ask gym apps for", body)
        say(f"report: {report_pr}")

    # 2. The items, each on its branch off the previous one.
    picked = pick_items(load_backlog()["items"], args.tasks)
    base_branch, base_pr = report_branch(run_id), report_pr
    for position, item in enumerate(picked, start=1):
        spent = run_cost(read_ledger(LEDGER), run_id)
        if spent >= args.run_budget:
            say(f"run budget reached (${spent:.2f}); stopping before {item['id']}")
            break
        say(f"item {position}/{len(picked)}: {item['id']} {item['title']}")
        item_wall = time.monotonic()
        branch, result, outcome, pr_body = implement_step(run_id, position, item, base_branch, run_dir, args)
        wall = time.monotonic() - item_wall
        pull_request = ""
        if outcome == "ok":
            with open(pr_body, "a") as handle:
                handle.write(pr_footer(item, run_id, position, base_pr, findings_rel, result, wall))
            pull_request = open_pull_request(branch, base_branch, git_out("log", "-1", "--format=%s"), pr_body)
            say(f"pull request: {pull_request}")
            base_branch, base_pr = branch, pull_request
        else:
            say(f"{item['id']}: {outcome}")
        # Back on the report branch for the bookkeeping: an item branch never carries it.
        git("checkout", "--quiet", report_branch(run_id))
        mark(
            item,
            status="in-review" if outcome == "ok" else "blocked",
            pull_request=pull_request,
            note="" if outcome == "ok" else f"run {run_id}: {outcome}; set status back to new to retry",
        )
        append_ledger(LEDGER, ledger_row(run_id, started, "item", item["id"], item["title"], args.model, result, wall, branch, pull_request, outcome))
        commit_research_state(run_id, f"Research {run_id}: ledger and status for {item['id']}")

    if args.skip_research and git_out("rev-list", "--count", f"origin/main..{report_branch(run_id)}") != "0":
        body = run_dir / "report-pr.md"
        body.write_text(f"No research this round: the items came from the backlog as it stood. The ledger rows and the statuses of run `{run_id}`.\n")
        say("report: " + open_pull_request(report_branch(run_id), "main", f"Research {started[:10]}: ledger and statuses", body))
    by_run, _ = totals(read_ledger(LEDGER))
    this = by_run.get(run_id, {"cost_usd": 0.0, "wall_minutes": 0.0})
    say(f"done: ${this['cost_usd']:.2f}, {this['wall_minutes']:.0f} min wall")


def show_next(args):
    picked = pick_items(load_backlog()["items"], args.tasks)
    if not picked:
        print(f"nothing to take: no new item at or under complexity {MAX_COMPLEXITY}")
    for position, item in enumerate(picked, start=1):
        print(f"{position}. {item['id']} · priority {item['priority']} · complexity {item['complexity']} · {item['title']}")
        print(f"   {item['why'].strip()}")


def show_totals(args):
    by_run, overall = totals(read_ledger(LEDGER))
    print(format_totals(by_run, overall))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    runner = commands.add_parser("run", help="research, then the top items as stacked pull requests")
    runner.add_argument("--tasks", type=int, default=5, help="how many items to take on (default 5; 0 for research only)")
    runner.add_argument("--skip-research", action="store_true", help="no research round; take items from the backlog as it is")
    runner.add_argument("--no-verify", action="store_true", help="don't run the Gradle checks here (the agent still runs them)")
    runner.add_argument("--dry-run", action="store_true", help="print the plan and the commands; run nothing, push nothing")
    runner.add_argument("--model", default=DEFAULT_MODEL)
    runner.add_argument("--research-budget", type=float, default=RESEARCH_BUDGET_USD, help="USD cap for the research step")
    runner.add_argument("--task-budget", type=float, default=TASK_BUDGET_USD, help="USD cap per item")
    runner.add_argument("--run-budget", type=float, default=RUN_BUDGET_USD, help="USD cap for the whole run")
    runner.add_argument("--run-id", help="a run id to use instead of the current minute (for reruns)")
    runner.set_defaults(func=run)
    nxt = commands.add_parser("next", help="the items the next run would take")
    nxt.add_argument("--tasks", type=int, default=5)
    nxt.set_defaults(func=show_next)
    tot = commands.add_parser("totals", help="time and money so far")
    tot.set_defaults(func=show_totals)
    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()

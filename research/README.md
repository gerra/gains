# Research rounds

A recurring, unattended round that finds out what people who lift want from a gym app, scores
each wish, builds the top ones and opens them as stacked pull requests. The owner's only job is
to merge, or not. Everything the round learns stays here, so the next round reads it instead of
finding it again, and every dollar and minute it spends is in `ledger.csv`.

The direction every round works under: a smooth, satisfying UI that is easy and fulfilling to use
at the rack. Feel, polish and small conveniences before big new systems.

- [How a round runs](#how-a-round-runs)
- [What you approve](#what-you-approve)
- [Where it runs: GitHub Actions](#where-it-runs-github-actions)
- [Or a box of your own](#or-a-box-of-your-own)
- [Time and money](#time-and-money)
- [Cadence, and running it by hand](#cadence-and-running-it-by-hand)
- [The files](#the-files)
- [Permissions](#permissions)
- [Why this shape, and the alternatives](#why-this-shape-and-the-alternatives)

## How a round runs

[`tools/research_run.py`](../tools/research_run.py) drives it; the
[Research round workflow](../.github/workflows/research.yml) starts it once a week. Each step is
one headless Claude Code call (`claude -p`), paid for with the API key, with a dollar cap and a
turn cap. The script does the git and GitHub work itself, so the agent never pushes and the
round always has the same shape:

1. **A fresh branch off `main`**, `research/<run>/report`, where `<run>` is the start minute in
   UTC, `20261001-0200`.
2. **Research** ([`prompts/research.md`](prompts/research.md)). The agent reads `backlog.json`,
   `sources.json`, `competitors.md` and the two newest reports first, then searches Reddit, app
   store reviews, blogs, competitor changelogs and the like, fanning out to subagents by source.
   It writes `findings/<run>.md`, appends to the backlog with a `priority` and a `complexity`
   for every item and a `why` a reviewer can judge, appends every URL it read to `sources.json`
   and updates the matrix. The script validates the backlog's shape and throws the round away if
   it is wrong; the ledger keeps the cost either way.
3. **The report's pull request**, base `main`: the findings, the backlog, the sources, the
   ledger row. Its description is the agent's summary and the list of what the round took on.
4. **The items.** `pick_items` takes the `new` items with complexity at most 3, highest
   priority first, the simpler one on a tie, five of them. For each, in order: a branch off the
   previous one (`research/<run>/1-<slug>`, then `2-…` off it, and so on), the implementation
   prompt ([`prompts/implement.md`](prompts/implement.md)) with the item's JSON and a pointer
   to its section of the report, the agent's commits, then `./gradlew :shared:desktopTest
   :composeApp:desktopTest` run by the script. A failure goes back to the same session once,
   with the output; a second failure keeps the branch local and moves on. On green the branch is
   pushed and `gh pr create` opens the pull request against the previous one, the agent's
   description plus a footer with the scores, the `why`, the evidence links, and the cost.
5. **Bookkeeping on the report branch**, after every item: the ledger row and the item's new
   status (`in-review` with its pull request, or `blocked` with a note), committed and pushed at
   once so a round that dies halfway has still recorded what it spent.

The agent sees what it built: `ScreenshotTest` renders every screen to
`composeApp/build/screenshots`, and the prompt tells it to look at the screens it touched and fix
what looks off before it finishes.

## What you approve

A round is a stack of pull requests. Merge the report first, then the items in order; as each
one merges and its branch is deleted, GitHub retargets the next one to `main`. Turn on
**Settings → General → Automatically delete head branches** for that to happen by itself, and
keep `main` protected as it is (a pull request and green CI). Nothing reaches `main` otherwise;
a merge is the approval.

Every item's description says why the agent chose it: who asked, the quotes and their links, the
scores, and what it cost. The report's description says what the round found, in five lines.

To decline an item: close its pull request. The ones stacked above it still carry its commits,
so either let a later round redo them (set the item's `status` back to `new` in `backlog.json`,
or to `rejected` to keep it out) or ask Claude to rebase the rest of the stack. Rejecting the
first item in a stack of five is the expensive case; the research step is asked to score honestly
so the order holds up.

A pull request that only touches `research/` skips CI's Gradle jobs (`tools/changes.py`) and
cuts no release branch (`tools/release.py`), so the report merges without a build and without a
TestFlight round. An item's pull request runs the full CI, Android and the iOS simulator build
included, before it can merge, and ships with the next cut like any other change.

## Where it runs: GitHub Actions

[`research.yml`](../.github/workflows/research.yml) runs the round on a GitHub-hosted runner
every Monday at 02:00 UTC, and on **Actions → Research round → Run workflow** by hand, with the
number of items and a research-or-not switch as inputs. The runner has four cores, 16 GB and a
JDK, which the Compose compile and the desktop UI tests want and the box that serves the API
doesn't have (2 GB, one core, no swap: see below), and the minutes are free on a public
repository. The Gradle cache the CI jobs already keep makes a round's builds warm.

Two repository secrets, under **Settings → Secrets and variables → Actions**:

- `ANTHROPIC_API_KEY`: a key from the Claude Console. It pays for every step; the ledger says
  how much.
- `RESEARCH_TOKEN`: a fine-grained personal access token for this repository with **Contents:
  read and write**, **Pull requests: read and write** and **Metadata: read**. It pushes the
  branches and opens the pull requests, so CI runs on them: a push made with the workflow's own
  token triggers no workflows. Without it the workflow falls back to `RELEASE_TOKEN` (the
  release workflows' token, the same shape), then to the default token.

Then run it once by hand with `tasks` set to `0` for a research-only round, and read the report
it opens. The agent's logs, its JSON results and the pull request bodies it drafted are the run's
`research-run` artifact; the run summary ends with the ledger's totals.

A change to the prompts or the script takes effect once it is on `main`: the workflow checks
`main` out.

## Or a box of your own

The same script runs from a systemd timer on any Linux box with at least 4 GB of memory, two
cores and 8 GB free: Claude Code wants 4 GB on its own, the Compose compile and the UI tests
want about as much again, and Gradle's caches take a few gigabytes. The Hetzner box that serves
`api.gains.gerra.sh` (2 GB, one core, no swap, 4 GB free) is under all three, so the workflow
above is the setup for this repository; the units in [`deploy/`](../deploy) are kept for a
bigger box. Once, as root:

```bash
# 1. The user and its home. Not a system user: the clone, Gradle's caches and Claude Code's
#    state live in the home directory, and a login shell makes the by-hand commands easier.
useradd --create-home --home-dir /opt/gains-research --shell /bin/bash gains-research

# 2. Tools the box may lack: a JDK, git, GitHub's CLI
#    (https://github.com/cli/cli/blob/trunk/docs/install_linux.md) and Claude Code, which
#    installs itself into ~/.local/bin for that user. The stable channel: the native install
#    updates itself in the background, and a server wants the release that is a week old.
apt-get install -y openjdk-17-jdk-headless git gh
sudo -u gains-research -H bash -lc 'curl -fsSL https://claude.ai/install.sh | bash -s stable && ~/.local/bin/claude --version'

# 3. The clone.
sudo -u gains-research -H git clone https://github.com/gerra/gains.git /opt/gains-research/gains

# 4. The two secrets, in a file only root and that user read: the same key and token as the
#    workflow's.
install -m 640 -o root -g gains-research /dev/null /opt/gains-research/env
printf 'ANTHROPIC_API_KEY=sk-ant-...\nGH_TOKEN=github_pat_...\n' > /opt/gains-research/env

# 5. git pushes through gh's credentials (GH_TOKEN), so no key file is on disk.
sudo -u gains-research -H bash -lc 'set -a; . /opt/gains-research/env; set +a; gh auth setup-git && gh auth status'

# 6. Gradle next to other services: a smaller heap than gradle.properties' 5 GB (set for the
#    iOS release link, which never runs here) and no daemon left behind.
sudo -u gains-research -H bash -lc 'mkdir -p ~/.gradle && printf "org.gradle.jvmargs=-Xmx3g\norg.gradle.daemon=false\n" > ~/.gradle/gradle.properties'

# 7. A dry run says what a round would do and checks the tools, without spending anything;
#    one tiny real call (cents) proves the key works and shows the JSON the ledger reads.
sudo -u gains-research -H bash -lc 'cd ~/gains && python3 tools/research_run.py run --dry-run'
sudo -u gains-research -H bash -lc 'set -a; . /opt/gains-research/env; set +a; claude -p "Reply with the word ok" --max-turns 1 --output-format json'

# 8. The units. The timer fires early Monday UTC; the service can be started by hand any time.
install -m 644 deploy/gains-research.service deploy/gains-research.timer /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now gains-research.timer
systemctl list-timers gains-research
```

Watch the first round: `systemctl start gains-research` and `journalctl -u gains-research -f`.
The agent's logs and drafts are under `/opt/gains-research/.gains-research/runs/<run>/`. The
clone updates itself (`git fetch origin main` and a branch off it), so the box never needs a
pull by hand. Run the workflow or the timer, not both: two rounds a week would pick the same
items.

## Time and money

Every step appends a row to [`ledger.csv`](ledger.csv): the run, the step (`research` or
`item`), the item, the model, the agent's turns and seconds (from Claude Code's JSON result,
`num_turns` and `duration_ms`), the wall seconds around it (Gradle included), the dollars
(`total_cost_usd`, what the API charged), the branch, the pull request and the outcome. The
report's and every item's pull request repeat their row in the description.

```bash
python3 tools/research_run.py totals     # per run and overall; the workflow writes it to the run summary
```

Caps, all in `tools/research_run.py` and overridable on the command line: $20 for the research
step, $25 per item, $150 for the round; 300 and 400 turns; 90 and 120 minutes. The agent stops
itself at the dollar cap (`--max-budget-usd`), and the script doesn't start the next item once
the round's total is reached. What a round actually costs depends on how much the agent reads
and builds; the first rounds will say. The research step also pays per web search on top of the
tokens (see Anthropic's pricing for the web search tool). The runner's minutes are free on a
public repository; a job stops at six hours, and the ledger is committed after every item.

## Cadence, and running it by hand

The workflow's `cron: "0 2 * * 1"` is once a week, before the day's first release branch is cut
at 08:00 UTC, so the pull requests are open before the cut and never race it. `"0 2 * * 1,4"`
is twice a week. The timer's `OnCalendar` is the same line in systemd's spelling.

By hand, from **Run workflow** (the inputs are `tasks` and `skip_research`), or in a clone:

```bash
python3 tools/research_run.py run                    # the whole round
python3 tools/research_run.py run --tasks 0          # research only: a report and a backlog, no items
python3 tools/research_run.py run --skip-research    # five items from the backlog as it stands
python3 tools/research_run.py run --tasks 2 --task-budget 10
python3 tools/research_run.py next                   # what the next round would take, and why
python3 tools/research_run.py run --dry-run          # the plan and the commands, nothing run
```

To retry an item the round marked `blocked`, set its `status` back to `new` in `backlog.json`
(on `main`, through a pull request like anything else) and the next round takes it; its note
says what went wrong, and the run's artifact says more. To hand an item to a person, set it to
`rejected` or leave it at complexity 4 or 5: the round never takes those.

## The files

- [`backlog.json`](backlog.json): `{"items": [...]}`, every wish ever found. The research agent
  appends and re-scores; the script sets statuses. Fields, all required:
  - `id`: `r001`, `r002`, … in the order found.
  - `title`: the feature in the app's words, as a pull request subject could read.
  - `why`: two to four sentences: who asked, what they get, why now, what the leaders do.
  - `area`: the screen or module (`workout editor`, `home`, `programs`, `charts`, `settings`…).
  - `priority`: 1 to 5, demand × fit with Gains × gain in feel.
  - `complexity`: 1 to 5. 1 is one screen, 3 is a feature across screens with tests, 4 needs a
    schema or sync change, 5 is native platform work. Rounds take 3 and below.
  - `status`: `new`, `in-review` (a pull request is open), `done`, `rejected`, `blocked` (a
    round tried and couldn't; see `note`).
  - `found_in_run`: the run id that added it.
  - `evidence`: a list of `{"url", "quote", "source"}`, at least one for a new item.
  - `pull_request` and `note`: set by the script.
- [`sources.json`](sources.json): `{"sources": [{"url", "read_on", "run_id", "what",
  "takeaway"}]}`, every URL a round read. A round skips what was read in the last 90 days.
- [`competitors.md`](competitors.md): the feature matrix, one row per capability, one column
  per app.
- [`findings/`](findings): one report per round, named after the run.
- [`ledger.csv`](ledger.csv): the time and the money.
- [`prompts/`](prompts): what the agent is told in each step. Change these to change what a
  round looks for or how it builds; `tools/test_research_run.py` checks that the placeholders
  still match the script.

## Permissions

The agent runs with `--permission-mode bypassPermissions`: Gradle, git, the web and the file
system without a prompt, which is what unattended means. Two things bound it. The script passes
`--disallowedTools "Bash(git push:*),Bash(gh:*)"`, so the agent can't reach GitHub at all; the
script pushes, and only ever a `research/<run>/…` branch. And the machine is throwaway or
sandboxed: a GitHub-hosted runner is discarded after the job, and the systemd unit runs it as
`gains-research` with the box read-only outside `/opt/gains-research`, no capabilities, no
devices. The two secrets it holds are an API key the Console can revoke and a token scoped to
this one repository's contents and pull requests.

Where that isn't enough, `GAINS_RESEARCH_PERMISSION_MODE=dontAsk` makes Claude Code deny
anything not on an allow list, and the clone's `.claude/settings.json` `permissions.allow` is
where the list goes (`Bash(./gradlew:*)`, `Bash(git:*)`, `Edit`, `Write`, `WebSearch`,
`WebFetch`, …). Denied calls are counted in the log; expect to widen the list over the first
rounds.

## Why this shape, and the alternatives

Claude Code headless is the one option that gives all four things the round needs at once: the
built-in web tools for the research, the file and Bash tools for Gradle and git, a JSON result
with the cost and the time of every step, and a machine with a JDK where the app's own tests can
run before anything is pushed. The orchestration is a few hundred lines of the same Python the
release and deploy scripts are written in, tested the same way, and runs the same on a runner
and on a box.

Hosted by Anthropic instead: Claude Code on the web has Routines (scheduled, against a Claude
subscription, not an API key), and the Claude API has Managed Agents with scheduled deployments
(against the API key, in Anthropic's own sandbox). Both run the loop for you; both would need
the Gradle checks moved to CI only, since their sandboxes have no JDK or Android SDK of this
project's, and neither reports a per-step cost the way the JSON result does.

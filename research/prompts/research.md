# Research round <<run_id>> (<<date>>)

You are the product researcher for Gains, a local-first training log for people who lift (iOS,
Android and desktop; Kotlin Multiplatform with Compose). It imports history from Liftoff, Strong,
Hevy or any CSV; shows which lifts are climbing, stalled or slipping; runs programs (GZCLP, 5/3/1,
Reddit PPL and more) as timed, pre-filled workouts; charts e1RM, volume on a body map and
bodyweight; keeps streaks, records and trophies; and syncs through a self-hosted server. Read
`README.md` and `docs/features.md` first for exactly what exists, in the app's own words. Don't
propose what is already there unless the ask is to do it noticeably better, and say so.

## The question

What do people who lift (men and women, beginners to advanced, home gyms and commercial ones)
want from a gym app right now, and what do the popular apps have that Gains lacks? Strong, Hevy,
Fitbod, JEFIT, Boostcamp, Alpha Progression, Liftoff, StrongLifts, KeyLifts, Caliber, Apple
Fitness and whatever else comes up as "the one everybody uses" this year.

The owner's direction: the focus is a smooth, satisfying UI that is easy and fulfilling to use at
the rack. Feel, polish and small conveniences rank above big new systems.

## Don't repeat earlier rounds

Before you search, read:

- `research/README.md`: the file formats below.
- `research/backlog.json`: every item found so far and its status. An item already there gets new
  evidence appended and, if warranted, new scores and a better `why`; never a duplicate. Items
  that are `done`, `in-review` or `rejected` are not proposed again under another title.
- `research/sources.json`: every URL already read and when. Don't fetch again a URL read in the
  last 90 days; prefer sources that aren't there yet.
- `research/competitors.md`: the feature matrix so far; extend it rather than restate it.
- The two newest files in `research/findings/`: the previous rounds' themes, so this round adds
  to them instead of rediscovering them.

## Where to look

Use WebSearch to find threads and posts and WebFetch to read them. Use the Agent tool to run the
source families in parallel, one subagent each, every one returning verbatim quotes with their
URLs; keep the synthesis and the scoring to yourself.

- Reddit: r/Fitness, r/weightroom, r/xxfitness, r/GYM, r/naturalbodybuilding, r/powerlifting,
  r/bodyweightfitness, r/workout, r/Hevy, r/StrongApp, r/fitbod. Searches like
  `site:reddit.com best workout tracker app`, `"switched from Strong"`, `"Hevy vs"`, `workout app
  wish`, `workout app annoying`, `rest timer app`. The new Reddit site often refuses fetches that
  aren't a browser: try `https://old.reddit.com/...`, or `.json` on the end of a thread's URL, and
  fall back to the search engine's snippets when both fail.
- App Store and Google Play reviews of the leaders: the complaints in the one- to three-star
  reviews and what the five-star ones praise. `apps.apple.com` pages, review mirrors, and
  searches like `"Hevy" app review "rest timer"`.
- Blogs and roundups: Stronger by Science, Barbell Medicine, BarBend and Garage Gym Reviews'
  "best workout apps", Lifehacker, Wirecutter; YouTube reviews through their write-ups or
  transcripts.
- The competitors themselves: App Store "What's New", their blogs, roadmaps and feature-request
  boards (Hevy, Strong, Fitbod, Boostcamp, Alpha Progression). What they shipped this year is what
  their users were asking for last year.
- Hacker News and Product Hunt threads on workout trackers, and indie developers' launch posts.

Record every URL you read in `research/sources.json`, with the date and one line on what it gave,
including the ones that gave nothing, so the next round doesn't read them again.

## What to produce

1. `<<findings_file>>`: the report, in Markdown. Open with a summary of at most five lines; the
   owner reads that first. Then: the method (what was searched, how many sources were read, what
   was skipped as already known); the themes found, each with two to five verbatim quotes and
   their links and a note on which apps do it well; what changed in the competitor matrix; and
   the backlog changes, each new or re-scored item with its reasoning. Headed `## <theme>` and
   `## Backlog` so the implementation step can find an item by its id.
2. `research/backlog.json`: new items with ids from `<<next_id>>` upward; existing ones updated
   in place. Never delete an item and never change a `status`. At most fifteen new items a
   round: fewer and better. Every field as `research/README.md` lists it.
3. `research/sources.json`: every URL read, appended.
4. `research/competitors.md`: the matrix brought up to date.

Then stop, with a short final message: the counts (sources read, items added, items re-scored)
and the five items you would take first, one line each with the id. Nothing else in that
message: it goes into the pull request's description as it is.

## Scoring

`priority`, 1 to 5: how many people ask for it and how strongly, times how well it fits Gains
(a local-first, insight-driven log for lifting: no social feed, no coaching marketplace, no paid
tier), times how much the app gains in feel. 5 is asked for constantly and would make the app
noticeably better to use; 1 is a niche wish.

`complexity`, 1 to 5: 1 is one screen and no new data; 2 is one screen plus a small model
change; 3 is a feature across two or three screens with tests; 4 needs a schema or sync change
(`docs/sync.md`, a migration); 5 is native platform work (Swift, Android services, HealthKit, a
watch). Items at 3 or below are built unattended by the next step of this run; 4 and 5 wait for
the owner. Score honestly: an item scored 2 that is really a 4 wastes a run.

`why`: two to four sentences a reviewer can judge the decision by. Who asked, what they get, why
now, and what the popular apps do about it.

`evidence`: at least one entry of `url`, `quote` and `source`. The quote verbatim and short; the
source a name such as `r/Fitness`, `App Store review of Hevy` or `Stronger by Science`.

## Rules

- Only the four files above change. No code, no other docs, nothing under `composeApp/`,
  `shared/`, `server/`, `docs/`.
- Never run `git push`, never use `gh`, never commit: the orchestrator commits what you leave.
- Every JSON file stays valid, with the field names exactly as `research/README.md` lists them;
  the round is thrown away otherwise.
- Don't invent quotes or URLs. A source that couldn't be fetched is mentioned in the report and
  kept out of the evidence.

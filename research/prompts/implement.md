# Build one research item (run <<run_id>>)

You are on branch `<<branch>>` of Gains, a local-first training log for people who lift (Kotlin
Multiplatform, Compose Multiplatform; iOS, Android and desktop). Implement this one item from the
research backlog, completely and small, as the owner would in a single pull request:

```json
<<item_json>>
```

The reasoning behind it, with quotes from lifters, is in `<<findings_file>>` (search for the
item's id) and behind the item's evidence links. Read them before deciding how it should feel:
the item is a wish from the rack, not a spec.

## Before you write code

- Read `README.md`, `docs/features.md` (what exists, and the words the app uses for it),
  `docs/how-it-works.md` (the architecture: ScreenModels, repositories, Koin) and the screens
  you'll touch under `composeApp/src/commonMain/kotlin/app/gains/ui/`.
- Read `composeApp/src/commonMain/kotlin/app/gains/ui/theme/Motion.kt` and `Theme.kt`: every
  animation and colour in the app comes from there, and so does yours.
- Find a comparable existing feature and the test next to it (`composeApp/src/desktopTest`,
  `shared/src/commonTest`) and match how both are built.

## The bar

The owner's direction: a smooth, satisfying UI that is easy and fulfilling to use mid-workout.

- Motion says what just changed and comes from `Motion.kt`; nothing loops, nothing decorates.
- Thumb-reachable on a phone, readable at arm's length, no truncated text in either language.
- Light and dark, English and Russian: every new string goes in both
  `composeApp/src/commonMain/composeResources/values/strings.xml` and `values-ru/strings.xml`
  (`LocalizationResourcesTest` fails otherwise).
- Look at what you made. `./gradlew :composeApp:desktopTest --tests app.gains.ScreenshotTest
  -Pgains.android=false` writes every screen to `composeApp/build/screenshots/`; open the PNGs
  of the screens you touched with the Read tool and fix what looks off. If your screen isn't
  captured, add it to `ScreenshotTest`.

## Conventions

From `docs/auth-plan.md` ("Checks every item must pass") and `docs/development.md`:

- `shared/` stays pure Kotlin, the UI lives in `composeApp/`, wiring goes through Koin
  (`shared/.../di/SharedModule.kt`). No new frameworks or dependencies.
- KDoc on public classes and functions says *why*, in the voice of the code around it.
- Keep iOS work in Kotlin (`iosMain`). Any Swift, `project.pbxproj`, `.plist` or `.entitlements`
  change, and anything under `androidMain`, is listed in the pull request description for the
  owner to check on a device.
- A schema change needs a `migrations/N.sqm` and, if the table syncs, the triggers and the
  document class (`docs/development.md`, "Changing the schema"). If the item needs one and it
  isn't small, build the slice that doesn't (see Scope) rather than the migration.
- `docs/features.md` describes every user-visible feature: update it in the same change.
  `docs/how-it-works.md` if the architecture moved, `docs/sync.md` if sync did.
- A test for the behaviour: a desktop UI test or a shared test, in the style next to it.

## Checks

Run `<<verify_command>>` and make it pass. The orchestrator runs it again before pushing; if
it fails there you get the output back once. Run `:server:test` too if you touched `server/` or
`protocol/`. CI also compiles the iOS klibs and builds Android, so `commonMain` stays portable:
no `java.*`, no `String.format`, nothing JVM-only.

## Deliverables

1. Commits on this branch, in the repository's style: a subject such as `Rest timer: starts
   itself when a set is ticked` (the area, then what it does now), and a body that says why,
   wrapped at 100 columns. One commit is usual; more only when they are separately reviewable.
   `git add` with paths, not `-A`, so nothing stray goes in.
2. `<<pr_body_path>>`: the pull request description, in Markdown, for a reviewer who hasn't
   seen the research. Sections: **Why** (who asked for it and what they get, two short
   paragraphs); **What changed** (by screen or module); **How it looks** (the screens touched,
   with the screenshot file names); **Checks run** (the exact commands and their result); **For
   the owner to try** (the manual steps, and every iOS, Android or Xcode file touched). The
   orchestrator appends the research scores, the evidence links and the cost; don't repeat them.

## Scope

Only this item, and all of it: a small, polished, complete change beats a broad half-done one.
If the item turns out larger than its complexity says, or needs a schema, sync or native change
that can't be done safely here, build the largest self-contained slice that is still useful and
say in the description what is left and why. If nothing sensible can be built, change nothing,
commit nothing, and write why in `<<pr_body_path>>`; the orchestrator records it.

## Never

- `git push`, `gh`, switching branches, rebasing, or amending a commit that isn't yours from
  this session.
- Anything under `research/`: that directory is the orchestrator's.
- Skipping, weakening or deleting a test to get green.

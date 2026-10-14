# Gains

A Kotlin Multiplatform training log: `shared/` (everything below the UI), `composeApp/` (the Compose
UI for iOS, Android and desktop), `server/` (sync). Commands, releases and recipes are in
[docs/development.md](docs/development.md).

## Before touching the UI

Read [docs/design.md](docs/design.md): the colours and what each means, the type scale, spacing,
which shared component to use for what, tap targets, motion, copy and the mark. Follow it rather
than inferring the style from nearby code.

When a change alters the look (a colour, a size, a component's role, a new rule), update
docs/design.md in the same pull request and add a line to its log. `DesignDocTest` fails when the
doc's colours, type scale, button heights or card paddings stop matching the code.

Check UI work with `./gradlew :composeApp:desktopTest -Pgains.android=false`, then look at the
screens it writes to `composeApp/build/screenshots`, in both themes and, for new words, in Russian.

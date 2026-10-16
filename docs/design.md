# Design

The rules the app's UI follows, so a change can be made without reverse-engineering the style
from the code. The code is the source of truth for values; this file says what they are, what
each one is for and what not to do. `DesignDocTest` checks that the colours, the type scale, the
button heights and the card paddings written here match `Theme.kt` and `Components.kt`.

**When you change the look, change this file in the same pull request**, and add a line to the
[log](#log) at the end saying what changed and why.

Where it lives:

| What | Where |
|---|---|
| Colours, type scale, shapes | `composeApp/src/commonMain/kotlin/app/gains/ui/theme/Theme.kt` |
| Motion timings | `ui/theme/Motion.kt` |
| Shared components | `ui/components/Components.kt` (cards, buttons, pills, tags, tiles, chips), `Streak.kt`, `Badges.kt`, `Pickers.kt` |
| Top bar, tabs, Start bar, workout bar | `ui/nav/AppChrome.kt`, laid out in `ui/nav/AppFrame.kt` |
| The mark | `ui/components/Logo.kt`, plus the icon files listed under [The mark](#the-mark) |
| Fonts | `composeApp/src/commonMain/composeResources/font/`, licences in `files/licenses/` |

## The idea

Chalk and iron: a gym floor, not a neon dashboard. Flat graphite surfaces, chalk-white type, and
colour only where it means something. Two things the app was told by testers and must not drift
back to: it was hard to read (tiny bold capitals, low-contrast grey, colour everywhere), and its
buttons were huge.

1. **Readable first.** Body text is 15 sp regular in a high-contrast colour. Nothing smaller
   than 13 sp, no all-caps labels.
2. **One loud thing per screen.** The main action, or the one number that matters (the streak,
   a workout's figures). Everything around it stays quiet.
3. **Colour is a signal.** Green is progress, red is a slip, yellow a stall, blue consistency.
   A fact about something (its level, how many days a week, how it is logged) is never coloured.
4. **Actions where the thumb is.** The main action of a screen sits in the bottom third.
5. **Same thing, same look.** Use the shared components; don't style a one-off.

## Colour

Two themes, dark first. The main action is **chalk on the dark floor and ink on the light one**:
the loudest thing on a screen is the thing to do, and the accents stay free to mean something.

Base (Material colour scheme):

| Role | Dark | Light | Use |
|---|---|---|---|
| `background` / `surface` | `#121416` | `#F1F2F4` | The floor |
| `surfaceContainer` | `#1B1E22` | `#FFFFFF` | Cards, the tab bar |
| `surfaceContainerLow` / `Lowest` | `#16191C` / `#0D0F10` | `#F8F9FA` / `#FFFFFF` | Material's own defaults (bottom sheets); not used directly |
| `surfaceContainerHigh` | `#22262B` | `#F4F5F7` | Chip rows, icon buttons |
| `surfaceContainerHighest` | `#2B3036` | `#E9EBEE` | Secondary buttons, tags, the tab and chip indicators, empty meter tracks |
| `onSurface` | `#F2F0EB` | `#15171A` | Headings and body text |
| `onSurfaceVariant` | `#A7ACB3` | `#5A616B` | Secondary text, labels, captions |
| `primary` / `onPrimary` | `#F2F0EB` / `#121416` | `#15171A` / `#FFFFFF` | The main button, the chosen chip, the workout bar |
| `outline` / `outlineVariant` | `#3A4047` / `#282C32` | `#D0D4DA` / `#E2E5E9` | Dividers, field borders |

Accents (`GainsColors.palette`), taken from the colours of competition plates:

| Name in code | Means | Dark | Light |
|---|---|---|---|
| `volt` (`progress`) | Progress, the brand, links, "done" | `#62D99A` | `#17924F` |
| `coral` (`regression`) | A slip; destructive actions | `#FF7366` | `#D4402F` |
| `amber` (`stall`) | A stall; a streak at risk | `#F2C14E` | `#B0700F` |
| `cyan` (`consistency`) | Consistency; rest | `#6CB2FF` | `#1F6CC4` |
| `orchid` (`neglect`) | A neglected muscle | `#D291F0` | `#9441C4` |
| `violet` | A secondary series in charts, muscle-group colouring; no meaning of its own | `#A99BF7` | `#6650D6` |

Rules:

- Roughly 60 / 30 / 10: the floor and cards, the text, then accents. If a screen looks colourful,
  something is coloured that shouldn't be.
- An accent is used through its meaning (`palette.progress`, `palette.regression`), and the same
  meaning always gets the same colour.
- Red only for something that went wrong or can't be undone (delete, a regression).
- Secondary text is `onSurfaceVariant`, never a hand-picked grey or the body colour at reduced alpha.
- Tints of an accent (a pill's background) are the accent at 14 % alpha.
- No gradients for decoration and no glows. The two gradients in the app are faint and mean
  something: the streak card's green wash (`heroBrush`, `#1C2622` into the card colour on dark,
  `#E7F5EC` into white on light), and the welcome screen's single still wash of `volt`.

## Type

Two families, bundled (not the system font, which rendered differently on each platform):

- **Onest**, regular (400) and semibold (600), for every word. It is drawn for Cyrillic as well
  as Latin, so Russian reads as well as English.
- **Oswald**, semibold (600), for big figures and screen titles. Condensed, like a scoreboard, so
  "1 h 23 min" fits a third of a phone without shrinking. Its figures are tabular (`tnum`), so a
  clock or a count does not shift as its digits change.

Four sizes and one hero numeral (`TypeScale`). Every Material role maps onto one of them:

| Size | Font | Material roles | Use |
|---|---|---|---|
| 13 sp | Onest regular | `bodySmall`, `labelMedium`, `labelSmall` | Captions, labels over a value, tags and pills, tab labels |
| 15 sp | Onest regular / semibold | `bodyMedium`, `bodyLarge`; `titleSmall`, `labelLarge` semibold | Body text; buttons; section headers |
| 17 sp | Onest semibold | `titleMedium`, `titleLarge`, `headlineSmall`, `headlineMedium` | Card titles, dialog titles, list item names |
| 32 sp | Oswald | `headlineLarge`, `displaySmall`, `displayMedium` | Screen titles, figures in tiles, the workout clock |
| 52 sp | Oswald | `displayLarge` | The hero number: the streak, the welcome headline |

Rules:

- Use the typography roles; never set `fontSize` or a weight other than SemiBold by hand. The
  exceptions: charts' axis labels (`TypeScale.CAPTION`), the wordmark, and auto-sizing that steps a
  label down only when a translation doesn't fit (button labels, tab labels, figures in tiles).
- Sentence case everywhere. No `.uppercase()`, no letter-spacing, no strings written in capitals.
- Hierarchy from size, weight and colour (`onSurface` against `onSurfaceVariant`), in that order.
- A value is bigger than its label: "26" in 32 sp over "This week" in 13 sp, never the reverse.
- Button labels stay on one line. If a translation doesn't fit, shorten the words; the label may
  step down to 80 % of its size before it ellipsizes, and that is a last resort.

## Space and shape

- **8-point grid.** Every padding, gap and spacer is a multiple of 4, and mostly of 8: 4, 8, 12,
  16, 20, 24, 32. Not 6, 10, 14 or 18.
- **Screen gutter** 20 dp for lists, 16 dp for the bars along the bottom.
- **Cards** pad 20 (`Dp16.Normal`), 16 when dense (`Dp16.Tight`), 24 for a hero (`Dp16.Loose`).
  12 between cards.
- **Groups.** Things that belong together sit 4 to 12 apart; a new section starts 32 below the
  last and its content follows 12 below its header (`SectionHeader` does this). The gap between
  groups is more than twice the gap inside one.
- **Corners** (`MaterialTheme.shapes`): 6 small marks, 10 compact buttons and inputs, 14 buttons,
  18 cards, 26 sheets. Pills and tags are fully round or 8.

## Components

Reach for these before writing a new one.

| Need | Use | Not |
|---|---|---|
| The screen's main action | `PrimaryButton` (48 dp, chalk / ink). One per screen. | A coloured button |
| Another action beside it | `SecondaryButton` | A second `PrimaryButton` |
| An action inside a card | `PrimaryButton` / `SecondaryButton` with `compact = true` (44 dp), sized to its label | A full-width button in every card |
| A link or a minor action ("+ Log", "View all ›") | `TextButton` with green text | A button |
| A card | `GainsCard` (flat, `surfaceDepth`, press scale) | A custom `Box` with a background |
| A screen title | `ScreenTitle` | |
| A section | `SectionHeader` (sentence case, its own spacing) | A capitalised label |
| A fact (level, days a week, modality) | `Tag` (neutral) | `Pill` |
| A signal (Active, Best match, a record, a status) | `Pill` in the accent that means it; `filled` only for the one that matters most | |
| A change in % | `DeltaBadge` | |
| A number with its label | `MetricTile` | |
| One choice of a few | `ChipRow` (the pill slides) | Radio buttons |
| How far back a chart reaches | `ChipRow` of windows: 1M · 3M · 1Y · All for bodyweight (in the Trend header), 3M · 6M · 1Y · All for a lift | A date-range picker |
| An answer that toggles, with a line on what it means | `SelectTile` (ring and tick, one to a row; the "nothing" choice first and preselected) | Checkboxes |
| What a built-in program is fitted to, and the way to change it | `PlanFitLine` ("Focus: …" over "Adjusted for: …", Change) | |
| A long list most people need little of (the built-in programs) | The best two first, the rest folded under one row ("More programs (6)" with who they are for and a chevron) | Every option at once |
| A tap refused (a third focus area) | `CapNote`, pinned above the bottom buttons where the eye is | A note in the scroll that may be off screen |
| Nothing to show | `EmptyState` with an action | A blank screen |
| A key / value line | `KeyValueRow` | |
| A figure that changes | `RollingText` | A plain `Text` |

The bars along the bottom, above the tabs: `StartBar` on Home when a program has a next day,
`LiveSessionBar` while a workout runs (it replaces the Start bar), and the workout screen's Ready
card and clock, pinned under its list. The Ready card and the clock are one row, 12 above and below
a compact button: the clock's figures sit under their labels in a box the height of their digits,
skipping a rest is an × and the rest's bar runs along the card's foot.

## Interaction

- **Thumb zone.** The main action of a screen is in its bottom third: onboarding's Next, the
  import's Import, a workout's Start, Skip and End, Home's Start bar. The top bar is for
  navigation and the "+" menu.
- **Tap targets** are at least 44 pt: buttons 48 (compact 44), top-bar icons 44, chips 44. A
  tappable `Pill` takes a 48 dp target around its small label that overhangs into the space
  around it rather than taking room in the layout, so a row of them sits 12 below the text above.
- **Depth** (`Modifier.surfaceDepth`): on the light theme a soft shadow tinted with the ink colour
  (never plain grey); on the dark one, where a shadow can't show, a chalk edge at 5 %. Filled
  buttons carry a faint inner highlight on their top half.
- **Motion** says what just changed and is never decoration (`Motion.kt` has the timings): a press
  scales a card to 98.5 %, the tab and chip pill slide, a "done" moment pops (a set ticked, a
  workout logged, an answer picked), charts and meters draw in. Nothing loops except the welcome
  card's trend, the at-risk ring and the exercise demos. Everything is a cut under reduced motion.
- **Home's fold.** On a phone-sized window (the screenshot test's 480 × 860 dp) Home shows the
  start of "What's moving" without scrolling. If a change pushes it down, move something below
  it rather than squeezing the type.

## Words

- Sentence case; plain verbs; say what happens: "No program for now", not "Submit".
- An action keeps its name through the flow: the button that says "Start B1" opens B1.
- A chevron (›) only on text links that go somewhere, never on a button.
- Every string is in both `values/strings.xml` and `values-ru/strings.xml`. Russian runs about a
  third longer: check a new label in Russian at 390 dp wide before calling it done.

## The mark

The end of a loaded barbell seen from the side: the sleeve, three plates stepping up towards the
middle of the bar, and the bar running on. Plates going up is progressive overload, which is what
"gains" means; the biggest plate is green. Flat, three colours on a graphite tile, legible at 16 px,
no text.

| Part | Colour |
|---|---|
| Tile | `#1B1E22` |
| Bar | `#8E949C` |
| Small plates | `#F2F0EB` |
| Big plate | `#62D99A` |

The geometry is three SVG paths on a 100-unit tile, kept identical in every copy:
`ui/components/Logo.kt` (`GainsLogo`), `androidMain/res/drawable/ic_launcher_foreground.xml`,
`ic_launcher_monochrome.xml` and `ic_notification.xml` (scaled), `values/colors.xml`
(`ic_launcher_background`), `iosApp/.../AppIcon.appiconset/AppIcon.png` (1024, square, no
transparency), `site/img/logo.png`, `site/img/favicon-32.png` and `site/apple-touch-icon.png`.
Changing the mark means changing all of them.

## Checking a change

1. `./gradlew :composeApp:desktopTest -Pgains.android=false`: the UI tests, including
   `ScreenshotTest`, which walks the app and writes every screen to `composeApp/build/screenshots`,
   and `DesignDocTest`, which checks this file against the theme.
2. Look at the screenshots, dark and light (`10-home-light`, `11-lift-detail-light`). A tap the
   test makes on something below the fold misses: scroll to it or click it through its semantics.
3. For anything with new words, look at it in Russian.
4. `python3 tools/store_screenshots.py` remakes the store and site pictures from the screenshots;
   the Screenshots workflow does both after a merge.

## The website

[gains.gerra.sh](https://gains.gerra.sh) (`site/`, plain HTML and one `site/style.css`) follows
the same rules, translated to the web:

- **Colour.** The CSS variables at the top of `style.css` are Theme.kt's colours under the same
  roles: `--bg`, `--card`, `--raised` (`surfaceContainerHighest`), `--text`, `--muted`,
  `--primary` / `--on-primary`, `--outline` for fields, `--divider` for rules, and the accents by
  name. Change a colour in Theme.kt and change it here.
- **Type.** The app's fonts, served from `site/fonts/` as WOFF2 cut to Latin, with their licenses
  beside them. Body text is 17 px (a web page is read at arm's length), secondary text, buttons
  and the nav 15, captions 13; section titles are Oswald 32 and a page's headline Oswald 52.
  Weights 400 and 600, sentence case; the wordmark is the one word in capitals.
- **Buttons** are the app's: 48 px high, 14 px corners, a 15 px semibold label on one line.
  `.button.primary` is chalk on dark and ink on light with the faint top highlight; one per
  page section. `.button.secondary` is the raised fill. Not green, not a pill.
- **Cards** (`.surface`, `.card`, the feature list) are flat `--card` with 18 px corners, padded
  20, 24 for the guest list, 12 apart; on dark a 5 % chalk edge, on light an ink-tinted shadow.
  The hero card carries the welcome screen's still green wash; no other gradient.
- **Facts and signals.** "Training log · iPhone" is a `.tag` (neutral). Colour appears only as a
  `.pill` in the accent that means it, as on the Insights card; feature cards get no coloured dots.
- **Space.** Multiples of 4 and mostly 8; a 20 px gutter; links and nav items are 44 px targets.
- Check it by serving `site/` (`python3 -m http.server -d site`) and looking at the pages at
  390 px and on a wide window, in both colour schemes.

## Log

Newest first. One line per change to the rules, with the reason.

- **2026-10-16** A program workout says where it falls in the rotation under its day pill ("Week 2 of 4 · Cycle 1"),
  in the same words and colour as Home's next day: a tester mid-workout could not tell which cycle they were on.
- **2026-10-15** The workout clock is one row (about 68 dp, from about 96): each figure drawn in a box
  the height of its digits rather than the font's whole line, a compact End, an × to skip the rest
  and the rest bar along the card's foot. Stacked, it took a big slice of a phone above the tabs; the
  Ready card matches so Start changes over in place.
- **2026-10-15** The bodyweight trend takes a window (1M · 3M · 1Y · All) in its section header, and an
  overlaid lift starts at the first weigh-in shown: a squat history years older than the weigh-ins
  squeezed the bodyweight into the chart's right edge.
- **2026-10-15** Focus areas and what to adjust for: a tester who trains regularly but is new to the
  gym found the app hard and asked for "glutes, abs" and a health section. Added `SelectTile`,
  `PlanFitLine` and `CapNote`; sets are written out ("3 sets of 8–12", not "3 × 8-12"); built-in
  programs lead with a one-sentence `_short`; a program day says "about N min". Health words: "Adjusted
  for", never "careful with" a pregnancy; "often easier on", never "safe"; the not-medical-advice line
  wherever guidance is shown. One-to-a-row tiles, since two to a row wrapped at 360 dp in Russian.
- **2026-10-15** A tappable `Pill`'s 48 dp target overhangs instead of taking layout room: it was
  adding 11 dp above and below every pill, leaving the Insights cards' session links loose.
- **2026-10-14** The website follows the redesign: the app's fonts, chalk / ink buttons in place
  of green pills, flat cards with the app's depth, sentence-case labels instead of tracked
  capitals, signal pills instead of decorative coloured dots. Written down under [The website](#the-website).
- **2026-10-14** Applied the mobile-app-ui-design skill's rules: four type sizes and two weights,
  the 8-point grid, 44 pt targets, actions in the thumb zone (the workout's clock and Start at the
  bottom, Home's Start bar), tinted shadows on light. Home's level card moved below "What's moving"
  to keep it above the fold.
- **2026-10-14** Redesign: chalk and iron replaced the near-black-and-neon look after testers found
  it hard to read with huge buttons. Bundled Onest and Oswald, plate-colour accents with one
  meaning each, neutral tags, 48 dp buttons, the barbell mark replacing the glowing trend line.

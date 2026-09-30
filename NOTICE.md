# Notices

Copyright © 2026 German Berezhko

> This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
> of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.

That notice applies to every file in this repository written for Gains. It is given once, here and
in the [README](README.md), rather than in a header in each file, as Exhibit A of the license allows.
The license text is in [`LICENSE`](LICENSE).

Files written for Gains are licensed under MPL-2.0, except those listed below. Contributions are
accepted under MPL-2.0 too, the license they are contributed to; there is no CLA and no sign-off.

## Exceptions

These files are not Gains' own work, and MPL-2.0 doesn't change their license.

| Path | Author | License | Link | What the license needs |
|---|---|---|---|---|
| `composeApp/src/commonMain/kotlin/app/gains/ui/charts/BodyMapPaths.kt` | ELABBASSI Hicham (react-native-body-highlighter) | MIT | https://github.com/HichamELBSI/react-native-body-highlighter | The notice below in every copy. It is in the file's header, here, and in every build on the Open-source licenses screen. The file is generated from the upstream path data (`assets/bodyFront.ts`, `assets/bodyBack.ts`, `components/SvgMaleWrapper.tsx`) and stays under MIT as a whole. |
| `composeApp/src/commonMain/composeResources/files/exercises/` (the start and end drawings) | Everkinetic (everkinetic.com, Greg Priday) | CC BY-SA 4.0 | https://github.com/everkinetic/data | The notice below wherever the drawings go: credit, the license and its link, and that they were changed. Everkinetic's drawings are cropped, aligned, scaled and made transparent by `tools/exercise_demos.py`; the adapted files stay under CC BY-SA 4.0, and so does any further change to them. The caption under the drawings in the app names Everkinetic and the license, and the Open-source licenses screen carries the notice in every build. Share-alike covers the drawings only, not the code that shows them. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` | The Gradle authors | Apache-2.0 | https://github.com/gradle/gradle | Keep the headers the two scripts carry. Build tooling: never part of the app. |
| `shared/src/desktopTest/kotlin/app/gains/auth/GoogleOAuthTest.kt`, the PKCE example values only | IETF Trust (RFC 7636, Appendix B) | The IETF Trust Legal Provisions | https://www.rfc-editor.org/rfc/rfc7636#appendix-B | Nothing beyond the reference: they are the RFC's published test vector, used in a test and never shipped. |

The body drawing's notice, word for word:

> MIT License
>
> Copyright (c) 2022 ELABBASSI Hicham
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

The exercise drawings' notice, word for word:

> Exercise drawings by Everkinetic (https://github.com/everkinetic/data), from everkinetic.com by
> Greg Priday, licensed under the Creative Commons Attribution-ShareAlike 4.0 International
> License (CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/). Gains adapted them:
> each pair is cropped and aligned together, scaled, and its white made transparent so the app can
> draw the lines in its own colours. The adapted drawings are licensed under CC BY-SA 4.0 too.
> They are provided as is, without warranties.

## Credits that owe nothing

- **free-exercise-db** (https://github.com/yuhonas/free-exercise-db, Unlicense, public domain):
  some of the built-in exercises' names and muscle groups in
  `shared/src/commonMain/kotlin/app/gains/catalogue/ExerciseCatalogue.kt` come from its JSON data.
  Its **photos are not in Gains** (the drawings under "How to do it" are Everkinetic's, above).
  Until September 2026 the app bundled 548 of them; the project free-exercise-db took them from
  (wrkout/exercises.json) says they were scraped off the internet and that its author doesn't own
  their copyright, so no license covers them and they were removed before the license was applied.
  They remain in this repository's history, as the upstream repository still hosts them.
- **Program names** in `shared/src/commonMain/kotlin/app/gains/catalogue/ProgramCatalogue.kt`
  (GZCLP, 5/3/1 for Beginners, Reddit PPL and the others) name their authors' programs. The
  descriptions, days and progression rules there are written for Gains.
- **Libraries.** The apps are built with open-source libraries, each under its own license
  (Apache-2.0, MIT, and Google's Android SDK License for Play services on Android). They are
  dependencies, not files in this repository; every build lists the ones it contains, with their
  licenses and the license texts, on its Open-source licenses screen (Settings → About).

## The name and the logo

MPL-2.0 grants no rights in a contributor's trademarks, service marks or logos (section 2.3). The
files that draw the Gains mark (`ui/components/Logo.kt`, the Android vector drawables, the app
icons and the logo images in `docs/` and `site/`) are under MPL-2.0 as far as copyright goes, so a
fork builds without replacing them. The name "Gains" and the mark are not licensed for use as a
name or mark: a build distributed from a fork takes a name and an icon of its own, and doesn't
suggest that it is Gains or is endorsed by it. Saying that a fork is based on Gains is fine. This
paragraph describes how the name may be used; it claims no registration.

## Personal data

The repository holds no one's real training. The exports and screens it does hold are made up:
`samples/liftoff-export.csv` is a generated eight-month export, the test fixtures are written by
hand, and the screenshots in `docs/` and the pictures on the site are the app rendered from that
sample export by the Screenshots workflow. The owner's own Liftoff export, and test rows copied
from it, were in the tree until September 2026; they were removed before the license was applied,
and remain in the history.

## Who wrote it

Every commit is by the owner, by Claude in the owner's own sessions, by `github-actions[bot]`
(screenshots and releases) or by `dependabot[bot]` (version bumps). No one else has contributed.

MPL-2.0 doesn't change the license of any third-party work in this repository.

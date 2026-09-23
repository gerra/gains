# Features

The long version of the list in the [README](../README.md).

- **A goal, then a program.** Three questions after sign-in (build muscle, get stronger, lose
  fat or general fitness; experience; days a week) rank the built-in programs by fit. Skip them
  and set the goal later in Settings. The goal also decides which insights lead on Home.
- **Programs with choosable days.** The r/Fitness Basic Beginner Routine, GZCLP, 5/3/1 for
  Beginners, Reddit PPL (6-day and 3-day), an Upper/Lower split and the r/bodyweightfitness
  Recommended Routine ship built in, each as named days of exercises with sets, reps and a
  progression rule. Days rotate on completion, never by weekday; Home shows the next one with
  the rotation order, and the program page lays out a full cycle week by week, every day's
  exercises and how each lift progresses. Any day can be started with a tap. Duplicate a
  built-in to edit it, or build your own.
- **Pre-filled workouts.** Starting a day opens the editor with every set loaded from your last
  session of that exercise and a hint such as `Last: 60 kg × 5,5,5 → try 62.5 kg` from the
  program's rule (linear, double progression or the GZCLP stage ladder). The first session of a
  GZCLP tier is estimated rather than copied: the best recent free-session set gives an Epley
  1RM, T1 starts at 85% of it, T2 at 65% and T3 at about 52%, rounded down to the plates and
  never at a weight you already failed for the tier's reps (`Last: 50 kg × 6,8,9 → est. 1RM
  ~65 kg → T2 start 40 kg`). Once a tier has its own sessions, its ladder takes over.
  Whatever the workout, every set row carries a `PREV` column with the same-numbered set of the
  exercise's last session (`60×5`), so each set can be judged against last time while it is typed.
- **Warm-ups and rest.** Tiered days pre-fill warm-up sets (T1: empty bar, 40/60/80%; T2: bar
  and 60%; T3: one light set on cables and machines), labelled `W1, W2…` so the work sets stay
  1–5. They are stored as warm-ups and never count towards volume, records or progression, can
  be hidden or removed per exercise, and can be switched off in Settings along with the bar
  weight. Each tier shows its rest (T1 3–5 min, T2 2–3 min, T3 60–90 s).
- **Tick sets off as you go.** Every set row ends in a Liftoff-style check: tap it when the set is
  done to mark the row and start the rest timer, tap again to undo. An empty row can't be ticked.
  If you save with some work sets ticked and others left unticked, you're asked whether to leave
  the unticked ones out; a workout where nothing was ticked saves every set as before.
- **Timed sessions.** Opening a program day (or **Start workout** from the **+** menu) shows the
  plan ready to go, every set shown disabled; nothing runs and nothing can be changed until you
  press **Start**. From then the workout is against the clock: a bar pinned above the sets shows the total time and the rest left, and ticking a set
  restarts the countdown. The workout is written to the database as you go, so it survives the
  app being killed; leave it and a bar above the tabs shows it ticking with a tap to resume.
  **End session** asks about unticked sets the same way, stores the workout with the timed
  duration, and past three hours asks whether the timer was left running before it does.
  While it runs, the workout is a tap away from anywhere. On Android an ongoing notification in
  the tray shows the total time counting up and, during a rest, the countdown beside it with a
  **Skip rest** button; both clocks are kept by the system, so they run on while the app is
  asleep. On iOS a notification goes up in Notification Centre whenever the app is left, with the
  start time and how long the workout has run, and a rest becomes a scheduled **Rest over**
  notification that iOS delivers when the countdown ends. The desktop has a tray icon with the
  same **Skip rest** in its menu. Each is cleared when the session is ended or discarded.
  **Log past workout** still logs a session by date, time and duration.
- **A summary when you finish.** Ending a session opens what it came to: how long it took, on the
  wheels in case the clock was wrong; your last body weight filled in, ready to be recorded against
  the day; a caption with a photo beside it to remember it by; and then the muscle groups it trained,
  shaded onto the body with their set counts. What there is to fill in comes first, what the workout
  came to after. Everything there edits the workout already stored, so leaving at any point keeps it. The photo is shrunk to a long side of 1280 px and kept in the database beside the
  session, on the device like everything else; the caption and a photo marker show on the workout in
  History, and the summary reopens from the workout's editor.
- **Nothing to type but reps.** Weights are picked on a wheel (whole units and quarters, with
  plate-jump buttons for the usual step between sets), the date on a calendar, the time and the
  duration on hour-and-minute wheels, so there is no format to get wrong and no keyboard to put
  away mid-set.
- **Import from anywhere.** Drop in Liftoff, Strong or Hevy exports, or any CSV with date,
  exercise, weight and reps columns. The format is detected from the header, several files can
  be imported at once, and re-importing an overlapping export never creates duplicates.
- **Honest insights.** Six rules, each a pure function with tunable thresholds, report
  progress, regressions, stalls, neglected lifts, neglected muscle groups and consistency.
  Nothing is reported without the sessions to back it up.
- **A week streak that survives a rest week.** The streak counts consecutive *weeks* with at least
  one session, not days — a training log that asked for a session every day would be asking for
  something no program wants. Home leads with it: the number, the week Monday to Sunday with the
  days trained filled and today ringed, your sessions against your own days-a-week target, and one
  plain sentence about what is actually at stake. Every four weeks kept banks a **rest week**, two
  at most; a missed week spends one instead of ending the run, silently, and the calendar shows it
  afterwards. Miss a whole week with none banked and the run ends — there is no way to buy it back.
  Nothing on the card moves except the ring around today, and only on the two days it means
  something. See [Streaks and the nudge](how-it-works.md#streaks-and-the-nudge).
- **Per-lift analysis.** Estimated 1RM (Epley) over working sets, top set weight, volume per
  session and best set per session, over 3-month, 6-month, 1-year or all-time windows.
- **Weekly volume by muscle group.** Working sets per week with primary and secondary credit,
  flagged as maintenance under 8 sets and likely junk volume over 22. The sets are also shaded
  onto a body, front and back; tap a muscle to see its numbers and filter the list to it.
- **Log and edit workouts.** Add sessions in the app, edit imported ones (date, duration,
  exercises, sets, notes) and have the edits flow into the same analyses. Each exercise card
  has a menu to swap the lift for another while keeping every set typed so far, to move it up
  or down the workout, or to remove it. Program slots can be swapped the same way.
- **Bodyweight tracking** with a 7-day average, and any lift overlaid on the trend.
- **A catalogue that understands names.** Nearly 300 built-in exercises with muscle
  contributions, equipment tags and aliases, so `Seated Dumbbell Shoulder Press` and
  `Seated Shoulder Press` are one lift. About 180 of them were curated from the public-domain
  [free-exercise-db](https://github.com/yuhonas/free-exercise-db). Unknown names become custom
  exercises you can merge later.
- **How to do it.** Every built-in exercise but a handful has its start and end position from
  free-exercise-db, cross-faded into a loop on the lift's page and under **How to do it** in an
  exercise card's menu while you train; tap the picture to freeze it. The photos ship inside the
  app, so they work in a basement gym with no signal. Every exercise, custom ones included, also
  has a **Watch a video** button that opens a YouTube search in the browser, the one place the
  app reaches outside itself, and only when you tap it.
- **Careful with bad data.** Real RFC 4180 parsing, unit conversion and rounding, warm-up
  detection, timer-default holds flagged for review, corrupt durations dropped, empty rows
  listed with a reason.
- **Dark and light themes**, a floating pill navigation and animated Canvas charts with no
  charting library.
- **Local first, sync optional.** Guest mode keeps everything on the device. Sign in with Apple or
  Google on iOS syncs it through the self-hosted server at `api.gains.gerra.sh`; the other
  platforms follow ([docs/launch-plan.md](launch-plan.md)).
- **English and Russian.** Settings → Language switches between them where you stand, with no
  relaunch, and follows the device while it is left on System. Down to the insight sentences, the
  progression hints, the built-in programs and every exercise in the catalogue. See
  [Languages](development.md#languages).

## All screenshots

Every image is rendered from the real app by [a headless UI test](../composeApp/src/desktopTest/kotlin/app/gains/ScreenshotTest.kt)
that signs in, imports the [sample export](../samples/liftoff-export.csv) and walks through each
tab, so the pictures cannot drift from the code.

<table>
  <tr>
    <td align="center"><img src="screenshots/01-welcome.png" alt="Welcome" width="230"><br><sub>Welcome</sub></td>
    <td align="center"><img src="screenshots/02-import.png" alt="Import preview" width="230"><br><sub>Import preview</sub></td>
    <td align="center"><img src="screenshots/03-home.png" alt="Home" width="230"><br><sub>Home: what's moving</sub></td>
    <td align="center"><img src="screenshots/04-history.png" alt="History" width="230"><br><sub>History and heat-map</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/05-lifts.png" alt="Lifts" width="230"><br><sub>Lifts with sparklines</sub></td>
    <td align="center"><img src="screenshots/06-lift-detail.png" alt="Lift detail" width="230"><br><sub>Lift detail</sub></td>
    <td align="center"><img src="screenshots/07-volume.png" alt="Volume" width="230"><br><sub>Weekly volume on the body</sub></td>
    <td align="center"><img src="screenshots/07b-volume-muscle.png" alt="Volume with a muscle selected" width="230"><br><sub>Tap a muscle to filter</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/01b-onboarding.png" alt="Goal onboarding" width="230"><br><sub>Goal onboarding</sub></td>
    <td align="center"><img src="screenshots/12-programs.png" alt="Programs" width="230"><br><sub>Programs ranked by fit</sub></td>
    <td align="center"><img src="screenshots/13-program-detail.png" alt="Program detail" width="230"><br><sub>A program, week by week</sub></td>
    <td align="center"><img src="screenshots/14-program-day-ready.png" alt="A program day, ready to start" width="230"><br><sub>A day, ready to start</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/14-program-day.png" alt="A timed session with the rest countdown" width="230"><br><sub>Timed session and rest</sub></td>
    <td align="center"><img src="screenshots/14b-weight-picker.png" alt="Weight picker" width="230"><br><sub>Weights on a wheel</sub></td>
    <td align="center"><img src="screenshots/15-home-program.png" alt="Home with the next program day" width="230"><br><sub>Up next, and resume</sub></td>
    <td align="center"><img src="screenshots/16-log-workout.png" alt="Log a past workout" width="230"><br><sub>Log a past workout</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/08-body.png" alt="Bodyweight" width="230"><br><sub>Bodyweight</sub></td>
    <td align="center"><img src="screenshots/09-settings.png" alt="Settings" width="230"><br><sub>Settings</sub></td>
    <td align="center"><img src="screenshots/17-summary.png" alt="Workout summary" width="230"><br><sub>Summary when you finish</sub></td>
    <td align="center"><img src="screenshots/17c-summary-photo.png" alt="Summary with a caption and a photo" width="230"><br><sub>Body weight, caption, photo</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/17d-summary-muscles.png" alt="Muscles the workout trained" width="230"><br><sub>What it trained</sub></td>
    <td align="center"><img src="screenshots/17b-summary-duration.png" alt="Changing the duration on the summary" width="230"><br><sub>Duration on the wheels</sub></td>
    <td align="center"><img src="screenshots/10-home-light.png" alt="Home, light theme" width="230"><br><sub>Light theme</sub></td>
    <td align="center"><img src="screenshots/11-lift-detail-light.png" alt="Lift detail, light theme" width="230"><br><sub>Lift detail, light</sub></td>
  </tr>
</table>

The streak card changes with the week, so three of its states are rendered on their own: at risk
on a day the nudge fires, safe, and a full week.

<p align="center">
  <img src="screenshots/18-streak-states.png" alt="The streak card at risk, safe and full" width="46%">
</p>

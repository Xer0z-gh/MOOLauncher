# Moo Launcher — operating notes for whoever picks this up

A fork of [Olauncher](https://github.com/tanujnotes/Olauncher), a minimal text
launcher, kept text-first and extended with the parts of Before Launcher worth
having. Target device is a **Samsung Galaxy A17 5G (SM-S176V)**: Exynos 1330,
4 GB RAM, Android 16 / One UI, 1080×2340 at 450 dpi. Budget hardware with an
aggressive memory manager — anything below that looks fussy is about this phone.

## Build

```bash
export JAVA_HOME=/path/to/jdk17     # Java 8 fails outright: AGP 8.9 refuses it
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`compileSdk`/`targetSdk` 36, `minSdk` 24. On Windows, **do not set
`MSYS_NO_PATHCONV=1` in a shell that runs `gradlew`** — it breaks the wrapper's
classpath and the build fails with `ClassNotFoundException:
GradleWrapperMain`. A stale APK then gets re-installed and reads as "no change".

## The one rule that matters here

**Nothing is done until it has been exercised.** Not "it compiles", not "the
diff looks right" — run it, drive it, and quote the output. Every claim in this
repo's history has a measurement behind it, and the commit messages say what was
measured.

The corollary, learned the expensive way and repeatedly: **suspect the
instrument first.** In one session, seven separate "bugs" were the test harness,
not the launcher:

- `uiautomator` writes attributes as `index text resource-id class …` — `text`
  comes **before** `resource-id`, so a regex anchored on the id can never see
  the text. A harness written that way matched nothing, fell through to a
  fallback the clock alone satisfied, and could not fail.
- `uiautomator` reports **visible** bounds. A row clipped by the end of a list
  measures short; that is not an undersized touch target. Compare against the
  scrolling container's box, not the screen's.
- `uiautomator` **resets a rotation lock** when a dump finishes
  (`UiAutomationConnection#restoreRotationStateLocked`), so a landscape test can
  silently run in portrait.
- `input swipe x y x y 900` is a zero-length gesture. It long-presses on an
  emulator and does nothing on the phone. Use `input motionevent DOWN` / wait /
  `UP`.
- `am start -W` on the launcher reports `TotalTime: 0` because it is already the
  resumed home activity. That is not a fast cold start; it is no measurement.
- `cmd uimode night no` does **not** put this app in light mode. It stores its
  own `APP_THEME` and calls `setDefaultNightMode` with it. A theme test that
  drives the system setting passes while looking at the wrong theme.
- A check that cannot fail is not a check. Before trusting a green run, break
  the thing on purpose and confirm the harness goes red.

## Verifying

Harnesses live in `ClaudeVault/Projects/Moo Launcher/attachments/` (outside this
repo). Each drives the running app and reads the screen back.

| script | what it gates |
| --- | --- |
| `verify_emulator.py` | home, settings hub, all four sections enter *and leave*, idle frames, crash log |
| `test_panel.py` | the notification panel, 19 checks |
| `test_panel_settings_row.py` | the Settings route to the panel |
| `test_panel_overflow.py` | 46 notifications, long/RTL/emoji/no-title text, both themes |
| `sweep_form_factors.py` | six device shapes: small phone, his phone, tablet, tablet landscape, foldable, 1.5× font |
| `audit_screen.py` | 48 dp targets and accessible names on any dump |
| `test_badges.py` | notification badges end to end |

`D:\Workspace\Ops\Moo-Notify` is a throwaway app that exists because
`cmd notification post` always posts as `com.android.shell`, which has no
launcher activity — so nothing on an emulator can notify **as a home app**
without it. An empty `--es title ''` means "post with no title".

## Driving his phone

It is his **daily** phone and his **live** launcher.

- **Never point the emulator harnesses at it.** `test_panel.py`,
  `test_badges.py` and the settings sweep rewrite preferences, swipe rows away
  and tap *Clear all* — that rebinds a gesture he uses and wipes a shade of real
  notifications. `phone_panel.py` is the read-only one.
- Back up and diff preferences around anything you do:
  `adb shell run-as app.olauncher.debug cat shared_prefs/app.olauncher.xml`.
  `SCREEN_TIME_LAST_UPDATED` changes on its own; anything else changing is you.
- **Never tap a remembered coordinate.** Dump, match the node by exact text or
  `content-desc`, tap that node's own centre. A stale dump once opened the
  launcher chooser and cleared the default home activity.
- A long press must land on **empty space**; on an app row it is that row's own
  gesture.
- If a step does not land, **abort** — do not scroll around looking for it. On
  the home screen a swipe up opens the drawer, and a script that improvises ends
  up several screens deep in an unrelated app.

## House style

- **No AI attribution anywhere.** No `Co-Authored-By`, no "Generated with".
  The history was scrubbed of it once already; do not reintroduce it.
- Commit subjects state **what is now true or what was found**, not the action
  taken: `Fleet: the watchdog was killing runs that were working`. No
  `feat:`/`fix:` prefixes.
- Commit bodies carry the measurement and the reasoning, including what was
  tried and failed. A correction to something an earlier commit asserted is
  written down as a correction, not quietly edited away.
- Comments explain **why**, especially where the obvious approach was tried and
  did not work — several fixes here look arbitrary without that context.

## Where the state lives

- `docs/HANDOFF.md` — current state, measured baseline, and what is open
- `docs/CONTINUE-ON-LAPTOP.md` — build and device setup on the Linux laptop
- `ClaudeVault/Projects/Moo Launcher/` — hub, `Decisions.md`, `Toolchain.md`,
  `Before Launcher Teardown.md`, and the harnesses

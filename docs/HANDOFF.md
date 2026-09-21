# Handoff: where Moo Launcher stands, and what is worth doing next

Written 2026-09-21. Read `AGENTS.md` first — it has the build, the verification
rules, and the phone-safety rules, and it is short.

## State

Everything below is verified by driving the running app, not by reading the
diff. Four gates pass on an **API 36 emulator** (the same API level as the
target phone) and the notification panel additionally ran on the **real
SM-S176V**.

| gate | covers |
| --- | --- |
| `verify_emulator.py` | home, settings hub, every section entered *and left*, 0 idle frames, clean crash log |
| `test_panel.py` | the notification panel, 19 checks |
| `test_panel_settings_row.py` | the Settings route into the panel |
| `test_panel_overflow.py` | 46 notifications, 120-char titles, unbreakable words, RTL, emoji, no-title/no-body, both themes |
| `sweep_form_factors.py` | six device shapes, currently 0 findings |

Also proven: the **R8 release build** runs the panel (`minifyEnabled` +
`shrinkResources`, signed and installed as the home app) — which matters because
the nav graph names `NotificationPanelFragment` as a *string*, exactly what R8
strips, and resource shrinking fails at runtime rather than build time.

## Measured baseline

API 36 emulator, 1080×2400 at 420 dpi, **142 third-party apps installed** so the
drawer is a realistic list. Medians of 7 runs, with the spread, because a single
sample from an emulator is noise.

| | median | range |
| --- | --- | --- |
| **cold start** (`Displayed …+NNNms`) | **830 ms** | 769 – 948 |
| drawer scroll, 8 flings | 242 frames, **10.7 % janky** | 9.5 – 14.6 % |
| notification panel open | 15 frames, 13.3 % janky | 6.7 – 23.1 % |
| total PSS after a cold start + one drawer open | 104 MB | — |
| java heap / native heap | 16.7 / 16.5 MB | — |

Script: `ClaudeVault/Projects/Moo Launcher/attachments/perf_baseline.py`.
Regenerate the app list with `make_filler_apps.py 140` — without it the drawer
holds ~2 apps and the scroll number means nothing.

**Cold start is the number to attack.** 830 ms on an emulator with a desktop CPU
behind it will be worse on an Exynos 1330. A launcher is the one app where that
delay is felt every single time the user presses home. Nothing has been done
about it yet — it has only just been measured.

It took four attempts to measure it at all, and all four failures were the
instrument, which is the theme of this project:

1. `am start -W` reports `TotalTime: 0` — the launcher is already the resumed
   home activity, so there is nothing left to start.
2. `am_activity_launch_time` does not exist on Android 16; it is
   `wm_activity_launch_time`.
3. A `\S*` in the pattern could not cross the spaces in `… for user 0: +746ms`.
4. `logcat -c` immediately before the launch swallows the line that launch
   writes. Measured: cleared → zero rows; not cleared → one row. It counts
   before and takes what is new.

## Worth doing next, roughly in order

**1. Cold start.** Nothing has been tried. Places to look, none yet verified:
`MainActivity.onCreate` does theme resolution, wallpaper checks and a default
launcher check before the first frame; `Prefs` is backed by `SharedPreferences`
and is touched many times during startup; the app list is loaded eagerly.
Measure each change — see the four failures above for how easy it is to measure
nothing.

**2. Drawer jank at 10.7 %.** An earlier pass collapsed three per-row view-tree
walks into one; it is better code and the measurement said it was **not** a
speedup (10.98/9.96 % before against 10.63/9.64/8.66 % after). So the remaining
cost is somewhere else — icon rasterising, the `LayoutTransition` from
`animateLayoutChanges`, or the filter running on every keystroke are all
unexamined guesses.

**3. Panel icons for apps with no launcher activity.** In his real shade,
System UI, Android System and Samsung Account render with an empty 32 dp gap,
because `IconCache` resolves through `LauncherApps.getActivityList`, which is
empty for them. Falling back to the notification's own small icon would fill it.
Visible in `attachments/phone_panel.png`.

**4. TalkBack has never been run.** The whole accessibility pass is from the
node tree and sampled pixels — 0 clickable nodes under 48 dp, 0 without an
accessible name — but nobody has listened to it. Enabling TalkBack on his daily
phone was judged too disruptive; an emulator has no such problem.

**5. Small, known, unactioned.** The badge is not clamped when neither side of a
very long name fits; some orphaned imports and Pro-era preferences remain.

Not open any more, despite what older notes say: badge backfill on first enable
is fixed (`NotificationService.rescanActive` handles the already-bound listener),
and the drawer search field is a real 48 dp target.

## Things that will waste your time if nobody tells you

- `MSYS_NO_PATHCONV=1` in a shell that runs `gradlew` breaks the wrapper and the
  stale APK gets reinstalled, which reads as "the change did nothing". This
  invalidated an entire before/after comparison once.
- A fresh install has no `shared_prefs` file, so pushing preferences into it
  silently does nothing and whatever you meant to configure is never set.
- Settings reopens on whichever section was last used, so a test that asserts a
  section header is visible fails against a perfectly good build.
- `cmd notification post` always posts as `com.android.shell`, which has no
  launcher activity, so nothing on an emulator can notify *as a home app*.
  `D:\Workspace\Ops\Moo-Notify` exists for exactly that.
- The emulator and the phone disagree about what a long press is. See
  `AGENTS.md`.

## The standard this is held to

Every claim in the git history has a measurement behind it, and where a claim
turned out to be wrong it is corrected in a later commit rather than quietly
edited. Two examples worth imitating: a "row running under the navigation bar"
that measurement showed was a row clipped by the end of a list, and a "rows in a
different order" claim that came from comparing line 270 of one file with line
231 of another. Both are written down as corrections.

If a check passes, ask whether it *could* have failed. Two checks in this repo
were incapable of failing until someone tested the test: one matched no nodes
and fell through to a fallback the clock alone satisfied, and one drove the
system dark-mode setting for an app that stores its own theme.

# Moo Launcher: implementation and verification

===== SLIDERS WHERE THEY FIT, SEARCH EDGE, SMALL-MISTAKE SWEEP (2026-09-28) =====

- [x] Search prompt and typed text share the start edge and size; the field grows at large text.
- [x] Stepped sliders for settings on a scale (Settings rows, Customize pages, Home information editor); choice rows for separate options; switch rows for on/off.
- [x] Small-mistake sweep of fresh A17 + emulator captures: 28 of 40 findings upheld by skeptics, 21 fixes.
- [x] Re-check of the 28 on fresh captures: 25 fixed, 2 refuted, 1 finished; 13 new upheld findings fixed (gesture value wording, dialog title size and weight, Back glyph column and wrapped-title centre, slider end on the value, 200% Search line, weather value cap, Text weight on Home menus / editor help / visualizer permission / Motion, first-row gap, three labels).
- [x] Reality-checker pass: Gestures picker as a checked list, dead Home-count row removed, RTL Back arrow, slider ids, `.gitattributes` LF; found and fixed on the way: settings that recreate the page dropped you onto Home, and keeping Settings across a recreate crashed on the second change (slider_focus_check.py, recreate_paths_check.py, leave_returns_home_check.py).
- [ ] Low, left: Customize pages' Back chevron at x=109 vs rows at 89 (it anchors the Motion editor's radio circles); new labels are English in every locale (332 of 402 strings not translatable).
- [ ] Publishing (when Tanner picks where): new applicationId (the release ID is Olauncher's own), a release signing key, a privacy policy page and Play's permission declarations, and a gentle "Support Moo" row (Play Billing tip on Play, donate link on F-Droid/GitHub).
- [ ] Font vs Text weight still both set weight; the Font options are now named as a family so they no longer read as weights. Merging them stays Tanner's call.

===== FINAL UI SCAN, CUSTOMIZATION AND BACKEND PASS (2026-09-27) =====

- [x] Audit and UI scan, skeptic-verified; 61 upheld findings implemented, 25 skipped with reasons; a second review of fresh captures (HOLD in all four areas, 13 regressions raised); 6 fixed and exercised (fix_checks.py 9/9, narrow_check.py).
- [x] Battery widget: constant value edge in all three states, state as the name, caption never cut, grid never re-wraps (battery_states_check.py; widget_rows_check.py and battery_scroll_check.py at 200% text; outputs in Moo-Perf-Tools\emu_final_sweep.log).
- [x] Customization: follow Android Power Saver, widget tap opens app, automatic date, Unlocks setup tile (customization_check.py 8/8, preferences set directly). Not exercised: the Focus tick row (A17-only) and the alarm/calendar taps.
- [x] Icon-pack map saved across process starts (330.5 -> 1.2 ms per start on the A17, drawer pixel-identical; miss, hit and new-app paths in iconcache_check.py); A17 A/B of cold start, Home press, warm return, drawer, Settings and memory.
- [ ] Decide Font vs Text weight (both set weight; merging moves his phone's text to 300).
- [x] Search prompt's left edge vs the typed text, and a field that grows at 200%: done 2026-09-28 (Tanner: "make the search apps search field text left aligned").
- [ ] Settings layout split, if the ~29 ms inflate per section open is worth 227 binding references (measured, deferred).
- [ ] Low, found by the second review and left: Home with 4 apps shows 3 rows at rest (the odd-row focus cap); the editor's "Tapping a widget" group is cut at 100% text (105 of 135 px visible until scrolled) and its radios sit on a different grid from the size radios; at 320dp the inline Search back chevron sits low; an idle Search screen draws about 2 frames/s from the caret blink.
- [ ] Warm return draws 1-2 extra small frames (render-thread frames over 11 ms 3/2 -> 6/6); the first frame is no later. Worth a look if Home returns ever feel rough.
- [ ] Wallpaper source is reachable only as a TalkBack action; add a visible row if Tanner wants it.
- [ ] drawer_menu_check.py's first drawer open failed once right after smoke in a sweep; standalone runs pass. Harden its open (wait for the Home frame) if it recurs.
- [ ] Screen time's "Set up" tile still opens the editor (owned by the separate screen-time action task).
- [x] Commit: done 2026-09-28, one commit for 2026-09-22 to 2026-09-28, pushed.

===== PERFORMANCE PASS AND ULTRA BATTERY SAVER (2026-09-26) =====

- [x] Ultra saver follows Android Power Saver (proven by pixels before any change) and is now ultra: 60 Hz, touch boost off, wallpaper layer off on Home, black Home, Paused states, visualizer listeners released, wallpaper job cancelled under manual Ultra. verify_saver.py passes on the exact final A17 APK.
- [x] Eight measured batches: Home scroll GPU, cold start initializers and dependencies, Home return, background app-list scans and usage queries, drawer ViewStubs, visualizer frame cap, icon-pack memory, stored font. Numbers in PERFORMANCE.md.
- [x] Adversarial review of the whole diff: 10 upheld findings fixed, plus two lint regressions; widget-toggle and saver bar-icon fixes shown failing on the pre-fix build and passing on the final one.
- [x] Signed Daily installed and hash-matched on A17; 43 JVM tests; zero-issue Debug/Daily lint; smoke on A17 and emulator.
- [ ] Visualizer frame cap on a device with music playing (unit-tested only; needs a player making sound).
- [ ] Decide whether "open the chosen screen-time app" should return (its only route, `tvScreenTime`, is always hidden) or be removed.
- [x] Commit: done 2026-09-28 (see the 09-27 block).

===== NOTIFICATION BADGE MENU (2026-09-25) =====

- [x] Split notification badges into a full Settings submenu with consistent navigation, row sizing, native style choice and a full-height app picker with explicit On/Off states.
- [x] Show independent Android notification-access state and management route; explain that panel/media access persists when badges are Off.
- [x] Clear existing in-memory badge counts and peek lines when an app is switched Off in the picker or notification panel.
- [x] Clear on successful Home/Apps/Search/shortcut launches, preserve count on failed launch, and verify with emulator instrumentation.
- [x] Remove the clipped carousel edge at 320dp/200% text and verify Home with a full compositor frame after process recreation.
- [x] Run Debug/Daily tests and full lint; install/hash-check signed Daily on A17; exercise A17 badge page/picker/Back and emulator choice persistence, access flow, 320dp/200% text, Dark and landscape.
- [ ] Optional isolated posted-notification fixture and live TalkBack speech check for badge state announcements; code and UI route were reviewed, but those behaviors were not directly observed.


===== REQUEST-BY-REQUEST AUDIT (2026-09-25) =====

- [x] Compare the current implementation and running app against the full user request history. Detailed ledger: `docs/REQUEST-AUDIT-2026-09-25.md`.
- [x] Enlarge categorized Apps headings relative to app names and exercise on current A17 plus 320dp/200%-text emulator. Separate first-viewport icon warmup from scroll prefetch and verify icons in three immediate A17 Apps captures.
- [x] Recheck all seven Settings sections and 25 ordinary nested routes, current A17 focus motor output, Apps scrolling, signed installation, and current-build pinned media/art/wave on isolated emulator.
- [ ] Tanner to judge the 30ms focus tick and exact Apple/Android visual feel by hand; objective motor and media behavior have been checked.
- [ ] Positive charging-wattage state on A17, live TalkBack/RTL/localization, unplugged endurance, long thermal/older-OEM matrix, A17 process-cold startup, and `QUERY_ALL_PACKAGES` Play policy review remain unverified.


===== ALIGNED WIDGETS AND FOCUS TICKS (2026-09-25) =====

- [x] Match the supplied weather reference and give Battery, Screen time and Unlocks consistent value/label sizing, stable wrap columns and a separate charging-detail line.
- [x] Verify narrow Light/Dark, 200% text scrolling, landscape and A17 live weather on running builds; add named accessibility edit actions and focused instrumentation.
- [x] Emit one focus tick for each app crossed during Home scrolling. Verify five motor events for five focus changes on the final A17 APK; prior candidate Off produced zero, then On was restored.
- [x] Install and hash-check the signed Daily APK on A17, run 34 tests per variant, full Debug/Daily lint, frame/idle/memory/log checks.
- [ ] Tanner to judge whether the A17's 30ms fallback feels appropriately light. The phone motor evidence cannot establish feel in hand.
- [ ] Live spoken TalkBack, right-to-left runtime, unplugged battery endurance, long thermal runs and older OEM coverage remain outside this bounded check.

===== EXACT FINAL DESKTOP BUILD AND A17 CHECK (2026-09-24) =====

- [x] Build and install the 2,505,687-byte signed Daily APK; A17 installed SHA256 `3EAB8D25CAAD3A2CD3D22C8F632BA28B3C5FBE99D615C18626C53EF6017B6C0C` matches, and Moo stays Home with personal data preserved.
- [x] Run 34 Debug and 34 Daily JVM tests, matching Debug emulator instrumentation, full Debug/Daily lint with zero issues, and source diff check.
- [x] Exercise A17 Home/weather, tap-to-focus and second-tap launch, Moo Settings hub/Apps editor/Back, categorized Apps via the configured left swipe, and separate Search.
- [x] Measure exact-final A17 Home and Apps three eight-swipe rounds, settled idle, memory snapshot, and crash buffer; no scroll speedup or battery-life gain is inferred.
- [x] Sweep seven Settings sections and 25 nested routes in Light/Dark, conditional routes, five form factors, auto saver/wake, Search empty/no-match, and bounded keyboard/accessibility states on emulator.
- [x] Repair hidden-app pinned-shortcut privacy and legacy hidden keys with regression tests.
- [ ] Optional follow-up: sync verified desktop source to the canonical Linux laptop when available; this is outside the authorized desktop completion scope.
- [ ] Longer unplugged battery/thermal, live spoken TalkBack, and older Android/OEM coverage remain before broad claims.

===== PREVIOUS VERIFIED STATE: AUTOMATIC ULTRA SAVER (2026-09-24) =====

- [x] Follow Android Power Saver automatically while preserving the manual
  Ultra choice, and show the forced state in Motion and power.
- [x] Exercise exact final signed APK on API 36 emulator: system saver
  Off/On/Off, Switch accessibility checked state and saver-on 0-frame idle.
- [x] Install/pull back 2,497,615-byte Daily APK on A17 with matching SHA256
  `a1d86ef461a9be8149eec61c66aedf59d8e3e493a83791827d1cff59f715b95c`; Moo retains Home and personal data. Build, 25 JVM tests and
  full Debug/Daily lint pass without new compiler warnings.
- [x] Measure exact-final A17 Home and Apps with three eight-swipe rounds each:
  median jank 1.78%/1.35%, p99 34/30/30 and 20/24/17 ms; settled idle
  zero frames/3 seconds. No Moo crash-buffer entry after the runs.
- [x] Cache regular and Private Space app lists in process, with explicit
  package/profile refresh and hidden-app mode separation.
- [ ] Retained-Power-section `onResume` path was not exercised because emulator
  external-Settings and sleep/wake routes reset navigation to Home.
- [ ] Repeat automatic Power Saver entry on A17 while unplugged. Android
  rejected the guarded on-command while USB-connected; mode was restored.
- [ ] Live TalkBack, unplugged drain, longer memory/thermal and older/OEM
  checks remain before broad performance or accessibility claims.
- [x] Fix hidden-app privacy with regression coverage: exclude shortcuts
  pinned by hidden packages/profiles from normal Browse. Repair legacy hidden
  keys that `upgradeHiddenApps()` wrote without the required `|` separator,
  including already-migrated preferences. Verified by `HiddenAppKeysTest`.

===== PREVIOUS VERIFIED STATE: CLEAR ALL (2026-09-24) =====

- [x] Install and pull back the signed 2,496,447-byte Daily APK on A17;
  SHA256 `3fb052b0ff09e5e2ff46e06f17079388069a7083e44f45a7279815ef6170141f`
  matched, and Moo retained default Home.
- [x] Run 25 JVM tests (seven suites) and full Debug/Daily lint with zero
  XML findings.
- [x] Place Clear all below Notifications, maintain centered configurable
  Filtered/All above it, and expose a focusable 48dp Button role on A17.
- [x] Scope dismissal to displayed clearable items with a fresh snapshot,
  exclude active/unknown media, and invalidate keys during view changes.
- [x] Exercise Clear all on isolated API 36 emulator with the exact final
  APK: 12 test/system records before, only the protected Android record
  after; the action hid and the panel remained open. A17 notices were kept.
- [x] Benchmark exact-final A17 Notifications scrolling: three eight-swipe
  runs, zero janky frames, p99 15/16/16 ms.
- [ ] Live TalkBack, positive charging watts, unplugged battery endurance,
  older-Android/OEM devices, and translations remain open before broad claims.

The earlier 2026-09-24 state below is historical where it differs.

===== PREVIOUS VERIFIED STATE (2026-09-24) =====

- [x] Install and pull back the final signed Daily APK on SM-S176V; hash
  `b296c95fcbea33cd4dec76379535e58c8e5f7f3bd77a096a7dc3b7c4732046d6`
  matched, and Moo retained the Home role and package data.
- [x] Signed build, 20 JVM tests and full Debug/Daily lint pass with no
  emitted findings under the documented scoped exceptions.
- [x] Direct A17 keyboard focus advances through Home apps, reverse focus
  leaves the first app, and touch restores the repeating carousel.
- [x] Full-screen shortcut Add/Cancel uses explicit consent and sanitized
  publisher labels. Crafted Unicode label, Cancel 0/Add 1 and fixture cleanup
  passed on the final phone build; independent security gate passed.
- [x] Three-round Home/Apps swipe regression on final APK, idle zero-frame
  sample and 309-second navigation/memory/thermal soak are recorded in
  `docs/PERFORMANCE.md` with their build boundaries.
- [ ] Complete real translations if shipping all listed locales. Current
  English fallback is deliberately retained; clean lint does not translate it.
- [ ] Live TalkBack, unplugged battery-life and longer memory/paging and
  thermal runs, plus targeted older-Android/OEM devices, remain before
  broader claims.

The 2026-09-23 state below is historical where it differs from this section.

===== PREVIOUS VERIFIED STATE (2026-09-23) =====

- [x] Install the final signed Daily APK in place on Galaxy A17 and verify its
  pulled hash, retained package data and default Home role.
- [x] Clear emitted Windows/laptop full-lint findings; Daily/Debug builds
  and JVM tests returned `BUILD SUCCESSFUL`. `:app:lintVitalDaily` was
  `SKIPPED`, not executed. Scoped exceptions and untranslated English-only
  strings remain documented.
- [x] Match the Android notification media-player structure with session art,
  an output-mix-reactive filled Wave, played rail, playhead, flat tail and
  elapsed/total time; exercise Wave/Raw/stopped phone states and restore the
  original visualizer preference after the disposable fixture is removed.
- [x] Replace the general Home long-press menu with four Settings-style routes,
  group Settings in seven categories, and keep Home settings concise.
- [x] Exercise full-screen bulk Home apps/Unadded apps, notification tabs,
  Apps/Home/Notifications swipes and 200% emulator layouts.
- [x] Run three-round A17 Home and Apps scroll benchmarks plus settled idle;
  keep exact measurements in `docs/PERFORMANCE.md`.
- [ ] Complete translations if Moo is to ship in all listed locales. The
  present English fallback remains, and full lint is clean under scoped rules.
- [ ] Run live TalkBack/keyboard traversal, longer battery/thermal soak and
  targeted older-Android/OEM checks before claiming those broader guarantees.
  The final A17 checks do not establish a causal battery-life improvement.

Earlier unchecked claims below are historical snapshots, superseded by this
verified state where they concern signed installation or emitted full lint.

===== PREVIOUS FULL-SCREEN EDITOR UPDATE (2026-09-23) =====

- [x] Replace Added/Add tabs with one full-screen checked/unchecked list.
- [x] Label sections **Home apps** and **Unadded apps**; remove Find apps.
- [x] Exercise bulk changes and Cancel on emulator; build, JVM tests and
  R8 vital lint pass.
- [x] Finish generic Settings full-screen review at large text, sync to laptop,
  install/hash-check newest APK on A17.
- [x] Exercise full-screen editor labels, unchecked scrolling, menu Close and
  Cancel directly on A17 without changing Home selections.
- [x] The 326-error/173-warning full-lint snapshot was cleared by the final
  build above.
- [ ] Live TalkBack and long battery/thermal runs remain open.

Earlier entries below include superseded editor and device snapshots.

Updated2026-09-23. The latest user brief promotes focus scrolling into the current
work and pulls the interface back to compact, text-first presentation.

=====
IMPLEMENTED AND EXERCISED
=====

- [x] Weather condition centered under temperature with H/L stacked right.
- [x] Apps-to-Home divider handoff and first-arrival icon warmup.
- [x] Centered Filtered/All with top/bottom placement and left/right order.
- [x] Optional focused-app vibration, gated by Static/saver/system feedback.
- [x] Single Home app editor with separate Added/Add apps tabs, bulk actions,
  rename/reorder and responsive 200% text/keyboard layout.
- [x] Bounded daily wallpaper transfer/decode with success-only marker.
- [x] Latest Windows daily/debug build, 16 JVM tests and bounded emulator matrix.
- [x] The signed source was synced and installed/hash-checked on A17 in the
  final run above. This earlier offline-laptop limit is superseded.
- [x] This earlier full-lint failure was cleared in the final run above.
  Translation remains incomplete under the documented scoped rules.

- [x] Weather: temperature and condition left, high/low stacked to the right;
  A17 live layout and seven emulator layouts/forecast formats verified.
- [x] Content-sized inline Home widgets, common text scale/top alignment, natural wrap.
- [x] A17 installed-layout/identity/data checks; 200% text scroll and form-factor sweep.

- [x] Local settings for Home/Apps/Search/Notifications/Weather/Audio visualizer.
- [x] Filtered/All notification feed; independent density/privacy/icon controls.
- [x] Small/Medium/Wide weather; safe unit cache invalidation during fetch/saver.
- [x] Real output-mix Wave/Bars, theme color, opt-in Home and notification placement.
- [x] Audio session handoff, permission recovery and zero drawing in static/power states.
- [x] Opaque themed dialogs and visible radio/focus states;200% notification tabs.
- [x]16 JVM tests and11-check R8 gate; final A17 local-settings checks preserve data.
- [x] Physical output-mix audio capture was exercised on A17 on 2026-09-23.

- [x] Shared bounded app icon sizes in Appearance, exercised20/48/32dp on phone.
- [x] Draft bulk Home add/remove/rename/reorder with independent duplicate rows.
- [x] Charging-only Home bolt; plugged-full/charging/paused/unplugged tested.
- [x] Configuration draft retention and landscape keyboard selection/Add/Cancel.
- [x] Thirteen JVM tests; final A17 bulk add/remove restores current ten Home apps.

- [x] Remove home bottom buttons, preserve compact app spacing, separate Apps/Search.
- [x] Categorized app browsing with headings/rules and improved curated defaults.
- [x] Infinite focus carousel fills remaining height; centered app emphasized;
  optional outer blur; off-center tap centers before a second tap launches.
- [x] Static home is sharp, equal-sized, finite, one-tap and has no home animation.
- [x] Ultra saver chooses Static, disables decorative work and pauses weather;
  saved Focus mode returns when saver ends. Physical idle check:zero frames/3sec.
- [x] Add/remove a ninth home app on the phone; original eight restored via UI.
- [x] Editable home info and live approximate-location weather, condition vectors,
  high/low in local forecast timezone, specific error/power states, bounded request.
- [x] Midnight forecast-cache regression test, category tests, shortcut identity tests.
- [x] Laptop debug/unit/R8 release builds; nine JVM tests pass.
- [x] Physical Galaxy A17 gesture and tap-to-focus checks; three-round drawer and
  focus benchmarks, with protocol, raw frames and limitations recorded.
- [x] Six-shape geometry/navigation sweep, both Focus and Static.
- [x] Targeted source reviews: accessible context actions, reduced-motion direct
  activation, shortcut validation, power restoration, cancellable weather.

=====
FINAL GATES COMPLETED
=====

- [x] Exercise current R8 APK Home/Settings/Apps/Search/notification-panel routes.
- [x] Large weather label + enlarged text layout stress, empty/one/short home lists,
  reduced-motion/keyboard routes on emulator; retain original panel evidence.
- [x] Independent final evidence review and remaining findings.
- [x] Bounded Small/Medium/Large information widgets, next alarm, four-widget cap
  across editor and legacy settings, stable focus, enlarged-text scrolling.
- [x] Tablet Focus row-count refinement with off-center focus/launch verification.

=====
MEASUREMENT LIMITS
=====

Short gfxinfo runs cannot establish hours of battery life, long-term stability or
coverage of every OEM/form factor. No real battery-drain gain is claimed. Historical
emulator baselines are not directly comparable to the physical phone protocol.
Source is uncommitted; publishing and a new app identity are outside this task.


===== OPTIMIZED DAILY BUILD (2026-09-22) =====

- [x] R8/resource-shrunk, non-debuggable daily variant preserving phone identity.
- [x] Byte-bounded bitmap cache, memory callbacks, cancellable icon preloading.
- [x] Non-blocking icon-pack invalidation and closed XML resources.
- [x] Older-API guards and badge/panel/media listener independence.
- [x] Exact daily APK navigation/customization and physical benchmark/readback.
- [x] Historic full-lint finding count is stale: the exact-current Debug and Daily lint text reports both say `No issues found.` Translation content quality remains a separate open localization item above.
- [ ] Long-duration battery/leak soak and physical OEM/older-Android expansion.

===== MINIMAL PANEL REFINEMENT (2026-09-22) =====

- [x] Scrollable native dropdowns and reliable recycled-row theme tint.
- [x] Optional scroll feedback, including verified A17 motor fallback.
- [x] Minimal Apps/Search header and four app sorting modes.
- [x] Filtered/All notifications with app separators and no top action buttons.
- [x] Wave/Bars inside matching media notification with one shared capture.
- [x] Compact weather and aligned Home information spacing.
- [x] Drag reorder plus draft bulk add/remove and accessible edit alternatives.
- [x] Small/large-text/landscape/Paper regression checks and daily-phone readback.
- [x] Physical output-audio test was exercised on A17 on 2026-09-23.

===== WEATHER REFERENCE (2026-09-23) =====

- [x] Three-line weather hierarchy with full-size condition and muted H/L.
- [x] A17 live layout/customization/install preservation and before/after benchmarks.
- [x] Small/large-text/landscape/Paper and error/saver layout checks.

===== STACKED WEATHER ALIGNMENT (2026-09-23) =====

- [x] High and low on separate lines, condition beneath them.
- [x] Widget text respects Home left/center/right alignment.
- [x] Physical A17 install, screenshot, idle and scroll benchmark readback.

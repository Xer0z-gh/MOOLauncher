# Handoff: where Moo Launcher stands, and what is worth doing next

===== CURRENT UPDATE: SLIDERS WHERE THEY FIT, SEARCH EDGE, SMALL-MISTAKE SWEEP (2026-09-28) =====

- Tanner: "make the search apps search field text left aligned and look for other little ui mistakes like that ... make all the settings that have different values a scrollbar showing the value matching my style", then "dont put the scrollbars everwhere just wherever fitting".
- Search: typed text always starts at the field's start edge (it followed the list's label alignment), the "Search apps" prompt starts exactly where typed text starts and is the same size, and the field grows at large text instead of clipping typed letters at 48dp.
- Settings with values on a scale have a stepped slider under their row (ui/StepSlider.kt), value named live in the row's value column, one dot per stop, track from the name's first letter to the value column's edge, 48dp tall, drags commit on release (theme, font and text size recreate the page), keyboard and TalkBack steps commit as they land. Scales: text size, text weight, row spacing, app icon size, alignment (Left/Center/Right; "Align to bottom" is now its own switch), widget/weather/app/search text sizes, app spacing, visualizer height, focus tick strength, weather refresh. Separate options (date format, theme mode with System now listed, font, where icons show, icon style, badge style, temperature unit, sort, layouts, filters, visualizer style/colour) are a row that lists them with the current one checked; on/off is a switch row. Customize pages and the Home information editor use the same three row kinds (ui/SettingRows.kt) and the Settings pages' column, row pitch and text weight; the Motion editor keeps its radio list (the styles are previewed live) at the same column and weight.
- A small-mistake sweep of fresh captures (A17 and emulator light/dark/narrow; four reviewers, each checked by a skeptic): 28 of 40 findings upheld, 21 fixes after merging duplicates. Among them: dialogs now use the chosen font with "Save" instead of "SAVE"; the editor's widget checkboxes are switch rows; US spelling throughout; Font options named as a family (Sans light / Sans / Sans medium) so they no longer read like Text weight values; Fahrenheit is the same choice on both pages; the visualizer page hides its rows while the visualizer is off; fading scroll edges on the Customize pages.
- Also found and fixed on the way: the slider thumb sat 6px off its first stop (AbsSeekBar centres the thumb only when its offset is half its width, and the thumb changed size on press); stepSliderRow's track alignment was silently skipped (label, value and slider shared one edge).
- A re-check of those 28 against fresh captures (A17 and emulator light/dark/narrow, four reviewers, each finding checked by a skeptic) found 25 fixed; of the other 3, 2 were refuted and 1 (Motion's Preview line) was finished here. It also raised new findings, 13 of them upheld and fixed: the gesture values name what opens ("Missed notifications", not "Open missed notifications", which ran into its label); the Customize notifications title keeps full title size on two lines instead of shrinking to row size (every dialog title now wraps to 2 lines and takes Text weight); Back's glyph sits on the rows' text column and centres on a wrapped title's first line at 200%; Settings sliders end exactly on the value (the track bounds were not recomputed after the padding change); typed Search text at 200% no longer sits above the prompt (the field is sized to the full font box); the weather value's cap matches the label's 220dp, so "No location" stays on one line; both Home menus, the editor help line, the visualizer's permission row and the Motion editor take Text weight; the first row sits the same distance under the title on every Customize page; "Follow system battery saver", "Show on home", "App order". Two proposed fixes were refuted as platform geometry (radio circles centre on the Back chevron, as in every choice dialog).
- Found on the A17 captures of that build: the full-size two-line title left the Customize pages' Back chevron (a compound drawable, which TextView centres on the whole title) between its lines. It now shifts onto the first line (`Dialogs.kt` showSettingsPage; one-line titles are untouched). `back_glyph_check.py` on the A17: chevron centre 46.5 px below the first line's centre before, -3.5 px after.
- A reality-checker pass then returned NEEDS WORK; fixed from it: the Gestures picker was the last choice built as a menu (Cancel first, current action unmarked) and is now the same single-choice list as every other choice row; the hidden, dead "Apps on home screen" row and its picker are removed; the Back arrow mirrors in Arabic and Hebrew; Settings sliders have stable ids, so a keyboard or TalkBack step that recreates the page (Text size, Text weight) can hand focus back to the slider; `.gitattributes` stores text as LF, the ending every file was committed with (Windows tools had turned 26 changed files and every new file to CRLF, so whole files showed as changed).
- Checking the slider ids found a bigger bug: every setting that recreates the page (Text size, Text weight, Font, Theme mode, Color theme) dropped you from Settings onto Home, because `MainActivity.onStop` pops to Home and a recreate stops the Activity. It now skips that pop for a recreate (`isChangingConfigurations`); leaving the launcher still returns to Home (`leave_returns_home_check.py`, emulator and A17). Keeping Settings across a recreate then exposed a crash: Settings sections lower in the back stack come back without a view, and the next recreate destroyed one whose `viewModel` was never set (`SettingsFragment.onDestroy`); `AppDrawerFragment.onSaveInstanceState` had the same weakness. Both are guarded. `slider_focus_check.py` on the emulator: before, focus went to Home's clock; with the pop fixed, the second step crashed the launcher (it reproduced every time); now focus stays on the slider through both steps, still on Appearance, 0 crashes. `recreate_paths_check.py`: Font and Theme mode each changed and changed back through their dialogs, still on the page, no crash.
- Signed Daily APK: 2,290,921 bytes, SHA256 `14B4576E51D4CD5D62F8978CF432166E34CADE70A231B4665713B667BD57F298`; installed on the A17 and hashed there identically; smoke passed and left on Moo Home; `back_glyph_check.py` -3.5 px; `gesture_picker_check.py` (read-only) on all six gesture rows: an 8-item single-choice list titled with the row's name, the current action checked; leaving to Android Settings and back lands on Home. The same source's debug build on the emulator: the two checks above, smoke and leave-returns-Home. On earlier builds of this pass: `customization_check.py` 8/8, `verify_saver.py` and `gesture_picker_check.py --pick` (before the recreate fixes), `search_size_check.py` (before the crash guard: typed text and prompt give identical ink boxes at 100% and at 320dp/200%). 62 JVM tests; lint "No issues found." on Debug and Daily, from reports deleted and regenerated for this build. The A17 capture of every screen (`scan5_a17`) is of an earlier build of this pass (SHA256 `5A0E318E...`, before the chevron and reality-checker fixes).
- The emulator went into a Home-app chooser after an ANR during a capture (host at 1.3 GB free RAM with two Gradle daemons; the ANR traces show 89% system-wide kernel time and Moo idle). ui_scan now stops loudly on the chooser instead of passing with three screens.
- A Moo crash also clears its default-Home choice (`ActivityTaskManager: Clearing package preferred activities from app.olauncher.debug`), which is why a Home chooser after a run means look in `logcat -b events` for `am_crash`; the crash buffer stayed empty for the recreate crash.
- Left, deliberately: the Customize pages' Back chevron sits at x=109 while their rows start at 89 (Settings pages line up at 94/94); moving it would take it off the Motion editor's radio circles, which centre on it. 332 of 402 strings are `translatable="false"` (documented 2026-09-23), so new labels show in English in every locale.
- Committed and pushed on 2026-09-28 as one commit covering 2026-09-22 to 2026-09-28; the "uncommitted" notes in the blocks below are history.

===== PREVIOUS UPDATE: FINAL UI SCAN, CUSTOMIZATION AND BACKEND PASS (2026-09-27) =====

- Tanner asked for "one final ui scan and backend optimization", as customizable and fast as possible, and for the battery widget, which "looks off". An audit (backend, customization) and a UI scan (A17 plus emulator light, dark and 320dp/200%-text captures) were each checked by skeptics. Four owners implemented 61 upheld findings (Home 25, Settings 22, Apps 8, performance 6) and skipped 25 with stated reasons (`D:\Workspace\Ops\Moo-Perf-Tools\impl_reports.txt`). A second review of fresh captures (8 agents; a source hash manifest shows they changed nothing) returned HOLD in all four areas and raised 13 regressions. 6 are fixed and exercised (`fix_checks.py` 9/9, `narrow_check.py`); the rest are low and listed in TODO.md.
- Battery widget: the glyph is always drawn (level fill, bolt while charging), so the value never moves: its left edge is 513 px unplugged, charging and paused (emulator, 100% text). The name is the state (Battery, Charging, Plugged in) and the muted line is the charge power or "Paused" (Samsung battery protection holding the charge; the A17 capture shows it at 79%). A full battery on the charger reads "Charging". A caption that wrapped used to lose its second line (LinearLayout pins a MATCH_PARENT child to its first-pass height; `HomeInformationWidgetView.onMeasure` now measures it free), and the name's width is reserved for the widest state so the grid never re-wraps. At large text, where the widget area scrolls, it settles on a row's top when a drag or fling stops, so that row reads clear and the next one peeks under the fade (`battery_scroll_check.py` at 200%: 85% / Plugged in / Paused all inside the viewport).
- New choices: "Follow Android Power Saver" (Ultra saver follows Power Saver unless turned off), widget taps open their app (battery usage, alarms, calendar) or the editor, Focus tick 30/45/60 ms, an Automatic date format in the locale's order. `customization_check.py` passes 8 of 8 on the emulator, with preferences set directly rather than through the Settings rows: follow Power Saver and the battery-tile tap both ways, the Unlocks "Set up" tile through to usage access, Automatic date one way. The Focus tick row (it appears only on a motor without a native tick, i.e. the A17) and the alarm and calendar taps were not exercised.
- Backend: the icon pack's parsed, installed-filtered map is saved (`no_backup/iconpack.map`, keyed by the pack's version and update time). On the A17 the Arcticons parse cost 330.5 ms of a background thread at every process start; two traced cold starts read the saved copy in 1.2 ms with no parse, and the drawer is pixel-identical. Only this profile's apps are written to the file (Private Space and work apps never are). `iconcache_check.py` on the emulator with Arcticons: a cold start with no file writes it, a second leaves it untouched, an app installed afterwards is re-read and re-saved, and every saved package is launchable. Also: the androidx.startup provider removed, one Power Saver receiver instead of one per page, plain notification rows without the media card, CollationKeys not kept, fewer rescans on hide/delete, and Home presses made 0 binder calls in the A/B.
- Measured on the A17 (alternating installs, `speed`-compiled; numbers in PERFORMANCE.md): cold start 452 -> 421 ms median (n=21 each), then 439 -> 423 ms for the icon cache (n=15/16); Home press on Home: main thread 164/149 -> 104/123 ms, binder calls 16 -> 0. Drawer slightly better; Settings and memory unchanged. Warm return is slightly worse on frames: 1-2 extra small frames per return, render-thread frames over 11 ms 3/2 -> 6/6, worst render-thread frame 20.0/13.7 -> 26.3/34.9 ms, while the time to the first frame is unchanged.
- Not done, deliberately: splitting the Settings layout. Its inflate is 28.9 ms of the ~35 ms per section open (Settings.* trace slices); the rest is about 6.4 ms. A split touches 227 binding references plus the landscape layout for a screen opened occasionally. The slices stay, so a future split can be A/B'd.
- Needs Tanner: Font vs Text weight both set weight (merging them would thin every text on his phone at weight 300); the Search prompt's left edge vs the typed text; typed Search text clips at 200% text. The wallpaper source is now reachable only as a TalkBack action (the old route was an unmarked tap on the label); a visible row is a small change if he wants one. Every sideloaded install on the A17 now raises Play Protect's "Send app for a security check?"; each was answered "Don't send".
- Known: `drawer_menu_check.py` failed its first drawer open once, right after smoke, in the final sweep; it passed twice standalone and is re-run in `emu_final_sweep.log`. An idle Search screen draws about 2 frames/s from the new caret blink. At 320dp/200% text Home shows one app row, and the Home information editor is not reachable by the scan harness there.
- Signed Daily APK: 2,293,245 bytes, SHA256 `1849841260F45F71EAC34D1B6B7204A9777430D298995D7C53C5A23C47731DC6`; installed on the A17 and hashed there identically; smoke passed (all seven Settings sections, Apps, no crash, left on Moo Home) and the saver verified (90 -> 60 -> 90 Hz, black Home, wallpaper layer dropped and restored). It is the A/B'd final5 plus row snapping in the widget area, which runs only when that area scrolls (large text). 59 JVM tests, lint "No issues found." on Debug and Daily. A reality-checker pass (read-only) returned NEEDS WORK on the write-up; its corrections are in these docs, and its two code points (a main-thread wallpaper-colour query on dark Apps opens; Private Space names reaching the saved icon map) are fixed in this APK.
- Nothing is committed. Uncommitted work now spans 2026-09-22 to 2026-09-27.

===== PREVIOUS UPDATE: PERFORMANCE PASS AND ULTRA BATTERY SAVER (2026-09-26) =====

- Tanner asked for performance "in every which way possible", and for Ultra battery saver to switch on whenever Android Power Saver does. It already followed Power Saver (`savingPower` = Ultra pref OR `isPowerSaveMode`). What it lacked was the "ultra": while saving, the window now requests 60 Hz, turns touch boost off (API 35+), drops the wallpaper layer on Home only, paints Home true black, shows "Paused" rather than "Loading..." for screen time and unlocks, releases the visualizer's media listeners, and cancels the 4-hourly wallpaper job while manual Ultra is on. Android 16 hides the A17's 60 Hz mode from apps; a `preferredRefreshRate` of 60 still reaches it. `verify_saver.py` passed on the exact final APK: 90 -> 60 -> 90 Hz, touch boost off and back, wallpaper dropped on Home and back behind the drawer, everything restored. The black paint is proven on the emulator (255 -> 0 -> 255); Tanner's A17 Home is black with the saver off too, so that check cannot distinguish there.
- Signed Daily APK: 2,298,645 bytes, SHA256 `D9A087741634EDF3536C0235B0AAD2A3FEF36E28EC493BFE98B1A48EE2E43B31`; installed on the A17 and hashed there identically, Moo still Home. 43 JVM tests (34 + RibbonFrameCapTest + IconPackFilterTest), zero lint issues on Debug and Daily. The smoke gate (Home, all seven Settings hubs, Apps, crash buffer) passed on the A17 and the API 36 emulator.
- Measured on the A17 with alternating A/B installs at the same ART state (or on the emulator with the same instrument on both sides):
  - Home scroll: the focus label's alpha + text shadow cost an offscreen layer per row per frame. Offscreen-layer draws 779 -> 40 per run; layer flushing 282 -> 23 ms per run.
  - Cold start: WorkManager, EmojiCompat and ProcessLifecycle initializers removed; Material and lifecycle-extensions dropped with constraints pinning the versions Material had lifted; app scan after the first frame. Median 475.5 -> 452 ms (14 validated cold starts each, paired); the final build alone measured 440 ms (n=6, 431-449, unpaired). bindApplication 110.8 -> 65.7 ms in single traces taken hours apart. APK 2.51 -> 2.30 MB (it was 1.88 MB before the font below).
  - Home while on Home, 8 presses, two rounds: worst frame 12.7/12.0 -> 7.8/7.4 ms, main thread 186/192 -> 151/129 ms (the 4-hour recreate, UI_HIDDEN icon trim, pre-frame info rebuild and redundant relayouts are gone).
  - Background: away from Home, package changes only mark the app list stale and one scan runs on return; emulator, three installs/removals with another app in front: 5 scans (1.8 s) -> 0, then 1 on return. Shortcut updates now rescan only when a pinned shortcut is involved; that part, the chat-app case, is reasoned from the code, not measured.
  - Drawer: the row's action menu and rename field are ViewStubs. Inflation 100 -> 40 ms, open frame 55 -> 19 ms; menu pixel-identical to before on default and Forest themes.
  - Memory: Tanner uses Arcticons, whose whole appfilter map was held for the process life. Filtered to installed apps: Java heap -1.65 MB (12.4 -> 10.8 MB), drawer icons pixel-identical.
  - Visualizer ribbon draws every second vsync (gate = 1.5 x the display's own period) and polls at 33 ms. Unit-tested only; no audio player was run.
- An adversarial review (5 areas, each finding re-checked by a skeptic) raised 11 findings; the skeptics upheld 10 (two of them the same widget bug). All are fixed, plus two lint regressions, and the user-visible ones were re-run on both the pre-fix and the fixed build: a second usage widget switched on showed "Loading..." (fixed: shows a value), the saver left white bar icons on light Settings pages (fixed: dark icons there, light on black Home).
- Not done, deliberately: a baseline profile (AOT measured no cold-start difference: 463/474/464 ms), a carousel render key (unsafe). Found and flagged, not changed: the always-hidden `tvScreenTime` was the only route to "open the chosen screen-time app"; that action is unreachable (a separate task).
- Tools: `D:\Workspace\Ops\Moo-Perf-Tools` (git-excluded) holds the harnesses and Perfetto queries: `ab.py`, `trace_scenario.py` (+ `*.sql`), `smoke.py`, `verify_saver.py`, `scan_count.py`, `mem_ab.py`, `drawer_menu_check.py`, `backfill_check.py`, `widget_toggle_check.py`, `bars_check.py`. The emulator-only ones refuse a phone serial.
- Nothing from this pass is committed: the checkout already carried four days of uncommitted work on master, and Tanner decides how that is committed.

===== PREVIOUS UPDATE: LEGACY OR CARD APPS BROWSER (2026-09-25) =====

- The categorized Apps browser defaults to its earlier full-bleed Legacy view again. Customize Apps now offers Apps layout: Legacy or Card. The choice is stored in existing launcher preferences, takes effect immediately, and survives a process restart. Search and app pickers retain their Settings-style card; app sort, icon, row-size and category choices are unchanged. At <=360dp the optional Card uses the full screen width so enlarged category headings remain whole. The shared full-screen Settings Back/title now exposes a Button role, and the native Legacy/Card chooser exposes checked state.
- Exact signed Daily APK: 2,511,731 bytes, SHA256 `EC698D28F99085BFBD9D69F6705BC67630A48BCCB3E989A3531AC0D507B2C525`; installed in place on the A17 and pulled byte-identical. The A17 showed Legacy at first entry, switched to Card and back, kept Search as a card, restored Legacy and ended on Moo Home. A 320dp/200%-text emulator round-trip verified both layouts, full-word category headings, and Legacy persistence after process restart. Debug and Daily each passed 34 JVM tests and full lint with zero issues. These UI changes do not establish a scroll-speed or battery gain. Spoken TalkBack selection was not reliably testable through ADB; bounded node roles and focus targets passed.

===== PREVIOUS UPDATE: SETTINGS-STYLE SEARCH AND SLOW FOCUS TICKS (2026-09-25) =====

- Search now uses the same full-height Settings card, spacing, Back control and title hierarchy as the main Settings screen and other app pickers. Its top-right Apps icon is removed. Apps keeps Search on the left. Back from Search returns to Apps when opened there; direct Search returns Home. Both icon-only controls expose Button roles and 48dp targets. The Notifications customization submenu uses the short Notifications title so it stays at the shared header size.
- The A17's unsupported native tick falls back to a 45ms TOUCH pulse per focus crossing, up from 30ms. On the same 1800ms slow Home swipe, the before build recorded five 30ms Moo pulses and the final signed build recorded five 45ms pulses, both at Android's unchanged LOW (0.71) touch intensity. Focus vibration remains optional and is suppressed by Static, Ultra Battery Saver, disabled system touch feedback or lost window focus. Motor records prove execution, not Tanner's subjective tactile preference. The longer motor-on time may use slightly more energy during frequent Focus scrolling; Static, saver and the Off setting still avoid it.
- Final signed Daily APK: 2,510,755 bytes, SHA256 `1302EF62C3978D2C909ED55E380A71DE1E2071CB46029D8D6A500A60B4AFC6AC`; installed in place on the Galaxy A17 and pulled byte-identical. The exact build passed Home -> Apps -> Search -> Apps -> Home, including accessible Button roles and absence of the top-right icon. The emulator opened/backed all seven Settings sections and 25 ordinary nested routes; Icon style was opened after temporarily enabling icons and restored Off; Icon pack had no installed pack. At 320dp with 200% text, Search and Notifications fit, the final Notifications row is scroll-reachable, and Back returns correctly. Both variants passed 34 JVM tests and full lint with zero issues. A17 remained Home, had no Moo crash-buffer entry and drew zero settled Home frames over three seconds. A physical comfort judgment, live spoken TalkBack and broader OEM coverage remain open.

===== PREVIOUS UPDATE: NOTIFICATION BADGE SETTINGS (2026-09-25) =====

- Badge reset now happens only after Android accepts an app/shortcut launch. Home, Apps and Search share the success path; a missing app keeps its count and cached preview. An isolated emulator instrumentation test seeded process-local counts, launched Settings successfully, then tried a missing package and checked both outcomes.
- A 320dp/200%-text emulator exposed a real clipped outer carousel row directly under Battery. The Home widget gap now grows only at large system text, and short Focus viewports center complete app rows. UI bounds after settling/recreation and an idle compositor recording show visible apps without the clipped line. Normal A17 spacing stays unchanged; exact-final A17 Home idled at zero rendered frames over three seconds. Still screenshots on the emulator sometimes omit unchanged carousel pixels, so the compositor video was used for the visual check.
- Notification badges now open their own full Settings card from Notifications. Show badges, style, tap-to-peek and Apps with badges use the same header, Back, row rhythm and full-row targets as the main Settings screen. Style uses the ordinary single-choice dialog. The app picker is a full-height Settings surface with explicit On/Off state, and its choices persist. The Android notification-access state and tap-through sit after badge choices, with supporting text explaining that the panel and media still use the grant when badges are Off.
- Turning an app's badges Off now clears its in-memory count and cached peek lines immediately, both from the picker and the Notifications panel filter. The isolated emulator round-tripped style and per-app choice, verified Off/On/Off with temporary listener access, and restored its choices/access. The exact current A17 build opened badge settings and app picker, checked access state and Back, with no personal preferences or notifications changed. Final A17, 320dp/200%-text, Dark and landscape captures were visually checked; the UI, accessibility and scoped privacy gates passed. A live posted-notification fixture was not used to observe the cache clearing on a rendered Home badge.
- Signed Daily APK: 2,510,651 bytes, SHA256 `3087959E923F11E6A00BE18B4945A5BF8945B562546473B504AF67133C150F37`; installed A17 copy matched byte for byte and Moo remained Home. Debug and Daily each passed 34 JVM tests; both full lint tasks found no issues and the final compile emitted no new warnings. A17 crash buffer had no Moo entry. No startup, scroll-speed or battery gain is claimed for this Settings change. Live TalkBack speech and a posted-notification fixture remain outside this check.


===== CURRENT UPDATE: REQUEST-BY-REQUEST AUDIT (2026-09-25) =====

- The full chronology is reconciled in `docs/REQUEST-AUDIT-2026-09-25.md`. The latest Apple lock-screen weather photo is the target where older H/L instructions conflict. The audit fixed two concrete Apps gaps: category headings had been smaller than app labels, and scrolling could cancel the first-viewport icon warmup. Headings are now Apps label size plus 4 sp, visibly larger on A17 and unclipped at 320dp with 200% text; three immediate A17 entry captures show icons in the first viewport. Warmup remains asynchronous and bounded.
- Exact current signed Daily APK: 2,507,555 bytes, SHA256 `26DF9CCBF40262857B00715A3F7CF9CB80F0015FC1647E0111EDFFC8AF2D672E`. Installed A17 copy matched, Home role retained. Debug/Daily each passed 34 JVM tests, full lint emitted zero XML issues, and the emulator opened/backed through all seven Settings sections and 25 ordinary nested routes. Three conditional routes lacked prerequisites in this pass.
- Current A17 Apps scrolling: three eight-swipe rounds, 0.88/0.53/0.89% jank and p99 16/15/15ms; repeat 0.89/0.53/0.71% and p99 17/16/16ms. Short-run results do not establish a speedup over the prior signed build. Settled Home drew zero frames/3s; one 700ms Home swipe yielded three new Moo TOUCH motor records around 38-39ms. Preceding Debug emulator media fixtures, before the icon-only warmup fix, showed artwork behind pinned playback, changing waveform pixels, 124 frames/2s while playing, and zero idle frames/3s after stop. Fixtures were removed and emulator preferences restored.
- This audit does not establish a causal speedup or universal battery/stability guarantee. Still open: Tanner's physical haptic feel, positive A17 charging watts, live TalkBack/RTL, unplugged endurance, long thermal/older-OEM coverage, A17 process-cold startup, and `QUERY_ALL_PACKAGES` Play policy review. Two A17 force-stop attempts returned UNKNOWN/0ms and WARM/311ms because Android restarted its default Home process, so neither is a valid cold measurement. The A17 was left on Moo Home without dismissing personal notifications.


===== CURRENT UPDATE: APPS AND SEARCH HEADER (2026-09-25) =====

- Signed, non-debuggable Daily APK: 2,507,159 bytes, SHA256 D0972A600425E5E838D678816840CFC7AB5AD218C16CEB7F6382AD15938013FE. Installed in place on the Galaxy A17 and pulled back byte-identical. Moo retained the Home role, and existing app data and notifications were preserved.
- The categorized Apps browser places the Search glyph on the left and Apps on the right. Search reverses the header controls, removes the old upper-left title, and shows a smaller centered Search apps prompt in the input row. The prompt disappears on input and returns when cleared. Tapping it opens the keyboard. Picker-specific hints remain unchanged. The header keeps pane/heading semantics and the long-press Customize Search shortcut.
- The exact A17 APK was exercised through Apps, Search, the centered prompt, clear, Apps return, and long-press Customize Search. Dark-theme screenshots are in the task work folder. The matching Debug build showed no clipping at 320dp in Light with 130% and 200% text. Debug and Daily each passed 34 JVM tests; both full lint XML reports contained zero issues under existing scoped exclusions; the A17 crash buffer had no Moo entry. UI and bounded accessibility reviewers passed. Live TalkBack speech and hardware-keyboard focus order were not tested. No performance or battery gain is claimed for this header change.

===== PREVIOUS UPDATE: ALIGNED HOME WIDGETS AND FOCUS TICKS (2026-09-25) =====

- Final signed, non-debuggable Daily APK: 2,506,267 bytes, SHA256 7653B8CFDB5B1E26BD5EEA5EE0EC01F6D6618096DCAF6397177012383728B550. Installed in place on the Galaxy A17 and pulled back byte-identical. Moo retained Home; app data and notifications were preserved.
- Weather keeps the supplied three-line lock-screen rhythm. Battery, Screen time and Unlocks now share its value/label sizing and top alignment. Battery wattage, when valid, sits on a muted third line below Battery. Wrapped rows use consistent intrinsic column starts without stretching content into equal tiles. The exact A17 showed Clear 55 degrees, H:75/L:54 and Battery 79%; no bolt or wattage appeared while it was not charging.
- A narrow 320dp emulator at 130% text showed aligned Weather/Screen time and Battery/Unlocks columns in Light and Dark. Landscape at 130% held all four widgets on one row. At 200% text, the bounded widget area scrolled to reach Battery, Screen time and Unlocks. Emulator instrumentation checked the weather/Battery second-line baseline (0px difference), charging detail row, and named Edit home information accessibility action. UI and bounded accessibility reviewers passed; live spoken TalkBack remains untested.
- Focus vibration now fires when each centered app changes during scrolling instead of queuing a tick that a later change could cancel. Android's segment/click feedback is preferred where supported; the A17's unsupported tick effect uses a 30ms touch pulse that honors Moo's toggle, Android touch-feedback setting and power saver. Before this fix, one 700ms A17 swipe crossed about five apps but produced zero new 12ms motor events. The exact final APK produced five new 30ms TOUCH motor events for one 700ms swipe. A preceding candidate's Off control produced zero events and On was restored. Tanner's subjective feel is still awaiting confirmation.
- Final assembly, 34 Debug and 34 Daily JVM tests, and Debug/Daily full lint passed; both lint XML reports have zero issues under existing scoped exclusions. Three exact-final A17 eight-swipe Home rounds: 517/528/534 frames, 8/7/7 janky, p99 29/27/28ms. The immediate post-scroll three-second idle sample included 69 settling frames; after two seconds and a reset, settled idle was 0 frames/3 seconds. PSS was 132,597 KiB; sampled logs showed no Moo fatal/ANR. No battery-life or causal performance gain is claimed. See VERIFICATION-2026-09-24.md and PERFORMANCE.md.

===== PREVIOUS UPDATE: REFERENCE WEATHER WIDGET (2026-09-25) =====

- Current signed, non-debuggable Daily APK: 2,505,063 bytes, SHA256 E57574D1F6A126D976B1CA090A251AC665B497881E39AC9D086E0AFD6232D517. Installed in place on Galaxy A17 SM-S176V, pulled back byte-identical, with Home role, app data, and notifications preserved.
- Home weather matches the supplied three-line reference: filled cloud and current temperature, condition, then muted inline H/L. The exact A17 APK showed Cloudy 60 degrees and H:75 degrees L:55 degrees without clipping. Clear night selects the moon icon; a day/night-only change repaints it. The weather announcement remains one accessible focus target and has an English language span.
- The final Debug counterpart passed phone, narrow 2x-text, and wide emulator checks with Cloudy and Partly cloudy. A focused emulator instrumentation check verified day-to-night icon repaint with unchanged text and presence of the speech language span. Final source passed 34 Debug and 34 Daily JVM tests, Debug/Daily lint with zero issues under scoped exclusions, and Debug/AndroidTest/Daily assembly. The A17 crash buffer had no Moo entry, and settled Home rendered zero frames over three seconds after a second gfxinfo reset. Earlier scrolling performance receipts are for the preceding APK. Arabic/Hebrew weather text is untranslated and live TalkBack pronunciation was not exercised. See VERIFICATION-2026-09-24.md.

===== PREVIOUS UPDATE: FINAL DESKTOP DAILY AND A17 REPLAY (2026-09-24) =====

- Final signed, non-debuggable Daily APK: 2,505,687 bytes, SHA256 `3EAB8D25CAAD3A2CD3D22C8F632BA28B3C5FBE99D615C18626C53EF6017B6C0C`. Installed in place on Galaxy A17 SM-S176V; installed base.apk hash matched. Moo stayed default Home and personal data/notifications were preserved. This desktop checkout is the user-authorized source; canonical Linux laptop sync is deferred.
- Both Debug and Daily JVM suites passed 34 tests each, full Debug/Daily lint had zero issues under documented scoped exclusions, and matching Debug instrumentation passed on the isolated emulator. A Daily instrumentation attempt used a Debug runner against minified production classes and failed for that packaging mismatch; the compatible Debug run passed. No production crash was observed.
- Exact-final A17 Home/weather, tap-to-focus/second-tap launch, Moo Settings and nested Apps editor/Back, categorized Apps with visible icons, and separate Search were exercised. The A17's current gestures are left for Apps and up for Settings; no preferences were changed.
- Exact-final three-round A17 Home frame counts 550/555/552, janky 10/8/9, p99 30/28/30 ms; Apps 458/461/452, janky 6/6/4, p99 18/19/17 ms. Settled Home rendered zero frames over three seconds. Home focus vibration was On during these rounds; previous matched comparison had it Off. No speedup or battery-hour gain is claimed. One post-route PSS snapshot was 158,397 KiB without a matched baseline.
- Emulator checked all seven Settings sections and 25 ordinary nested routes in Light and Dark, three conditional routes separately, five form factors including 2x text, color palette contrast, Android Power Saver Off/On/Off plus sleep/wake, Search states, and bounded accessibility. UI and accessibility reviewers passed their scoped re-reviews. Live spoken TalkBack, older OEMs, unplugged battery endurance, and longer thermal/memory behavior remain outside the measured evidence. See `VERIFICATION-2026-09-24.md` and `PERFORMANCE.md`.

===== PREVIOUS UPDATE: AUTOMATIC ULTRA SAVER AND BOUNDED PERFORMANCE (2026-09-24) =====

- Final signed Daily APK: 2,497,615 bytes, SHA256 `a1d86ef461a9be8149eec61c66aedf59d8e3e493a83791827d1cff59f715b95c`. It was
  installed in place on Galaxy A17 SM-S176V and pulled back byte-identical;
  Moo remained Home and Android Power Saver remained off. The laptop
  `assembleDaily`, 25 JVM tests in seven suites, full Debug/Daily lint and
  Kotlin compile passed with no test failures, lint findings or new compiler
  warnings. `lintVitalDaily` was skipped; full lint ran.
- Ultra Battery Saver follows Android Power Saver without changing the user's
  manual choice. Motion and power shows `On (Android Power Saver)` and disables
  the manual row while the system forces it. The exact final APK on an isolated
  API 36 emulator passed Off/enabled -> forced On/disabled -> Off/enabled;
  accessibility exposed Switch with checked=false/true. Saver-on settled Home
  drew zero frames in three seconds. A guarded Power Saver-on attempt on the
  USB-connected A17 was rejected, so the physical transition remains open;
  the original off state and Home were restored. External Settings and
  sleep/wake reset the emulator to Home, leaving retained-Power-section
  `onResume` behavior source-reviewed only.
- Saver mode suppresses motion/haptics and optional media work. Charging
  telemetry reads only while actively charging and at most every 15 seconds;
  invisible usage scans stop; icon warmup cancels; wallpaper fetch/decode
  defers without advancing the day marker. Normal Apps reuses a five-minute
  in-process list. Private Space enumeration is cached by profile handle and
  locked state, and profile/package changes force refresh. These reduce work
  by code path; battery-hour savings were not measured.
- Exact-final A17 three-round, eight-swipe Home run: 572/562/575 frames,
  13/10/10 janky (median 1.78%), p99 34/30/30 ms; settled idle 0 frames in
  three seconds and PSS 134,078 KiB. The configured left swipe opened Apps;
  three eight-swipe rounds rendered 506/512/519 frames, 5/8/7 janky (median
  1.35%), p99 20/24/17 ms. Post-Apps idle was 0 frames/3 seconds and PSS
  125,733 KiB. Moo stayed foreground; the checked crash buffer had no Moo
  entry. Thermal status was 1 after this run. The first Apps attempt was
  interrupted by ADB loss while the phone entered Messages; its partial data
  was excluded. Earlier medians used other APKs and loads, so no causal
  speedup is claimed. See laptop `work/phone-saver-private-final-home.json`
  and `work/saver-private-final-apps-report.json`.
- Independent source review found no new saver/cache security regression.
  Hidden-app pinned shortcuts can appear in normal Browse, and legacy
  hidden-app migration omitted the `|` in package/profile keys. Both are
  preexisting privacy issues for a separate tested fix. Live TalkBack,
  unplugged endurance, longer memory/thermal
  testing and older/OEM coverage remain open.

===== PREVIOUS UPDATE: CLEAR ALL AND FILTER SAFETY (2026-09-24) =====

- Signed Daily APK: 2,496,447 bytes, SHA256
  `3fb052b0ff09e5e2ff46e06f17079388069a7083e44f45a7279815ef6170141f`.
  Installed in place on the Galaxy A17, pulled back byte-identical, and Moo
  retained the default Home role. Laptop `:app:assembleDaily`,
  `:app:testDebugUnitTest` (25 tests in seven suites), `:app:lintDebug` and
  `:app:lintDaily` passed; both full-lint XML reports contain zero issues.
- Notifications now has a centered Clear all footer below the scroll list.
  It is at least 48dp tall and exposes a focusable Button role while keeping
  Moo's small-text appearance. A17 UI bounds were [0,2070][1080,2205] in
  both filter views. With filters set to Bottom, the list ended at y1935,
  Filtered/All occupied y1935-2070, and Clear all followed at y2070-2205;
  the original Top placement and All selection were restored.
- Clear all targets dismissible notifications displayed by the selected view,
  plus a displayed paused media card when Android marks it dismissible. It
  leaves active media and nonclearable items alone. Switching filter views
  immediately hides the action until new data arrives; tapping it re-reads
  the notification service on IO before choosing keys, so stale All keys
  cannot be dismissed from Filtered. If active media state cannot be read,
  media-token notifications are conservatively excluded.
- The personal A17 notifications were preserved. On an isolated API 36
  emulator with the exact final APK and listener enabled, the All panel
  exposed Clear all as a clickable Button. `cmd notification list` showed
  12 disposable/test and system records before the tap. After the tap, only
  the protected Android record remained; Clear all disappeared and the
  panel stayed open. The scoped reality review passed this feature test.
- Three eight-swipe Notifications scroll runs on the exact A17 APK reported
  266/255/256 frames, zero janky frames in each, and p99 15/16/16 ms. The
  prior exact A17 Home/Apps sample is below. Short USB-powered samples do
  not establish universal speed or battery-life gains.

===== PREVIOUS UPDATE: WEATHER, CLEAN HOME RETURNS AND EXACT A17 REPLAY (2026-09-24) =====

- Signed Daily APK: 2,496,299 bytes, SHA256
  `03642d468617f4c340eba270643c4d26996297ddef3769e648861b89f05bff23`.
  It was installed in place on Galaxy A17 SM-S176V / Android 16, pulled back
  byte-identical, and Moo retained the default Home role. The formatting-only
  final rebuild produced the same hash. The laptop build, 25 JVM tests and
  full Debug/Daily lint passed; both lint XML reports contain zero issues.
  The current crash buffer had no Moo entry.
- Home weather now places the condition directly below and centered with the
  temperature. High and low stack to its right, each aligned to the
  corresponding temperature/condition line. The live A17 screenshot showed
  47°, Clear, H:67° and L:47° in that layout.
- The gray/white horizontal line that appeared above Home apps on returning
  from Apps was an idle media waveform seek rail, not a carousel divider.
  `WaveView` now draws only for a current PLAYING session and resets that
  state on stop. Ten immediate Apps-to-Home captures on the exact APK showed
  no rail, versus 7-8 of ten before the guard. Soft-edge Focus remained on.
  A real temporary player then verified the pinned top media row, changing
  waveform pixels in three successive captures, 188 rendered frames in two
  active seconds and zero frames in three idle seconds after stopping. The
  temporary player was uninstalled.
- Filtered/All remain centered and configurable for top/bottom placement and
  left/right order; the A17 UI bounds and both placements were exercised.
  Apps icon cache warming runs before the drawer is opened, with bounded
  ahead-of-scroll prefetch; the first settled Apps viewport showed its icons.
- Exact-APK A17 three-round, eight-swipe samples: Apps jank 1.11/0.55/0.37%
  (median 0.55%, p99 17/16/16 ms); Home jank 1.47/1.28/1.11%
  (median 1.28%, p99 30/26/25 ms). Settled Home drew 0 or 1 frame in
  three seconds. PSS was 125,989 KiB after Apps and 155,002 KiB after Home.
  These short USB-powered A17 runs do not prove battery life, long-term memory
  stability, live TalkBack or every Android form factor. Positive charging
  wattage is still unverified while Samsung battery protection pauses charging.

===== PREVIOUS UPDATE: PINNED MEDIA AND CHARGE ESTIMATE (2026-09-24) =====

- The signed Daily APK is 2,494,455 bytes, SHA256
  `183b3f50692aca0405b44d17621af7c9982990293adac1f72361b9a70e83e731`.
  It was installed in place on the USB-connected Galaxy A17 SM-S176V and
  pulled back byte-identical. Moo remains default Home; package data was not
  cleared. Laptop `assembleDaily`, 25 JVM tests, `lintDebug` and `lintDaily`
  passed. Both full-lint XML reports contain zero issues.
- The matching media notification, album art and audio-reactive waveform now
  occupy a fixed top slot above Filtered/All. It stays visible while the list
  scrolls, is not duplicated in the list, and remains available in both filter
  views. A physical A17 test using a disposable media player kept the pinned
  bounds `[0,235,1080,755]` after four list swipes and a filter switch; the
  original All selection was restored. The three-second active sample rendered
  278 frames with 0% jank. A review found a media-only false-empty message;
  `PanelEmptyStateTest` now covers its correction and the status has a polite
  accessibility live region.
- Home now samples Android's battery current and voltage only while active
  charging and the battery widget is shown on Home, at most every 15 seconds.
  The second line beneath the bolt is an approximate net battery charging
  wattage (`≈x.x W`), not USB charger input wattage. Invalid, paused, full
  and discharging readings hide it. The A17 is held at 85% by Samsung battery
  protection (`status: 4`); the final Home screenshot confirmed that no
  wattage is fabricated while paused. Positive charging wattage remains
  unverified on the phone pending a charge state.
- After this final build, settled Home rendered zero frames in three seconds.
  The disposable audio fixture was removed, but Google Play Protect intercepted
  a later attempt to reinstall it for an exact-final-APK replay; the prompt was
  dismissed, the fixture was confirmed absent, and Moo remained foreground.
  The final media-only state and new pinned-area swipe are covered by source
  review/unit logic but lack an exact-final-APK physical replay. Live TalkBack,
  positive charging, longer unplugged battery/thermal and other-OEM checks
  remain open. Do not claim universal optimization from one A17.

===== PREVIOUS UPDATE: FINAL A17 CONSENT AND STABILITY PASS (2026-09-24) =====

- Final signed laptop Daily APK: 2,493,203 bytes, SHA256
  `b296c95fcbea33cd4dec76379535e58c8e5f7f3bd77a096a7dc3b7c4732046d6`.
  Installed in place on SM-S176V; pulled APK hash matched, Moo kept the Home
  role, and the disposable test app was removed. Current source is uncommitted.
- Laptop `assembleDaily`, `testDebugUnitTest` (20 tests), `lintDebug` and
  `lintDaily` returned `BUILD SUCCESSFUL`. Both full-lint reports read
  `No issues found` under the documented scoped exceptions. Gradle marked
  `lintVitalDaily SKIPPED`. The existing English fallback is not full
  localization.
- On the exact final APK, three eight-swipe A17 rounds completed with no route
  failure: Home jank 0.97/0.70/0.71% (median 0.71%), p99 26/28/27 ms; Apps
  0.27/0.44/0.27% (median 0.27%), p99 15/16/16 ms. Settled Home drew zero
  frames in three seconds. These are short load-dependent samples, not a
  causal speedup or universal-device claim.
- A 309-second, 16-cycle A17 Home/Apps/Notifications soak on the preceding
  layout-identical APK kept one process alive and thermal status 0. PSS peaked
  at 170,356 KB after the first cycle, then settled at 158,364-158,368 KB for
  the last five samples; there was no upward PSS trend during that span.
  Swap PSS rose from 16,199 to 42,702 KB, so this short run does not prove
  overall memory stability. Battery temperature was 24.3-24.7 C. USB power held battery level at 85%, so this
  does not measure battery drain. The final rebuild changed only shortcut-label
  sanitization and passed its own build and phone regression.
- Physical keyboard checks moved focus across two consecutive Home apps, let
  reverse DPAD movement exit the first app to Weather without a dead key, and
  restored the repeating carousel after a completed Home touch. The UI gate
  and bounded keyboard accessibility gate passed. Live TalkBack remains open.
- A real Android shortcut request now requires a full-screen Add/Cancel choice.
  The publisher package is shown separately; ISO controls, Unicode whitespace and
  bidirectional marks cannot push it off the message. On the final A17 APK,
  a crafted U+2028/U+2029 label rendered as spaces, Cancel left Pinned: 0, and
  Add produced Pinned: 1. Uninstalling the disposable publisher removed the
  fixture; the independent shortcut security gate passed.
- Earlier features and media validation remain in the dated section below.
  Broader OEM/older-Android behavior, unplugged battery life and live TalkBack
  have not been certified. Do not describe Moo as universally hyper optimized
  on this one phone's evidence.

===== PREVIOUS UPDATE: FINAL A17 WAVE, HOME EDITING AND SETTINGS (2026-09-23) =====

- The final signed laptop Daily APK is 2,491,147 bytes, SHA256
  `b374f10eeb91f271aa98d278294ce84cebc2f085ed5c93060b245af26b55d712`.
  It was installed in place on the Samsung Galaxy A17 (SM-S176V). A pullback
  matched the built hash; Moo's package data and default Home role were retained.
  This supersedes the pending signed-install statements in earlier checkpoints.
- Windows and laptop integrated builds and JVM tests returned
  `BUILD SUCCESSFUL`. Full `lintDebug`/`lintDaily` reports say
  `No issues found.` Gradle marked `:app:lintVitalDaily SKIPPED`, so that
  task did not execute. The clean full-lint reports include documented
  scoped exceptions; 327 English-only strings remain
  untranslated and are marked nontranslatable. The prior 330-error/154-warning
  media build is history, not the installed build.
- The Android-style notification media card has matching artwork behind the
  controls, a real output-mix, audio-reactive filled ribbon on its played rail,
  a playhead, a flat unplayed tail and elapsed/total time where provided. Capture
  runs off the UI thread at about 60 Hz while active and stops when hidden,
  paused, motion-off or in saver mode. The A17's Samsung notification player
  was captured as a visual reference. On the final Moo phone pass, the Wave
  rendered 278 frames in three seconds with 0 jank and 7 ms p99; Raw rendered
  168 frames with 0 jank and 8 ms p99. Stopped playback rendered 0 frames.
  Phone crops confirmed artwork, moving played region, still tail and advancing
  playhead. The disposable audio test player was removed and the user's
  visualizer style restored.
- Home long-press opens a full-screen Settings-style menu with Edit home apps,
  Home information, Home settings and All settings. The bulk editor retains one
  full-screen Home apps/Unadded apps list. Main Settings has seven categories;
  the Home pane keeps seven core controls. A17 routes verified the menu, bulk
  sections, Filtered/All and Home/Apps/Notifications swipes. The notification
  list hash was unchanged before/after the route. Emulator normal and 200%
  text review, bounded UI/accessibility review passed; live TalkBack/keyboard
  traversal was not certified.
- Three eight-swipe A17 rounds: Home 0.90/1.48/1.47% jank (median 1.47%);
  Apps 1.11/1.10/0.74% (median 1.10%). Settled Home idle drew 0 frames in
  three seconds. These short samples and one A17 do not prove a causal power
  saving, long-term thermal stability or broad OEM/older-Android coverage.
- In five forced process removals, Android auto-relaunched Moo as default Home.
  ActivityTaskManager `Displayed` times were 532/426/449/404/397 ms, median
  426 ms. Separate `am start -W` calls returned 0 ms after Home had already
  relaunched, so those are warm calls, not cold-start evidence. A prior
  369 ms median used a different build/load; no causal startup gain is claimed.
- Current source remains uncommitted. Earlier blocks below are dated history;
  use this section and the matching artifact hash for current installed state.

===== PREVIOUS UPDATE: FULL LINT CLEANUP CHECKPOINT (2026-09-23) =====

- The integrated Windows command
  `:app:lintDebug :app:lintDaily :app:testDebugUnitTest --offline` passed.
  Both generated full-lint text reports say `No issues found.` The earlier
  media build had 330 errors and 154 warnings. This is an analyzer result,
  not a new signed-laptop or physical-phone installation.
- The cleanup marked 327 existing English-only strings as
  `translatable=false` because they lack translations in the 29 locale sets;
  it also removed 98 unreferenced
  resource definitions/assets. The visible English fallback is unchanged.
  No translations were supplied; localization remains product debt.
- `app/lint.xml` documents exact exceptions for the existing `values-he`
  qualifier, the density placement of `ic_rounded.webp`, and pinned
  toolchain/library versions. Source-local exceptions cover lint advice that
  would alter existing appearance or gesture behavior. Zero emitted lint
  findings includes those scoped exceptions; it is not proof that every
  underlying compatibility concern was eliminated.
- At this earlier checkpoint the cleanup intended no visible or functional
  change, and the signed laptop APK/A17 regression were pending. The current
  section above records their completed verification. The 2,506,883-byte media
  build below remains the last physically verified artifact. Compiler
  deprecation cleanup is continuing separately. Settings virtualization,
  full localization, TalkBack and longer battery/thermal/OEM checks remain open.

===== PREVIOUS UPDATE: SESSION ARTWORK AND LIVE WAVEFORM (2026-09-23) =====

- A playing media notification now uses its own session artwork as a subdued
  card background behind a dark scrim, track text and an opt-in audio-reactive
  waveform. The artwork is independent of the visualizer switch. Notifications
  previews off hides the artwork and track text; the media-artwork switch can
  remove the background separately. Large 200% text remains readable.
- The wave reads real Android output-mix samples, not a synthetic animation.
  It belongs only to the notification with the matching MediaSession token.
  Two concurrently playing sessions suppress the ambiguous output-mix wave
  rather than suggest that either app owns all audio. No microphone samples are
  stored, no audio file is made, and no background recording service was added.
  Hidden, paused, detached, motion-off and power-saving states release capture.
- Album-art decode is capped at 384 px per edge. Content/resource URIs have a
  2 MiB encoded-byte and 32 MP source-pixel bound, are serialized and cancellable,
  and do not keep full-resolution images in notification models. Stale artwork
  callbacks cannot repaint a recycled row or a new track.
- The signed laptop daily APK installed on Galaxy A17 is 2,506,883 bytes,
  SHA256 `ad87caaa200c664221f71b5c1c2e67350dd3b3abfaddb99069690ed5c73e78bb`.
  `assembleDaily`, `testDebugUnitTest` and `lintVitalDaily` passed. The A17
  displayed artwork and a changing real waveform; the temporary test player
  was removed after the run. The install preserved Moo package data and default
  Home. Bounded emulator checks covered art, no-art, privacy, power saving,
  stopped playback and 200% text.
- Physical A17 render samples use four matched three-second active runs: the
  earlier dynamic Canvas Path wave was 27.12-39.66% janky with and without art.
  A one-line A/B was 0% in four warm samples, isolating Path drawing cost.
  The final real wave uses preallocated line segments and Canvas.drawLines:
  artwork on was 13.79% on the first active sample then 1.69%; artwork off
  was 1.69% and 3.39%. A later two-second visual check showed a clear
  five-cycle wave at 12.82% jank; stopped playback rendered zero frames.
  The higher active samples and short duration still limit any broad
  performance or battery-life claim.
- Full `:app:lintDaily` at this media-build checkpoint
  failed with 330 errors and 154 warnings, chiefly missing translations,
  including new media labels. Three media UseKtx advisories remain.
  Vital lint passed on the final build. TalkBack, long battery/thermal runs and
  broad OEM coverage remain open. Source changes remain uncommitted.

===== PREVIOUS UPDATE: FULL-SCREEN BULK EDITOR AND PANELS (2026-09-23) =====

- The Home bulk editor now has one full-screen scrollable list with explicit
  **Home apps** and **Unadded apps** headings. Checked apps remain together at
  the top, unchecked apps follow; tapping checkboxes stages bulk add/remove.
  The editor has no Find apps field. Rename, reorder, Save and Cancel remain.
- Shared settings dialogs and native menu/list surfaces now fill the screen.
  The custom dialog has a programmatic title, heading and button semantics.
  Customize panels and the Home-app row menu have visible Close actions.
- Laptop daily/debug build, unit tests and R8 vital lint pass. Current daily
  APK: 2,497,791 bytes, SHA256
  `da111b23460c829e7970dd534bc8dab4e1b5750ee1e3f3229e8b3e11c444a2fc`.
  This is the laptop APK installed on A17. The Windows mirror APK was built
  from the same source with the same signer but has byte hash
  `1bc342092174e1b7cea430fc0e744321fb2711d77983db0eac407ea84887b3ba`.
  Emulator checks exercised the full-screen editor, text-size dialog and
  Customize panels, including 200% font. UI and bounded accessibility review
  pass; TalkBack and keyboard traversal remain untested. The laptop reconnected,
  and this full-screen build was installed on A17 with package data retained.
  Pullback hash matched the built APK; Moo remains default Home. A17 UI test
  reached both headings and checked/unchecked rows, verified no Find apps,
  and Cancel returned Home. Long-press app menu has a visible Close action.
  The final Home carousel benchmark was 1.46/1.11/1.65% jank across three
  eight-swipe rounds, median 1.46%; idle rendered 0 frames in the sampled
  three seconds. Device load was not captured alongside those runs, so they
  do not establish a performance or battery-life improvement.
- Current full `:app:lintDebug` fails with 326 errors and 173 warnings:
  323 missing translations, one custom-view, and two implicit-intent errors.
  R8 vital lint passes; do not report full lint as green.
- The section below records earlier, superseded editor and device states.
  Do not use its Added/Add tabs or laptop-offline assertions as current design.

===== PREVIOUS UPDATE: FOCUS, PANELS, BULK EDITOR AND FINAL DEVICE HANDOFF (2026-09-23) =====

- Weather condition now centers beneath the temperature, with high/low stacked
  at the right. The Apps-to-Home transition hides the departing drawer before
  its category divider can flash over Home. Apps icons warm in visible browse
  order before the drawer arrives.
- Notifications has centered Filtered/All controls with independent top/bottom
  placement and left/right order. Optional focus-change vibration respects
  Static mode, power saving and the phone's touch-feedback setting.
- One Home app editor separates Added and Add apps into tabs. It supports bulk
  add/remove, search, rename and reorder, with content-sized small lists. The
  active tab stays focusable. Narrow 200% text reserves the dialog action row;
  search and app rows remain visible with the keyboard. Cancel and Save both
  closed the dialog in emulator checks.
- Daily wallpaper downloads now enforce HTTPS, connection/read and total-time
  limits, encoded-byte and pixel bounds, and cleanup. Failed application no
  longer marks that wallpaper as installed.
- Windows mirror build: daily/debug and 16 JVM tests pass, plus R8 vital lint.
  Daily APK is 2,504,763 bytes, SHA256
  `0361c7256f4458ea8f8602d4bd5b6269b58fb1fe58afa56a864d9ffccdd31637`.
  Emulator checks: nine weather cases; all four notification filter placements;
  six phone/tablet/foldable layouts; one/many/empty Home editor states, 200% text
  on small phone and tablet landscape, and keyboard-open search. Android memory
  instrumentation passed. UI/accessibility reviewers cleared the final editor;
  TalkBack and long battery runs remain untested.
- Full `:app:lintDebug` was also run: it fails with 322 errors and 159 warnings.
  Errors are 319 missing translations, one custom AppCompat view warning and two
  implicit external-intent warnings. The latter two actions intentionally open
  Android's Home chooser or an external calendar. R8 vital lint passes. No full
  lint pass or complete localization is claimed.
- An earlier variant of this update was installed directly on the A17 before
  the laptop went offline (SHA256 `af955564dd1d38f08625eb9f59f7fcd33add96bf62f681da86dd7b2d50672212`).
  It showed live weather, immediate Apps icons, no returning divider, no crash,
  and zero sampled idle frames. Home jank rounds were 1.62/2.49/1.06%, median
  1.62%, versus the prior 1.47%; noise is too large to claim a gain. The final
  editor/wallpaper revision above is built and emulator-tested but NOT yet
  installed on A17 because the laptop went offline. Sync source,
  install daily APK with `pm install -r`, check installed hash and phone state,
  then rerun the bounded physical routes once the laptop reconnects.

===== PREVIOUS UPDATE: WEATHER RANGE BESIDE CONDITIONS (2026-09-23) =====

- Weather now has two content-sized columns: icon/temperature with condition
  beneath on the left, and high over low on the right. The whole widget follows
  Home alignment, remains one clickable/spoken item, and hides the range when
  today's forecast is unavailable. Cached single- or double-spaced ranges work.
- Daily/debug build and 16 JVM tests pass. Emulator checked phone, 320 dp,
  200% text, landscape, centered Paper, right alignment, legacy cached spacing,
  location-off and static saver. The settled saver had zero frames in two
  three-second samples. Bounded visual and accessibility reviews found no
  blocker; full TalkBack and RTL interaction remain untested.
- Installed on A17 with live `53° / Clear` and `H:67° / L:47°` in side-by-side
  columns. Optimized daily APK: 2,495,303 bytes, SHA256
  `b5668c0d02841067231e344a5909f03429d1e3f41fdb3523419a6629fefd4ec0`.
  Installed hash matches. Default Home and notifications retained; crash log
  empty; zero sampled idle frames. No uninstall or data clear. After scroll
  testing, the original focused Home app (Calculator) was restored.
- Same-protocol A17 Home jank medians: 1.49% before and 1.47% after; after
  rounds 1.93/1.27/1.47%, with 38 ms p99 in the first. The short samples do
  not establish a performance gain or battery-life change.
- Evidence: laptop `work/phone-weather-column-{before,after}/` and
  `work/weather-column-build.log`; desktop `work/weather-column/` and
  `work/weather-column-phone.png`.

===== PREVIOUS UPDATE: STACKED WEATHER AND ALIGNMENT (2026-09-23) =====

- Weather now reads icon/temperature, high, low, then condition on four visual
  lines. The range remains muted and the full condition remains legible. The
  weather and other widget text follow Home's left/center/right alignment;
  the content-sized row and bounded scroll area remain.
- Daily/debug build and JVM suite pass. Emulator covers left, centered Paper,
  right, small screen, 200% text with reachable overflow, landscape, keyboard
  focus, long-press settings, location-off and saver. Visual and bounded
  accessibility reviewers found no blocker. Full TalkBack/RTL not certified.
- A17 live weather screenshot/readback confirms the four-line order. The
  optimized daily APK is installed, 2,494,711 bytes, SHA256
  `a6f96791174d3aff9d2e88f0de16ee4279a5147c0fb208406d9cdc33847603b1`. Default Home and prior notifications remain;
  crash log empty and zero idle frames/3 seconds. No data clearing occurred.
- A diagnostic debug install timed out but had applied; the optimized daily
  build was restored with `adb shell pm install -r`, and installed hash was
  verified. Further physical checks were read-only.
- Same-protocol Home jank medians: 1.29% before,
  1.09% after. After rounds: 2.02/0.90/1.09%,
  with a 40ms p99 in the first round. Short samples cannot establish a speedup
  or long-term battery behavior. No idle animation added.
- Evidence: laptop `work/phone-weather-reference-before/report.json`,
  `work/phone-weather-stack-readonly/report.json` and `home.png/xml`; desktop
  `work/weather-stack/`, `weather-stack-phone-report.json`.

===== PREVIOUS UPDATE: THREE-LINE WEATHER (2026-09-23) =====

- Matched the supplied lock-screen weather reference within Moo's existing
  text-first style: condition icon + temperature, condition on its own line,
  then muted daily high/low. Condition is full-size and full-contrast; its icon
  matches the temperature scale. No new cards, dependencies, polling or motion.
- Weather size, shared Information size, top alignment, natural-width flow and
  four-widget cap remain. Editing, location-off/loading/failure states, spoken
  description, cached forecasts and stale-range suppression are unchanged.
- Daily/debug builds and 16 JVM tests pass. Emulator verified phone, 320dp,
  200% text with reachable overflow, landscape, centered Paper, settings access,
  location-off and saver/idle behavior. Bounded visual/accessibility review
  found no blocker. Tab reaches weather with a visible focus ring; Enter opens
  Home information. No full TalkBack/RTL certification.
- Installed and hash-readback verified on A17: 2,494,611 bytes, SHA256
  `e2201eb4c3023587ccafa2e9b911fdabcf8458eb1b42f684fa00a2c49fe2e7e6`.
  Live weather and long-press customization exercised. Default Home and existing
  notifications retained; only SCREEN_TIME_LAST_UPDATED changed; no crash log.
- Home benchmark medians 1.47% before / 1.08% after. Three rounds of
  eight alternating 160 ms flings with 450 ms pauses. After samples 1.27/1.08/0.90%,
  p99 medians 28 ms before / 27 ms after. Short runs do not prove a causal speedup.
  Zero idle frames/3 seconds. Package remains 2.49 MB; no battery-life claim.
- Evidence: laptop work/phone-weather-reference{,-before}/report.json;
  fresh-home.xml/png uses a new dump filename and checks successful capture,
  following one transient UiAutomator null-root response in the earlier harness.
  Desktop work/weather-reference/, weather-keyboard/, weather-reference-*.json.

===== PREVIOUS UPDATE: MINIMAL PANELS AND HOME EDITING (2026-09-22) =====

- Settings dropdowns use bounded, scrolling native lists. Newly attached list
  rows receive the selected theme immediately, including Paper. Large system
  text stacks Settings label/value pairs; the header reserves its info target.
- Home: optional light scroll feedback, drag handles in the draft bulk editor,
  short Add/Remove actions, and compact instructions. Save/Cancel and accessible
  move/rename actions remain. Weather now uses temperature plus condition on
  the first line, high/low below; shared widget scale and 16 dp flow spacing.
- Apps: category sections, A-Z, Z-A or recently installed sorting; separate
  icon-only Search entry. Apps and Notifications have no Home/Edit top buttons.
  Hold their title to customize, or use Settings > Home > Customize panels.
- Notifications: only Filtered/All, clear app headings and separators. Filtering
  retains system notifications. The Android-style wave sits inside the matching
  playing media notification, with exact session-token binding. Preview-off
  also removes track text from its accessibility description.
- Audio subscribers share one bounded output-mix capture engine, reusable
  32-value buffer and 20 Hz callbacks. No runtime library added. Paused/hidden/
  detached/saver/motion-off states release capture and remove its entire block.
  Permission denial and unavailable capture have immediate inline explanations.
  This visualizes device output, not isolated per-app audio; the token decides
  which notification may display it. Phone audio permission remains unanswered.
- A17 rejects both native frequent-segment and clock ticks. Opt-in feedback now
  uses a 12 ms TOUCH pulse only when tick support is explicitly absent, with
  a 40 ms minimum interval. System feedback, foreground, motion and saver guards
  remain. Android recorded 21 completed pulses in the final phone run,
  none when disabled. Hardware execution is verified, subjective feel is not.
- Installed optimized daily APK: 2,494,635 bytes. SHA256
  `49a3c3a3b5c4f043bed9dffd5f4f44b3943eb32ae3480379a84cb40b05663e43`.
  Readback hash matches, default Home and notifications retained. Only
  SCREEN_TIME_LAST_UPDATED changed; crash log empty; zero idle frames/3 seconds.
- Daily/debug/test builds and 16 JVM tests pass. Memory/cache instrumentation
  and immediate capture-failure regression pass. Emulator covers real moving
  audio, two simultaneous media rows, session mismatch, privacy, denial/recovery,
  stopping, motion-off and saver. Bulk add/remove/drag and filtering are exercised.
- Layout evidence: phone, 320 dp with 200% text, landscape, Paper tablet at
  130% text. Final native menu option is fully revealed and selected; large
  widget overflow remains reachable. No full TalkBack or RTL certification.
- Matched pre-feature A17 median jank: Home 0.90%, Apps 0.56%. After features:
  Home 1.28% (0.91/1.46/1.28), Apps 0.74% (0.74/0.74/0.55). Home p99 27 ms,
  Apps p99 16 ms median. These small samples do not establish improvement;
  observed medians increased. Measurements precede final menu/editor/haptic
  fallback refinements; haptics were off during the benchmark. No long-duration
  battery-drain or universal-OEM claim. Source remains uncommitted.
- Full lint is not clean: 306 missing-translation errors and 3 existing
  view/intent errors. The added vibration API guard leaves zero NewApi errors.

Evidence: laptop work/phone-minimal-before/, phone-minimal-final/ and
phone-minimal-pulse/; desktop work/minimal-media/, minimal-power-media/,
minimal-remainder/, minimal-layout/, minimal-finish-layout/ and minimal-finish-wide/.
Benchmark protocol remains eight alternating 160 ms flings with 450 ms pauses,
three rounds per surface. The final layout rerun fixes a harness assumption
about the native ListView resource namespace; matching by widget class works
across landscape and AppCompat layouts.

References for interaction choices: https://help.niagaralauncher.app/article/127-edit-favorites
and https://beforelabs.com/faqs. Moo preserves its text-first theme and settings.

## Previous update (2026-09-22): aligned inline Home widgets

- Replaced equal half-width cells with content-sized flow rows, shared top
  alignment, consistent value/label typography and 24 dp horizontal spacing.
  Extra widgets wrap naturally; the group follows Home alignment. Weather
  Small/Medium/Wide controls reserved width, not a separate text scale.
  Information size scales all widget text together. Four-widget cap and bounded
  native scrolling remain; no new runtime dependency, polling or animation.
- Installed and hash-verified on Galaxy A17. Daily APK 2,487,202 bytes; SHA256
  `746ef224ac97595fbc5a0011b3ce7cc67d90ac4cff3ddcd6d49c4c8756fb62a9`.
  Weather long-press settings work; preferences, default Home and existing
  notifications retained. Only SCREEN_TIME_LAST_UPDATED changed. No crash log.
- Daily/debug builds and JVM tests pass. Emulator checks cover two/three/four
  widgets, wide weather, narrow width, 200% text, landscape, centered Paper,
  Ink, weather location-off and scrolling to the final large-text widget.
  Visual and bounded accessibility reviews pass. No TalkBack or RTL runtime
  certification; the attempted RTL fixture did not change direction.
- Phone Home jank: 1.29/1.08/0.90% (median 1.08%), zero idle frames/3 seconds.
  This is a layout regression sample, not evidence of a speed improvement.
  Evidence: work/phone-widget-row/report.json on laptop; desktop
  work/widget-phone-report.json, widget-row-sweep/ and widget-row-extra/.

## Previous update (2026-09-22): compact optimized daily build

- The Galaxy A17 now runs the `daily` build: release R8/resource shrinking,
  non-debuggable, existing `app.olauncher.debug` identity and existing signing key.
  No migration or data clearing. APK 2,488,102 bytes versus 8,838,328 previously
  (71.8% smaller); one DEX, no bundled native libraries. SHA256:
  `b4cd513ed0057c8ecc2a8cce5472a100397f0065d17b1bc55a1532b12a0b338a`.
- Cache bitmaps are capped at 2 MiB by allocation bytes, independent of icon size
  and screen density. UI-hidden trims the current cache to 512 KiB; in-flight
  loads may refill it within the global 2 MiB bound. Background memory pressure
  clears icons and icon-pack maps. Eviction never recycles visible bitmaps.
- Icon preloading cancels when superseded or the drawer stops; cancellation is
  checked between icon loads. Search uses its independent icon preference.
  Pack XML parsers close promptly; atomic map reset never waits for parsing and
  prevents stale parse results from repopulating a cleared map.
- Fixed pre-Android-10 usage access, Android 7 shortcut / pre-8 pin guards, and
  explicit Android 12 blur guards. Badge-off stops counts without unbinding the
  shared notification/media listener. All 16 prior NewApi lint findings cleared.
- Exact daily APK: 11 navigation checks and all six panel customization checks
  pass. Badge-off/new-notification integration passes. Memory instrumentation
  fails on the old APK and passes on the new debug companion. Six JVM suites,
  16 tests, zero failures/errors. See docs/PERFORMANCE.md for complete evidence.
- Emulator lifecycle recovery passes ten navigation/background-return cycles,
  three memory trim levels and three process recreations. Listener access and
  fixture preferences restored. See PERFORMANCE.md for injection constraints.
- Physical Apps median jank 1.32% before / 0.92% after; Home 0.91% after. Three
  rounds of eight alternating 160 ms flings, 450 ms pauses per surface. Five
  process-cold starts: 389/330/369/362/402 ms (median 369 ms), not a matched
  startup-improvement comparison. Idle 0 frames over 3 seconds. No new crash log.
- Final install hash read back; default Home and notifications preserved. A
  temporary matching signed debug install allowed preference readback, then the
  final daily build was reinstalled. Only SCREEN_TIME_LAST_UPDATED changed.
- IMPORTANT: `run-as` is intentionally unavailable on daily. Do not interpret
  that as data loss or clear data. For private-state diagnostics, back up the
  installed APK, install the matching signed debug companion with `install -r`,
  read/back up preferences, then reinstall daily with `install -r`. Never use
  uninstall, clear-data, or raw preference writes on the real phone.
- Full lint is NOT clean: 297 untranslated-string findings and 3 existing
  view/intent findings remain. Runtime checked on A17 and API 36 emulator; older
  APIs have static guard validation, not physical certification. No long-term
  battery or universal-OEM claim. Phone audio permission/test remains unanswered.

## Previous update (2026-09-22): panel customization and real audio visualization

- Settings > Home screen > Customize panels exposes Home, Apps, Search,
  Notifications, Weather and Audio visualizer. Apps/Search/Notifications have an
  Edit action; Home app long-press has Customize Home; weather long-press opens
  its own size/unit settings. Preferences apply immediately and persist.
- Notifications now have Focus, Filtered and All, app headings and separators,
  independent icons/previews/timestamps/compact rows/swipe controls. Hold a row
  to move its app between Focus and Filtered. Filtering does not cancel system
  notifications. Preview-off removes private content from accessibility labels.
- Weather Small/Medium/Wide is independent of the information grid size. Wide
  spans a row; the existing bounded scroll area and four-widget cap remain.
  Unit changes invalidate formatted cache; in-flight results use the current unit.
- Opt-in audio visualizer: Wave/Bars, theme-following/monochrome/theme color,
  three heights, Notifications default location and optional Home placement.
  It uses real Android output-mix samples at up to20Hz, not microphone recording
  or synthetic motion. Native capture is released when hidden, stopped, detached,
  paused, in saver/motion-off, and on Static Home. All active media controllers
  are observed with distinct callbacks, so an already-active player can take over.
  Audio/notification permission recovery and permanent-denial app-settings route
  are explicit. No background audio service, audio files, or frame timers in Moo.
- Dialogs now use opaque theme surfaces, matching text/radios and keyboard focus.
  Large text wraps in settings; notification tab widths fit200% text. Search's
  keyboard toggle is honored on explicit Search entry as well as mode switching.
- Debug and R8 builds pass;16 JVM tests,0 failures/errors/skips. Signed R8 runtime
  gate passes11 checks including all Settings sections, bulk Add/Cancel,
  notification/local visualizer settings, Apps and Search.
- Installed Galaxy A17 APK SHA256:
  `670e6cde319e0c2792718f1e4d1158a54412c0d4b7be2b62d27984faad7069fb`.
  Final backup/certificate/install-r preserved default Home, saved layout and all
  three notifications. Physical local-settings checks pass; only screen-time
  refresh changed during that final check. Idle0 frames/3sec.
- Signed scratch R8 APK SHA256:
  `9675b62e02b67433c12d7cc1b9dc88e3a4d8f234f655d589bd928657ee461e8f`.
- Three-round physical benchmark: Apps1.13/1.12/1.13% jank, Focus1.09/.90/.91%,
  medians1.13%/.91%. Protocol8 alternating160ms flings,450ms pause. This precedes
  only the final notification-tab-width XML adjustment; Home/Apps unchanged.
- Emulator: actual changing waveform; B/A/B handoff with two active sessions
  while Home stays foreground;0 idle frames for Static/saver/motion-off/stopped;
  offline unit invalidation; app filtering retains system notifications. Panel
  checks cover320dp, compact landscape, foldable and200% text, plus Paper/Ink/
  Charcoal/Forest. No long-term battery-drain or universal OEM claim.
- Phone audio capture is still unverified: the user has not answered the request
  to grant audio permission and play a brief test tone. Feature remains off by
  default. Do not silently grant RECORD_AUDIO on the daily phone.
- Evidence: desktop work/panels-unit-results, panels-release-gate.log,
  panels-functional.log, panels-final-checks.log, panels-media-handoff.log (first
  three checks; later combined routing attempt met onboarding),
  panels-filter-check.log, panels-viewport, panels-home-viewport, phone-panels,
  phone-panels-final.log, phone-panels-benchmark/report.json. Initial viewport
  Home screenshots that captured Android Settings are excluded; use the separate
  verified-foreground panels-home-viewport captures. R8 fixture explicitly marks
  onboarding complete after a wallpaper education prompt interrupted its first
  runs. Await every harness's finally/exit before another run uses that device.

## Previous update (2026-09-22): icon size and bulk Home editing

- Appearance > App icon size:20,28,32,40,48dp, shared by Home/Apps/Search;
  existing icon visibility and style stay independent. Default32dp. Bounded cache.
- Home > Edit home apps, or Home app long-press > Edit home apps: searchable
  multiselect Add, selected Remove, tap name to rename/move up/down. Save writes
  all six identity fields and count atomically; Cancel leaves saved Home intact.
  Duplicate rows have independent IDs. Work profiles/shortcuts are labeled;
  colliding targets are qualified. Lists are recycled and have no item animation.
- Landscape keyboard bounds verified from screenshot: search, Calendar result and
  Cancel/Add above IME; selected result, confirmed Add, canceled outer draft.
  Floating dialog uses Activity IME metrics and drops redundant heading in compact
  windows. Listener detaches with dialog. Config recreation retains all three
  draft layers (outer edits, rename text, Add selection), exercised via font scale.
- Fragment-scoped drafts retain main edits, pending rename and Add selections
  through configuration recreation. Explicit Save/Cancel clears the draft.
- Home battery widget shows a left lightning bolt for charging/full on external
  power; unplugged, paused and unknown states omit it. Broadcast-driven, no polling.
- Debug/unit/R8 release build succeeds;13 JVM tests, zero failures/errors/skips.
  Final signed R8 smoke passes Home, bulk Add/Cancel, four settings sections,
  notification panel, Apps and Search. Signed scratch APK SHA256
  `ae118b2a68d8b3c3a48ccffd8c5155cea32f15bc476559475d9189717421e713`.
  Final physical keyboard-search/Cancel preserves Home; idle recheck0 frames/3sec.
- Galaxy A17 final installed debug SHA256:
  `92fc1d8e3dd594cddc68a43306aaca47939a31794f94bf2a8534db314ed358ae`.
  Backup+certificate check+install-r; default launcher and existing notifications
  preserved. Latest baseline has TEN Home apps (user changed it since prior pass).
  Final UI test adds App Manager/Arcticons together and removes them together;
  all original Home identity/order fields restored, only screen-time refresh changes.
- Icon20/48/original32 selected through physical Appearance UI and Apps rendered.
  Emulator tests cover cancel, rename/reorder, bulk add/remove, duplicate selection,
  shortcut/profile preservation, and all four charging states.
- Physical three-round benchmark (8 alternating160ms flings,450ms pause): Apps
  jank1.14/1.33/1.77%, Focus1.29/1.28/1.47%; medians1.33%/1.29%. Zero frames in
  three-second idle sample. This benchmark precedes only editor lifecycle/keyboard-layout fixes;
  no scrolling/rendering code changed afterward. Not a battery-drain measurement.
- Evidence: laptop `work/phone-bulk/report.json`, `phone-icon-size/report.json`,
  `phone-final-benchmark/report.json`, `bulk-final-deploy.log`; desktop
  `work/bulk-verify/`, `bulk-viewport/`, `bulk-rotation/`, `final-unit-results/`.


## Current implementation (2026-09-21, final physical-device pass)

Canonical checkout: `/home/xer0z/Workspace/Dev/Android/Moolauncher` on Laptop,
branch `moo-laptop-continuation`. Desktop is a verified source mirror. Changes
remain uncommitted and unpushed. See `CONTINUE-ON-LAPTOP.md` for SSH/build setup.

=====
IMPLEMENTED BRIEF
=====

- Text-first home, no bottom buttons, compact phone spacing. Separate categorized
  Apps and Search, predictable horizontal navigation and category headings/rules.
- Infinite Focus carousel fills remaining height, one emphasized center app,
  optional outer blur. Off-center tap centers; second tap launches. Tablet Focus
  shows at most nine rows instead of multiple repeated cycles. Static remains a
  finite, flat, sharp, equal-size one-tap list with no home animation.
- Ultra saver uses Static, suppresses transitions, skips icon prewarming and
  pauses foreground weather; switching it off restores the selected preset.
- Home information uses aligned two-column widgets. Tap the date or a widget to
  edit: Small/Medium/Large, up to four widgets plus an independent date. Choices:
  weather, battery/charging, screen time, unlocks, next alarm. All entry paths
  enforce the limit. Alarm follows locale and 12/24-hour settings. Missing usage
  permission and loading remain explicit. Cancel preserves the whole draft.
- Information area caps at200dp (96dp in compact-height windows),400dp wide.
  Accessibility text measures fully and scrolls inside the cap. Value updates
  retain keyboard/accessibility nodes. Static adds no decorative animation.
- Live weather uses a bounded coarse-location request and cancellable HTTP.
  Condition vector, temperature, condition name, local-day high/low; hourly cache
  also checks the forecast timezone/date. Permission, location-off, offline and
  power states are explicit. No coordinates are logged/persisted. Tanner explicitly
  approved enabling phone-wide Location; real readings were exercised.

=====
CURRENT EVIDENCE
=====

- Final installed debug SHA256: `79eec7ed93e45525ed27e884ec50287547ffeaac587dfe175c8900695c19cffd`.
  Physical target SM-S176V / R5GL55YDDNT, Android16,1080x2340,450dpi. In-place
  installs preserved default home and all five existing notification keys.
- Debug, nine JVM tests and R8/resource-shrunk release build pass. Category4,
  shortcut3, forecast-timezone/midnight2. Current XML evidence is copied under
  desktop `work/final-unit-results`; build logs live in laptop work/.
- Physical swipes, tap-to-focus/second-tap launch, Add ninth/Remove restoring the
  original eight, Static persistence and ultra-saver restoration pass.
- Physical widgets: all three sizes save, Cancel preserves state, alarm adds and
  removes, original selection restored. Latest three-round carousel sample:
  1.83/1.85/3.39% jank, median1.85%; zero idle frames/3sec. Earlier same-protocol
  drawer median1.32% and carousel1.44% precede widgets. Portrait behavior is
  unchanged by final compact-window/tablet-only refinements. These are sampled
  gfxinfo counts, not battery-life or end-to-end latency claims.
- Emulator widget checks cover small phone,A17,tablet,foldable,200%text,actual
  compact-wide1280x720 and tablet-wide2560x1600. All sizes exercised. Explicit
  interaction gate verifies editor/legacy caps, Cancel, battery update retaining
  keyboard focus, and enlarged text scrolling within the cap.
- Tablet Focus portrait/landscape/large-font test verifies bounded row count,
  off-center tap centering, then exact app launch. Phone/Static spacing retained.
- Current R8 runtime gate: Home, all four Settings sections, notification panel,
  Apps and Search. Edge gate: empty/1/2/12 apps, motion-off direct launch,
  keyboard menu and long weather text. Design/accessibility/reality reviews pass.
- Samsung ADB screenshots sometimes omit unchanged pixels. Tanner explicitly
  confirmed everything stays visible on the physical screen. Complete captures
  were reviewed; the capture-tool issue itself is not claimed fixed.

Evidence: desktop `work/phone-widget-report.json`, `work/widget-interactions/`,
`work/widget-remaining/` (foldable/large-font), `work/widget-wide/`,
`work/tablet-focus-final/`, `work/release-final-gate/`, `work/edge-cases.log`.
The first widget sweep's nominal landscape and last stale foldable entry are
excluded; fresh actual-dimension tests replace them. Original phone/Static and
notification-panel evidence remains in work/phone-* and prior validation folders.

No long-term battery drain, stability soak, universal OEM compatibility or current
phone cold-start improvement is claimed. The historical540ms vs448ms cold-start
result was seven paired emulator runs on the earlier change. Source remains
uncommitted; publishing and app identity changes are outside this task.

The section below is historical baseline context, not current build certification.

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

## 2026-09-24 Settings, weather, ordering, privacy, and A17 haptics

The current desktop mirror contains the finished implementation and signed Daily
APK installed/hash-matched on the A17. The comprehensive checklist, tested
screens, privacy map, platform sources, and honest before/after metrics are in
`docs/VERIFICATION-2026-09-24.md`. The 34-test Debug and Daily JVM suites pass,
Android instrumentation passes, and both lint reports contain zero issues. The
phone's Focus vibration is On and produced 12 ms scroll ticks; Off suppressed
those ticks in a matched check. No scrolling speedup is claimed because the
final p99 frame times were slightly higher in the short A17 samples.

The canonical laptop still timed out over SSH. Sync this mirror
to `/home/xer0z/Workspace/Dev/Android/Moolauncher` only after checking its
working tree for independent edits. Do not overwrite that tree blindly. The
phone is already on the verified Daily APK; do not uninstall it or clear data.

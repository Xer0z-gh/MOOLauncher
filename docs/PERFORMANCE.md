# Performance and stability validation

===== CURRENT BUILD: FINAL UI, CUSTOMIZATION AND BACKEND PASS (2026-09-27) =====

59 JVM tests passed; Debug and Daily lint reported "No issues found." Paired A17 comparisons alternated installs (A, B, A, B) at the same ART `speed` state after a warm-up. Base = the 09-26 build (2,298,645 bytes, D9A08774...E43B31). New = this round's candidates: final2 for cold start, Home press, drawer, Settings and memory; final3 (final2 plus the icon-pack cache) for the warm return and the cache pair. The shipped build's own pair against final3 is below. Cold starts are validated (a sample counts only if a new process started; rejected samples are reported). Phone temperature and background load were not recorded.

Cold start, 3 alternating pairs, 7 starts each: 452 -> 421 ms median (n=21 each, none rejected; p25 427 -> 413, p75 492 -> 446); the new build was faster in every pair (452/433/459 -> 435/419/419). Icon-pack cache alone, same method: 439 -> 423 ms (n=15 / 16; 11 samples rejected, 6 and 5), faster in every pair. The same final2 APK measured 421 ms in the first session and 439 ms in the second, so compare within a session, not across. The cache: IconPack.parse was 330.5 ms on a background thread in one traced cold start; with the saved map, IconPack.cached took 1.2 ms in each of two traced cold starts and no parse ran; drawer screenshots before and after were pixel-identical (18,495 lit pixels in the icon column).

Home press on Home, 8 presses, 2 rounds: main thread 163.6/148.7 -> 103.6/123.2 ms, binder transactions 16/16 -> 0/0 (44 ms each round), worst frame 8.9/8.4 -> 9.8/9.8 ms (under the 11.1 ms budget).

Warm return from Settings, 5 per trace, 2 rounds: binder transactions 94/96 -> 82/82; performStart to the first real frame, median of 10 returns, ~44.5 -> ~43.0 ms (unchanged). Slightly worse on frames: the new build draws 1-2 extra small frames per return (frames 25/23 -> 35/34) because its first frame lands earlier, before the window is visible, so the window manager asks for another ("Start draw after previous draw not visible"), and one draw follows the window-focus change. UI frames over 11 ms 10/11 -> 11/14, render-thread frames over 11 ms 3/2 -> 6/6, worst render-thread frame 20.0/13.7 -> 26.3/34.9 ms, worst UI frame 40.1/31.5 -> 31.1/35.2 ms, main thread 468/442 -> 441/537 ms.

Drawer, open plus 6 flings, 2 rounds: inflation 41.2/41.3 -> 37.7/34.9 ms, worst frame 21.8/16.5 -> 19.1/17.4 ms, render-thread frames over 11 ms 6/10 -> 5/5. Settings, hub plus three sections twice, 2 rounds: inflation 277/266 -> 268/271 ms (unchanged); per section open, Settings.inflate 28.9 ms, arrange 2.5, populate 1.7, style 1.1, label 0.8 (two passes of 0.4), listen 0.1. Memory after a cold start and a drawer pass, 3 rounds: Java heap median 10.70 -> 10.58 MB, total PSS 105.5 -> 106.1 MB (noise).

Shipped build (final5: final3 plus the second review's fixes, the wallpaper-colour cache held while its listener is registered, and the icon map saved for this profile only) against final3, 2 rounds each: drawer inflation 43.3/44.1 -> 41.8/36.2 ms, worst frame 19.9/21.7 -> 19.6/17.1 ms, render-thread frames over 11 ms 6/7 -> 5/6; warm return binder transactions 82/82 -> 82/82, frames 34/34 -> 34/32, performStart to first frame median of 10 returns 41.6 -> 39.9 ms. No regression from the later changes, including the dark Apps scrim's wallpaper question.

Limits: phone temperature and background load were not recorded. The warm-return pair was re-run after a Play Protect prompt blocked an install in the first attempt. No battery-hour, thermal or unplugged-endurance measurement was made.

===== PREVIOUS BUILD: MEASURED PERFORMANCE PASS AND ULTRA SAVER (2026-09-26) =====

Signed Daily APK 2,298,645 bytes, SHA256 D9A087741634EDF3536C0235B0AAD2A3FEF36E28EC493BFE98B1A48EE2E43B31; the A17's installed copy hashed identically. 43 JVM tests passed; Debug and Daily lint reported zero issues. Paired comparisons below alternated installs on the A17 (A, B, A, B) at the same ART `speed` state after a warm-up, measured with Perfetto (trace_processor v51.2) or validated cold starts (a sample counts only if a new process started). Numbers marked "single trace" or "unpaired" were not taken that way. Phone temperature and background load were not recorded. Harnesses: D:\Workspace\Ops\Moo-Perf-Tools.

Paired. Home scroll, 8 flings, 2 rounds: offscreen-layer draws 779/792 -> 40/40 per run, their time 67.3/61.0 -> 3.7/3.8 ms, layer flushing 282.3/300.7 -> 23.4/22.7 ms, UI frames over 11.1 ms 17.6/17.7% -> 3.7/6.8% (focus label: hasOverlappingRendering false, scale by render properties). Cold start, 14 validated starts per build: median 475.5 -> 452 ms. Home while on Home, 8 presses, 2 rounds: worst frame 12.7/12.0 -> 7.8/7.4 ms, binder calls 24 -> 16. Returns from Settings, 5 per trace, 2 rounds: main thread 486/508 -> 481/470 ms, a marginal change. Drawer open plus 6 flings, 2 rounds: inflation 102/99 -> 41/40 ms, worst frame 60.8/48.7 -> 18.2/20.0 ms. Memory after a cold start and a drawer pass with Arcticons, 3 rounds: Java heap 12,812/12,412/12,444 -> 10,796/10,776/10,884 KB, total PSS median 108.3 -> 105.0 MB. Emulator, same trace slice on both builds: full app-list scans while another app is in front and three packages are installed or removed, 5 (1.8 s) -> 0, with 1 on return.

Single trace or unpaired. bindApplication 110.8 ms (pre-session) -> 67.9 (initializers removed) -> 65.7 (final), one trace each, taken hours apart; only ProfileInstallerInitializer is left in the final APK. The same traces' total startup was 797.8 -> 616.0 -> 709.3 ms, which shows how noisy one trace is. The final APK's own validated cold starts: 440 ms median (431-449, n=6), not paired with a before run.

Ultra saver on the A17: 90 -> 60 Hz while saving and back to 90 after, wallpaper layer dropped on Home only, touch boost off and restored. The black-Home paint is proven on the emulator (Home background 255 -> 0 -> 255); on Tanner's A17 the Home background is already black with the saver off, so that check cannot tell the states apart there.

Limits: the visualizer frame cap is proven by RibbonFrameCapTest only (no media playback was run). The chat-app case of the app-list change (shortcut updates for unpinned shortcuts no longer trigger a scan) is reasoned from the code; the measurement installed and removed an app. No battery-hour, thermal or unplugged-endurance measurement was made; lower frame, scan and binder counts are the evidence, not a battery claim. Screen time and unlocks are off on Tanner's A17, so their rewrite was checked on the emulator (identical 3m / 0 unlocks before and after; the widget-toggle regression the review found now shows a value, where the pre-fix build showed Loading).

===== PRECEDING ALIGNED WIDGET AND FOCUS TICK BUILD (2026-09-25) =====

The signed Daily APK is 2,506,267 bytes, SHA256 7653B8CFDB5B1E26BD5EEA5EE0EC01F6D6618096DCAF6397177012383728B550; the A17 installed copy matched exactly. Full Debug/Daily lint reported zero issues under existing scoped exclusions, and 34 Debug plus 34 Daily JVM tests passed. The final A17 frame method used three rounds of eight alternating 110ms Home swipes with 350ms pauses, resetting gfxinfo per round while Moo remained foreground. Frames were 517/528/534, janky 8/7/7 (1.55/1.33/1.31%), and p99 29/27/28ms. The first immediate idle interval rendered 69 frames while the final scroll settled; after two seconds and another reset the three-second idle interval rendered zero. One post-run PSS snapshot was 132,597 KiB; sampled logs held no Moo fatal or ANR. The earlier weather-only APK has no matched scroll benchmark, and the intervening widget candidate used a different load. These values are regression measurements, not evidence of a causal speedup, universal smoothness or lower battery drain.

On the A17, a 700ms focus swipe before the haptic fix crossed about five app positions and yielded zero new 12ms motor records because queued ticks were superseded. On the exact final APK, the same swipe yielded five new 30ms TOUCH motor records. A preceding candidate's On/Off control produced motor events when On and zero when Off; the preference was restored On. Physical motor activation is established, but Tanner has not yet judged its feel in hand. Android's SEGMENT_TICK is a platform feedback constant for moving among discrete choices on API 34+, and the general haptics guidance advises checking system touch-feedback state and treating one-shot vibrations as a fallback. The A17 reported no optimized EFFECT_TICK, so Moo uses the timed fallback only there. Sources: https://developer.android.com/reference/android/view/HapticFeedbackConstants and https://developer.android.com/develop/ui/views/haptics/haptics-apis .

===== PRECEDING REFERENCE WEATHER BUILD (2026-09-25) =====

The installed A17 Daily APK is 2,505,063 bytes, SHA256 E57574D1F6A126D976B1CA090A251AC665B497881E39AC9D086E0AFD6232D517, with an identical installed base.apk. The new three-line weather presentation, day/night icon refresh, and English speech language span passed 34 Debug and 34 Daily JVM tests, full Debug/Daily lint with zero issues under scoped exclusions, and the focused emulator icon transition check. It was visually checked on the A17 and at phone, 2x-text narrow, and wide emulator sizes with short and longer weather conditions. Scroll, PSS, cold-start, and battery measurements below belong to the preceding APK. Settled A17 Home rendered zero frames over a guarded three-second gfxinfo sample. No matched before/after speed or battery measurement is available for this presentation update.

===== PRECEDING DESKTOP DAILY AND A17 CHECK (2026-09-24) =====

The preceding signed, non-debuggable desktop Daily APK was 2,505,687 bytes, SHA256 `3EAB8D25CAAD3A2CD3D22C8F632BA28B3C5FBE99D615C18626C53EF6017B6C0C`. The A17 installed base.apk matched that hash, and Moo remained default Home with data preserved. Both JVM variants ran 34 passing tests; Debug and Daily full lint reported zero issues under documented scoped exclusions; matching Debug instrumentation passed on the isolated emulator.

On this exact APK, three eight-swipe A17 Home rounds rendered 550/555/552 frames, with 10/8/9 janky and p99 30/28/30 ms. Apps rendered 458/461/452 frames, with 6/6/4 janky and p99 18/19/17 ms. Settled Home drew zero frames in three seconds; the checked crash buffer had no Moo entry. The focus-vibration preference was On, unlike the earlier matched before/after comparison below, so this is a regression check, not a controlled speedup claim. One post-route TOTAL PSS reading was 158,397 KiB without a matched baseline. Battery energy, long-duration thermal behavior, A17 process-cold startup, counted before/after network requests, and app-private storage size remain unmeasured.

===== PREVIOUS VERIFIED BUILD (2026-09-24) =====

The previous signed Daily APK was 2,504,687 bytes and the installed A17 copy matched SHA256 `17221BD86103E6720DD2D0FCFC3FADD48564E812A4C872DA9EA8A9B8C5BF15DE`. Debug/Daily each passed 34 JVM tests, both lint reports were empty, and isolated emulator instrumentation passed. Home focus vibration produced 12 ms motor ticks when enabled and none in the same Off check; it was restored On.

Matched three-round A17 Daily swipe receipts are in `VERIFICATION-2026-09-24.md`. Home p99 changed from 26/28/27 to 28/30/31 ms; Apps p99 changed from 15/16/16 to 18/18/17 ms. Absolute janky-frame counts were similar, so this work does not establish a scrolling speedup. API 36 emulator process-cold startup median changed from 1,751 to 1,500 ms with the same command; A17 cold start was not measurable because the launcher role auto-restarted it. Settled A17 Home rendered zero frames over three seconds. A17 battery energy and matched memory change remain unmeasured. Sections below are historical build receipts, not measurements of the latest APK.

===== HISTORICAL UPDATE: POWER-SAVER INTEGRATION (2026-09-24) =====

Final signed Daily APK: 2,497,615 bytes, SHA256 `a1d86ef461a9be8149eec61c66aedf59d8e3e493a83791827d1cff59f715b95c`. The A17
installed copy matched this hash. Laptop signed build, 25 JVM tests and full
Debug/Daily lint passed with zero failures or emitted findings; Kotlin emitted
no new warnings. Exact-final APK on an isolated API 36 emulator passed
Android Power Saver Off -> forced Ultra On -> Off, with the settings row
enabled/disabled and Switch checked=false/true as expected. Saver-on Home
rendered 0 frames in a settled three-second sample. The USB-connected A17
rejected a guarded Power Saver-on request; its initial off state was restored.
External Settings and sleep/wake returned the emulator to Home, so the
retained-Power-section `onResume` refresh remains source-reviewed only.

Exact-final A17 Home carousel, three eight-swipe rounds: 572/562/575 frames,
13/10/10 janky (2.27/1.78/1.74%, median 1.78%), p99 34/30/30 ms. Idle
rendered 0 frames/3 seconds; PSS 134,078 KiB. The configured left swipe
opened Apps. Three further eight-swipe rounds: 506/512/519 frames, 5/8/7
janky (0.99/1.56/1.35%, median 1.35%), p99 20/24/17 ms. Post-Apps idle
again rendered 0 frames/3 seconds; PSS 125,733 KiB. Moo stayed foreground;
the checked crash buffer had no Moo entry. Thermal status was 1 after the
Apps run. The interrupted first Apps attempt was excluded. Earlier APKs
and workloads had different medians; these are bounded samples, not a causal
speedup or battery-life claim. Evidence: laptop
`work/phone-saver-private-final-home.json`,
`work/saver-private-final-apps-report.json` and
`work/saver-private-cache-build.log`.

The app list has a five-minute in-process cache; Private Space enumeration
also keys on profile handle and lock state, and profile broadcasts force a
refresh. Optional usage scans stop while invisible; active charging telemetry
is rate-limited; saver cancels icon warmup and defers wallpaper fetch/decode.
Unplugged battery drain, live TalkBack, long-duration paging/thermal behavior
and wider OEM/older-Android coverage remain unverified.

===== PREVIOUS UPDATE: CLEAR ALL FOOTER (2026-09-24) =====

Signed Daily APK: 2,496,447 bytes, SHA256
`3fb052b0ff09e5e2ff46e06f17079388069a7083e44f45a7279815ef6170141f`.
The A17 installed copy matched byte-for-byte and Moo retained default Home.
Laptop build, 25 JVM tests and full Debug/Daily lint succeeded; both lint
XML reports contain zero issues. The final A17 UI tree exposed Clear all as
a clickable and focusable Button with bounds [0,2070][1080,2205].
Both All and Filtered were selected and restored. The layout-equivalent
preceding build verified top and bottom filter placement around the footer;
the final code change affects key selection only.

Clear all uses a fresh off-main notification snapshot and current view to
avoid stale cross-filter keys. Active media and nonclearable items are
excluded; unknown media playback state is treated conservatively. The
destructive click was exercised on an isolated API 36 emulator,
not on the user's real notifications. With the exact final APK, an enabled
listener and 12 test/system records, tapping Clear all left only the
protected Android record; the action then vanished while the panel stayed
open. On the exact A17 APK, three eight-swipe Notifications runs rendered
266/255/256 frames with zero janky frames and p99 15/16/16 ms. The
Home/Apps benchmark below is from the preceding APK with unchanged paths;
no causal performance improvement is attributed to this footer. These
short USB-powered runs cannot establish battery-hour or long-run behavior.

===== PREVIOUS UPDATE: EXACT A17 WEATHER AND RETURN REGRESSION (2026-09-24) =====

The signed Daily APK is 2,496,299 bytes, SHA256
`03642d468617f4c340eba270643c4d26996297ddef3769e648861b89f05bff23`.
The A17 installed copy matched byte-for-byte and Moo remained default Home.
A final formatting-only rebuild retained this hash. Laptop
`:app:assembleDaily :app:testDebugUnitTest :app:lintDebug :app:lintDaily`
succeeded: 25 JVM tests, zero failures/errors, zero findings in each full-lint
XML report. The A17 crash buffer had no Moo entry.

An intermittent 854-pixel horizontal bar at y906-907 appeared in 7-8 of ten
immediate Apps-to-Home captures. Disabling blur did not change it; disabling
`WaveView.onDraw` eliminated it. The production fix gates waveform drawing
on current PLAYING state and clears that state on stop. With the waveform
restored, ten exact-APK return captures had no bar, while soft-edge Focus
remained enabled. The media fixture then showed a pinned top card and
waveform crop changes in three successive A17 frames. It rendered 188
frames over two seconds of playback and zero over three seconds after stop;
the fixture was uninstalled. This is a direct playback regression check,
not a claim about every media app or audio route.

Three eight-swipe rounds on the exact APK: Apps jank 1.11/0.55/0.37%
(median 0.55%, p99 17/16/16 ms), Home carousel 1.47/1.28/1.11%
(median 1.28%, p99 30/26/25 ms). Three-second idle samples were 0 and
1 frames, respectively; one isolated frame has no meaningful jank rate.
PSS snapshots were 125,989 KiB after Apps and 155,002 KiB after Home.
The earlier installed candidate showed aligned live weather, configurable
centered Filtered/All and populated first-viewport Apps icons; final code
changes after those checks affected only waveform/formatting. USB power,
short duration and one A17 limit claims about battery, long-run stability,
other OEMs and older Android runtimes.

===== PREVIOUS UPDATE: PINNED MEDIA AND CHARGE ESTIMATE (2026-09-24) =====

Final signed Daily APK 2,494,455 bytes, SHA256
`183b3f50692aca0405b44d17621af7c9982990293adac1f72361b9a70e83e731`,
was installed on A17 and pulled back byte-identical. The laptop build,
25 JVM tests and full Debug/Daily lint passed; both lint XML reports have
zero issues. Home idle rendered zero frames in a three-second sample after
installation.

The immediately preceding installed candidate used the same pinned media
layout: physical A17 player card bounds stayed `[0,235,1080,755]` after
four notification-list swipes and switching All to Filtered and back. It
rendered 278 active frames in three seconds with 0% reported jank. The
exact-final build changed the media-only empty-state logic, polite status
announcement and pinned edge-swipe handling. Google Play Protect intercepted
a disposable-player reinstall before those exact-final physical checks, so
the candidate sample is not a measurement of those last changes. The player
was absent after cleanup; Home and the default role were intact.

Charging power is derived from battery-side `CURRENT_NOW` in microamps
and broadcast battery voltage in millivolts. It is sampled only on visible
Home while active charging, at most every 15 seconds; invalid values are
hidden. It is not charger input power. On the A17, battery protection paused
charging at 85%; Home displayed the ordinary battery caption with no false
watts. Positive charging telemetry and battery-drain effects were not
measured. A 15-second active-only read is a bounded polling cost, not a
measured power saving.

===== PREVIOUS UPDATE: FINAL SIGNED A17 SWIPES AND SOAK (2026-09-24) =====

The signed Daily APK is 2,493,203 bytes, SHA256
`b296c95fcbea33cd4dec76379535e58c8e5f7f3bd77a096a7dc3b7c4732046d6`.
It was installed in place on Galaxy A17 SM-S176V; a pulled APK matched the
hash and Moo remained default Home. Laptop signed build, 20 JVM tests, full
Debug/Daily lint passed. Both full-lint text reports say `No issues found`
under scoped exceptions; `lintVitalDaily` was `SKIPPED`.

Three eight-swipe rounds on this exact APK completed without route failure.
Home jank was 0.97/0.70/0.71%, median 0.71%, with p99 26/28/27 ms. Apps
jank was 0.27/0.44/0.27%, median 0.27%, with p99 15/16/16 ms. Settled Home
rendered zero frames over three seconds, thermal status was 0, and the
current process log slice had no Moo fatal or ANR line. Run-to-run load was
not controlled, so the lower medians than 2026-09-23 are not attributed to a
specific code change.

The preceding APK had identical Home, Apps and media code, then underwent a
309-second, 16-cycle Home/Apps/Notifications soak: 17 samples, one PID, no
Moo foreground loss or process exit, thermal status 0, battery temperature 24.3-24.7 C. PSS began
at 147,877 KB, briefly peaked at 170,356 KB after warmup, and the final five
samples stayed within 158,364-158,368 KB. Swap PSS rose from 16,199 to
42,702 KB across the run, so the late PSS plateau does not rule out paging
or longer-run memory pressure. The final APK changed only the
shortcut confirmation label sanitizer; its build and phone interaction test
passed. USB power held the battery at 85%, so this cannot show battery life.

A real shortcut publisher fixture tested the full-screen confirmation on the
final APK. A U+2028/U+2029 label was normalized, the verified publisher package
remained visible, Cancel pinned zero and Add pinned one. The disposable
publisher was uninstalled. Hardware-key focus moved through Home apps, exited
in reverse to Weather, and completed touch restored the repeating carousel.
Live TalkBack, unplugged drain, longer thermal endurance and other OEM/older
Android devices remain unverified. English fallback remains incomplete
localization. Earlier checkpoints below are historical.

===== PREVIOUS UPDATE: FINAL SIGNED A17 REGRESSION (2026-09-23) =====

- Signed Linux laptop Daily APK: 2,491,147 bytes, SHA256
  `b374f10eeb91f271aa98d278294ce84cebc2f085ed5c93060b245af26b55d712`.
  In-place A17 install and pullback hash matched; package data and Moo's default
  Home role were retained. This supersedes the pending physical verification
  in the earlier lint checkpoint.
- Windows integrated `assembleDebug`, `assembleDaily`, `testDebugUnitTest`,
  `lintDebug` and `lintDaily` returned `BUILD SUCCESSFUL`; laptop signed Daily,
  JVM tests and full lint also returned `BUILD SUCCESSFUL`. Gradle marked
  `:app:lintVitalDaily SKIPPED`, so that task did not execute. Both full-lint
  reports read `No issues found.` That means zero emitted findings under the
  documented
  narrow exceptions, not complete localization or all-device certification.
- A17 media Wave, with matching artwork and genuine output-mix capture,
  rendered 278 frames/three seconds, 0 janky frames, p99 7 ms. Raw preset:
  168 frames, 0 janky, p99 8 ms. Stopped playback: 0 frames/three seconds.
  Captured card crops passed artwork, moving played region, stationary tail,
  advancing playhead and Raw-trace checks with warnings treated as errors.
  The output-mix waveform is polled off-main at about 60 Hz while active; it
  releases capture when hidden, paused, motion-off or in saver mode. These
  results replace the earlier Path/line-renderer benchmarks as current phone
  evidence. The test player was uninstalled and visualizer style restored.
- A17 Home jank across three eight-swipe rounds: 0.90/1.48/1.47%, median
  1.47%. Apps: 1.11/1.10/0.74%, median 1.10%. Settled Home idle: 0 frames in
  three seconds. Physical UI routes covered Home's new full-screen editor,
  bulk sections, seven Settings categories, Filtered/All and bidirectional
  Home/Apps/Notifications swipes. Notification-list hash before/after matched.
- In five forced process removals, Android auto-relaunched Moo as default Home.
  ActivityTaskManager `Displayed` times were 532/426/449/404/397 ms, median
  426 ms. Separate `am start -W` calls returned 0 ms after Home had already
  relaunched, so those are warm calls, not cold-start evidence. A prior
  369 ms median used a different build/load; no causal startup gain is claimed.
- This is bounded regression evidence on one A17. No long battery drain,
  thermal soak, every-OEM/older-Android test, or live TalkBack and keyboard
  certification was performed. Do not infer a battery-hour gain or universal
  smoothness from these samples. Earlier measurements below are historical.

===== PREVIOUS UPDATE: FULL LINT CLEANUP CHECKPOINT (2026-09-23) =====

- Windows `:app:lintDebug :app:lintDaily :app:testDebugUnitTest --offline`
  passed. Both `app/build/reports/lint-results-{debug,daily}.txt` reports
  read `No issues found.` The prior media checkpoint emitted 330 errors and
  154 warnings. At this earlier checkpoint the lint-clean source had not yet been
  verified as a signed laptop APK on Galaxy A17. The current section above
  records the final installed APK and frame samples.
- 327 existing English-only strings are explicitly `translatable=false`
  because they lack translations in the 29 locale sets; 98 unused
  definitions/assets were removed. This
  preserves existing English fallback rather than completing translation.
  `app/lint.xml` limits exceptions to the existing Hebrew resource qualifier,
  the current rounded-icon density path, and tested pinned dependency/tool
  versions. Other source-local exceptions preserve existing visuals and
  gestures. `No issues found` means zero emitted lint findings with these
  documented exceptions, not zero remaining product debt.
- Compiler deprecation cleanup and final build/device regression remain in
  progress. Settings virtualization, localization, TalkBack, long battery/
  thermal runs and broad Android/OEM coverage are not certified by lint.

===== PREVIOUS UPDATE: REAL MEDIA WAVEFORM ON GALAXY A17 (2026-09-23) =====

- Media-feature signed laptop daily APK: 2,506,883 bytes, SHA256
  `ad87caaa200c664221f71b5c1c2e67350dd3b3abfaddb99069690ed5c73e78bb`.
  Installed in place on SM-S176V with package data and default Home retained.
  `assembleDaily`, `testDebugUnitTest` and `lintVitalDaily` passed. Emulator
  exercised artwork, no artwork, hidden previews, playback stop, motion-off,
  ultra saver and 200% text. Physical A17 showed a changing real waveform over
  actual output audio and the matching album art; the temporary player was removed.
- Four three-second warm active A17 samples with dynamic `Canvas.drawPath`:
  artwork on 33.90% and 30.51% jank; artwork off 39.66% and 27.12%.
  Disabling artwork did not remove the cost. A temporary flat-line A/B returned
  0% jank in four warm samples, then was removed. Final signed output-mix wave
  uses reused line coordinates and `Canvas.drawLines`, preserving live shape:
  artwork on 13.79% on the first active run and 1.69% after warming; artwork off
  1.69% and 3.39%. A later two-second visual check showed a clear
  five-cycle wave but had 12.82% jank. Stopped playback: zero frames in the
  settled sample.
  The A17 is a 90 Hz phone. The higher active results and short samples mean
  this is a bounded rendering improvement, not sustained smoothness or battery
  endurance certification.
- One bounded output-mix Visualizer serves visible rows at up to 20 Hz; the
  current wave derives 128 signed positions from up to 1024 capture bytes.
  Results are posted to main in a reused buffer. Session-token matching governs
  placement, and multiple simultaneous playing sessions suppress ambiguous
  output-mix attribution. Static/motion-off and saver states release capture.
  Artwork is separately cancellable, pixel/byte bounded and preview-gated.
- At this media-build checkpoint, full `:app:lintDaily` failed with
  330 errors/154 warnings, principally MissingTranslation, including new media
  labels. Three media UseKtx advisories are included in the warning total.
  Final vital lint is green. Full TalkBack, older OEM audio paths, thermal soak
  and battery drain were not measured. No universal optimization claim follows.

===== PREVIOUS UPDATE: FULL-SCREEN DIALOG VALIDATION (2026-09-23) =====

- Current laptop daily/debug build, JVM tests and R8 vital lint pass. Current
  daily APK is 2,497,791 bytes, SHA256
  `da111b23460c829e7970dd534bc8dab4e1b5750ee1e3f3229e8b3e11c444a2fc`.
  This is the laptop APK installed on A17; the same-source, same-signer
  Windows mirror build has a different byte hash (1bc34209...).
  Full-screen
  Home editor and custom text-size dialog opened and closed in the emulator;
  the editor exposed **Home apps** and **Unadded apps**, supported bulk
  checkbox changes, and kept Save/Cancel usable. The newest APK is now
  installed/hash-checked on A17. The phone-side UI route passed both category
  headings, checked/unchecked rows, no Find apps, visible Home-menu Close, and
  Cancel returning Home. Three eight-swipe A17 carousel rounds gave
  1.46/1.11/1.65% jank (median 1.46%); device load was not recorded with the
  swipes. A settled three-second idle sample rendered zero frames,
  and the checked crash-log slice had zero Moo fatal lines. Drawer benchmark
  completed two rounds at 0.93/0.75% but its third swipe navigated away, so
  no drawer median is claimed. This is bounded regression evidence, not proof
  of a performance or battery-life improvement.
- Full `:app:lintDebug` was rerun on this build and fails with 326 errors and
  173 warnings, principally 323 missing translations. Vital lint passes.
- These bounded interaction checks do not prove battery-life, thermal or
  broad Android/OEM performance gains. The earlier A17 jank samples below
  showed noise; no speedup claim is warranted. Full lint remains red.

===== PREVIOUS UPDATE: BULK EDITOR, APP WARMUP AND DEVICE LIMIT (2026-09-23) =====

- Latest Windows daily/debug build and 16 JVM tests pass. R8 vital lint and
  emulator memory instrumentation pass. Final daily APK: 2,504,763 bytes,
  SHA256 `0361c7256f4458ea8f8602d4bd5b6269b58fb1fe58afa56a864d9ffccdd31637`.
- Full `:app:lintDebug` fails with 322 errors/159 warnings: 319 untranslated
  strings plus one AppCompat custom-view and two implicit-intent findings. The
  latter intent paths invoke Android Home selection and an external calendar.
  This is existing release debt; passing vital lint is a narrower gate.
- Six form factors, nine weather states, four notification filter layouts,
  immediate drawer icon rendering, and editor empty/one/many, 200% text,
  landscape and keyboard states passed bounded emulator checks. The final
  editor was visually inspected and its Cancel/Save actions tapped at 200%.
- The preceding physical A17 build (SHA256 `af955564dd1d38f08625eb9f59f7fcd33add96bf62f681da86dd7b2d50672212`)
  showed live weather, immediate browse icons, zero sampled idle frames and no
  crash. Three Home focus runs had 1.62/2.49/1.06% jank, median 1.62% versus
  a previous 1.47%. These short runs do not prove an improvement.
- Latest editor and wallpaper-bounds revision was not yet installed on A17:
  the USB-hosting laptop went offline. Do not treat emulator success as physical
  phone verification. No battery-hour, thermal-soak or all-OEM claim is made.

===== PREVIOUS UPDATE: WEATHER RANGE BESIDE CONDITIONS (2026-09-23) =====

- Weather changed from four text lines to two natural-width columns. The
  temperature and condition share the first column; high and low stack in the
  second. No animation, polling, library, or background work was added.
- Daily/debug build and 16 JVM tests pass. Seven emulator layouts/forecast
  formats, location-off and static saver pass. After settling, saver rendered
  zero frames in two three-second samples. The 200% text layout retains the
  weather columns and scroll access to lower widgets.
- A17 optimized APK installed and hash-readback verified: 2,495,303 bytes,
  SHA256 `b5668c0d02841067231e344a5909f03429d1e3f41fdb3523419a6629fefd4ec0`.
  Live weather is `53° / Clear` left and `H:67° / L:47°` right. Default Home
  and existing notifications retained, empty crash log, zero idle frames in
  the sampled three seconds. No uninstall or data clear.
- Same three-round, eight-swipe Home protocol: before 1.49/1.65/1.10% jank
  (median 1.49%); after 1.93/1.27/1.47% (median 1.47%). First after round
  p99 was 38 ms. This is regression evidence, not proof of a speedup. Battery
  hours and long-term stability were not measured.
- Evidence: laptop `work/phone-weather-column-{before,after}/report.json`,
  after `home.png/xml` and installed APK hash, `work/weather-column-build.log`;
  desktop `work/weather-column/` and `work/weather-column-phone.png`.

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

===== BUILD AND INSTALL =====

Use `bash tools/build-laptop.sh --offline :app:assembleDaily` for the phone's
optimized daily artifact. It inherits release optimization, uses the existing
`.debug` package suffix and signing identity, and disables debugging. The signed
APK is `app/build/outputs/apk/daily/app-daily.apk`. Keep the matching debug APK
for reversible private-state diagnostics. Never uninstall to switch variants.
Standard `release` remains the separate `app.olauncher` distribution identity.

Debug/test build: `:app:assembleDebug :app:assembleDebugAndroidTest
:app:testDebugUnitTest`. Tests are in a separate APK and add no runtime dependency
or payload to daily. On the emulator, install debug and its test APK, then run:
`adb shell am instrument -w app.olauncher.debug.test/app.olauncher.MemoryInstrumentation`.
Require the explicit PASS output; a shell exit alone is insufficient. Restore
daily after testing. Do not install the instrumentation APK on the daily phone.

===== MEASUREMENTS (2026-09-22) =====

APK: 8,838,328 -> 2,488,102 bytes (71.8% reduction). Optimization baseline SHA256:
`b4cd513ed0057c8ecc2a8cce5472a100397f0065d17b1bc55a1532b12a0b338a`.
Daily contains one DEX and no native libraries. Custom fonts and existing
language resources remain; no customization was removed to get this size.

Galaxy A17 Apps jank: before 1.50/1.30/1.32%, after 0.92/0.92/0.55%; medians
1.32/0.92%. Home after 0.91/1.28/0.91%. This compares the prior debug install
with the optimized daily install, not isolated effects of each source change.
Protocol: 3 rounds, 8 alternating 160 ms flings, 450 ms pauses; current node
bounds are queried before each round. At least 50 frames required per sample.
Five process-cold starts: 389/330/369/362/402 ms. Median 369 ms; no matched cold
baseline, so no startup speedup claimed. Idle 0 frames in a 3-second sample.
Observed PSS 151,461 -> 131,015 KiB, but workloads/process histories differ;
do not claim a causal total-memory reduction from those snapshots.

===== MEMORY AND INTEGRATION =====

IconCache uses a 2 MiB bitmap-allocation byte budget. UI-hidden trims current
contents to 512 KiB; this is not a permanent background budget because loads in
flight may finish. Background pressure clears icons and the pack map. Bitmaps
remain valid while visible rows retain them. Preloading cancels on replacement
and drawer stop, with cooperative checks between synchronous icon resolutions.
Pack reset uses atomic immutable state and compare-and-set publication. Main
thread reset never waits for XML parsing, and a reset invalidates stale loads.

NewApi lint findings: 16 -> 0. API 24 usage-access fallback, API 25 shortcut and
API 26 pin guards, explicit API 31 blur checks. Badge counting is independent
of notification-panel and media-listener connectivity. No new runtime library.

MemoryInstrumentation gates: byte budget, visible bitmap lifetime, hidden trim,
pressure clear, non-blocking pack reset while parser monitor is held. Old APK
fails; new debug companion passes. Six JVM suites / 16 tests pass. Exact daily
passes 11 navigation checks, six customizable panels, persistence, independent
icons, privacy labels, keyboard setting, and badge-off/new-notification flow.

Final lifecycle gate: ten Home/Apps/Search/background-return cycles, three
trim levels (HIDDEN, BACKGROUND, RUNNING_LOW) and three process recreations pass.
The emulator notification listener was temporarily disabled for background trim
and restored afterward: Android rejects background trim injection while the
bound listener keeps a process foreground-important. RUNNING_LOW is exercised
while Moo is visible. CLI uses HIDDEN, not the callback constant UI_HIDDEN.
These are recovery checks, not a long-duration leak/battery certification.

===== EVIDENCE AND LIMITS =====

Desktop `work/`: phone-daily-validation/report.json, phone-optimization-before,
daily-integration-gate.log, daily-customization.log,
daily-badge-integration-recheck.log, daily-stability-recheck.log (ten completed cycles before fixture correction),
daily-pressure-complete.log,
daily-stability/report.json, memory-regression, optimization-unit-results,
optimization-build-verified.log, optimization-lint-after.xml.

Initial badge harness ran the posting command as root and did not create its
fixture. Corrected test posts as shell UID 2000 and verifies the notification
exists before checking Moo. Initial lifecycle harness assumed swipe-left meant
Apps; the emulator had no binding and correctly used the camera fallback.
Corrected lifecycle test opens Apps explicitly from the Home context menu.
Await each harness's finally/exit before another uses that device.

Full lint still fails: 297 MissingTranslation, 2 UnsafeImplicitIntentLaunch,
1 AppCompatCustomView. No broad suppressions/baselines were added. Existing
English fallback remains; this is not a complete localization certification.
Physical runtime is A17 / Android 16, with API 36 emulator layout/lifecycle
coverage. Prior small/landscape/foldable/large-font checks remain relevant since
this pass does not redesign UI. No claim of every OEM, older runtime, battery
drain endurance, or exhaustive leak freedom. Phone audio capture remains
unverified pending explicit permission. Visualizer stays opt-in.

Android references used for callback and build decisions:
- https://developer.android.com/topic/performance/memory/manage-app-memory
- https://developer.android.com/topic/performance/app-optimization/enable-app-optimization

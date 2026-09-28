# Moo verification, 2026-09-24

=====
CURRENT ALIGNED WIDGETS AND FOCUS TICKS (2026-09-25)
=====

The final signed, non-debuggable Daily APK is 2,506,267 bytes, SHA256 7653B8CFDB5B1E26BD5EEA5EE0EC01F6D6618096DCAF6397177012383728B550; apksigner verified v2 signing and one signer. The A17 installed base.apk matched the built hash. Moo stayed default Home, and app data and notifications were preserved. The exact APK displayed live Clear 55 degrees, H:75/L:54, Battery 79% in the new aligned three-line style. The battery was not charging, so the bolt and wattage were correctly absent.

On an isolated API 36 emulator, a 320dp viewport at 130% text passed Light and Dark screenshot/UI-tree checks: Weather and Screen time start at the same x, and Battery and Unlocks start at the same x. A 130%-text landscape check showed all four widgets without clipping. At 200% text the bounded widget container exposed each widget after scrolling, including the last Unlocks row. The matching Debug instrumentation asserted zero-pixel weather/Battery second-line baseline difference in the 130% and landscape cases, charging wattage on a third line, and actual Android AccessibilityNodeInfo click-action labels reading Edit home information. UI finish and bounded accessibility re-reviews passed; full spoken TalkBack and right-to-left runtime were not checked.

The A17's Home focus-vibration preference was On. Before the tick fix, a 700ms swipe moved across about five focused apps but added no 12ms motor records. One same-direction swipe on this exact final APK added five 30ms TOUCH motor records and left Moo foreground. A preceding candidate's Off check produced zero events, then On was restored. The change respects Moo's toggle, Android touch feedback and power saver. The motor proves activation, not Tanner's subjective Apple-like feel.

assembleDaily, assembleDebug, assembleDebugAndroidTest, testDebugUnitTest, testDailyUnitTest, lintDebug and lintDaily completed successfully. Each JVM variant had 34 tests, zero failures/errors/skips; Debug and Daily full-lint XML reports contained zero issues under existing scoped exclusions. Three exact-final A17 eight-swipe Home rounds rendered 517/528/534 frames, 8/7/7 janky, p99 29/27/28ms. The immediate three-second post-scroll sample drew 69 settling frames; after two seconds and reset, settled idle drew zero frames over three seconds. PSS was 132,597 KiB. Sampled logcat held no Moo fatal/ANR. This short USB-powered run does not establish battery-life improvement, lower memory use, a causal speedup or broader OEM behavior. Receipt: task work/widget-final-a17-benchmark.json; screenshots work/widget-final-a17-exact.png, work/widget-narrow-aligned-dark-2.png, work/widget-narrow-aligned-light.png, and work/widget-landscape-aligned-final-2.png.

=====
CURRENT REFERENCE WEATHER WIDGET (2026-09-25)
=====

The signed, non-debuggable Daily APK is 2,505,063 bytes, SHA256 E57574D1F6A126D976B1CA090A251AC665B497881E39AC9D086E0AFD6232D517. It was installed in place on the Galaxy A17 SM-S176V; the pulled installed base.apk matched byte for byte. App data and notifications were preserved and Moo retained the default Home role. On the exact APK, the A17 showed a filled cloud with 60 degrees on the first line, Cloudy on the second, and H:75 degrees L:55 degrees inline on the muted third line. The settled screenshot and UI bounds showed no clipping. Clear night still selects Moo's moon icon.

The matching final Debug app on an API 36 emulator passed phone 1080 x 1920 at normal text, narrow 720 x 1280 at 2x text, and wide 1600 x 900 at 1.3x text with both Cloudy and Partly cloudy fixtures. UI bounds confirmed icon beside current temperature, condition below, inline H/L below that, and all weather elements inside each screen. Instrumentation changed a Clear fixture from day to night while its text stayed identical: the icon pixels changed and the widget speech did not. The widget's English spoken summary carries LocaleSpan(English); Arabic/Hebrew weather text is still untranslated and live TalkBack pronunciation in those locales was not tested.

The final source passed 34 Debug and 34 Daily JVM tests, full Debug/Daily lint with zero issues under documented scoped exclusions, and Debug, AndroidTest, and Daily assembly. The A17 crash buffer had no Moo entry after installation. Settled Home rendered zero frames over a guarded three-second gfxinfo sample after a second reset. Git diff --check was clean. Earlier scroll, startup, and memory numbers below were measured on the preceding APK, so this layout update carries no measured speed or battery claim. Exact-build captures and geometry are in task work/weather-reference-a17-exact.png, work/weather-reference-sizes/, and work/weather-reference-long/.

=====
PRECEDING DESKTOP BUILD AND A17 CHECK
=====

The preceding signed, non-debuggable Daily APK was 2,505,687 bytes, SHA256 `3EAB8D25CAAD3A2CD3D22C8F632BA28B3C5FBE99D615C18626C53EF6017B6C0C`. It was installed in place on the Samsung A17, and the installed `base.apk` hash matched. Moo remained the default Home; app data and personal notifications were preserved. Final `:app:testDebugUnitTest`, `:app:testDailyUnitTest`, `:app:lintDebug`, `:app:lintDaily`, and `:app:assembleDaily` passed. Each JVM variant ran 34 tests with zero failures; both full-lint XML reports have zero issues under documented scoped exclusions. The isolated emulator instrumentation passed against a matching Debug APK; the minified Daily APK is not compatible with that Debug test runner. `git diff --check` is clean.

The preceding A17 build showed live Home weather/battery, and an out-of-focus Home app scrolled into focus on tap, then launched on the second tap. Moo Settings hub, Apps subsection, nested Customize Apps editor, and both Back steps were exercised without changing preferences. The A17's existing gesture mapping is swipe left for Apps and swipe up for Settings. Swipe left opened categorized Apps with icons already visible; the separate Search page opened with its field. A screenshot review found no clipping in those phone states. These phone checks complement the emulator's full Settings and form-factor sweeps.

Three preceding-build A17 rounds of eight alternating swipes per route reported Home 550/555/552 frames, 10/8/9 janky frames, p99 30/28/30 ms, and Apps 458/461/452 frames, 6/6/4 janky frames, p99 18/19/17 ms. The user's Home focus vibration was On for these runs; the earlier matched before/after comparison below had it Off, so do not compare those runs as a controlled speed change. Settled Home rendered zero frames over three seconds. Observed TOTAL PSS was 158,397 KiB after these routes, with no matched baseline for a memory delta. The checked A17 crash buffer had no Moo entry. Battery endurance, A17 process-cold startup, full spoken TalkBack, broad older-device/OEM coverage, counted before/after network requests, and app-private storage size remain unmeasured.

That Daily also passed the API 36 emulator Android Power Saver Off/On/Off and sleep/wake state checks, five device shapes including 2x text, and seven Settings sections plus 25 ordinary nested routes in both Moo Light and Dark. Light, Dark, and Charcoal screenshots confirm the Settings hub and nested pages use the same surface treatment; the Settings/Search UI finish review passed. The accessibility re-review passed its bounded Home badge and Apps inline-menu checks. Conditional Settings routes were exercised separately with emulator-only prerequisites as described below.

The user authorized finishing from this desktop checkout. Syncing to the canonical Linux laptop is deferred, not a gate for the desktop build or the installed A17 APK.

=====
TASK CHECKLIST
=====

- [x] Inspect project instructions, Settings hierarchy, Home app storage, weather, tests, and data flows.
- [x] Make Settings sections and nested editors consistent; add persisted Home order and weather refresh controls.
- [x] Align weather temperature, icon, condition, high, and low across normal, narrow, and wide layouts.
- [x] Keep a new Home alphabetic by default while preserving existing manual arrangements.
- [x] Measure startup, scrolling, idle frames, memory, APK size, permissions, backup, and network paths before/after where the environment permits.
- [x] Test the signed Daily build and focus vibration on the Galaxy A17.
- [ ] Optional follow-up: sync this verified desktop source to the canonical Linux laptop when Tailscale makes it reachable.

=====
BEHAVIOR AND VERIFICATION
=====

The Settings hub's seven sections and 25 ordinary nested routes opened and returned successfully in both Moo Light and Moo Dark. Three conditional routes were exercised separately in Moo Light with their prerequisites enabled: Icon style opened its Full colour/Grayscale choices; Icon pack showed the no-packs toast, then listed and applied an emulator-only test pack before App default was restored and the fixture removed; Badge filter opened its full app picker after a temporary emulator-only notification-access grant, displayed Calendar as muted, then returned to All when unmuted. App icons and badges were restored Off, and the temporary notification grant was revoked. Section Back has a visible, theme-tinted 48 dp control; Tab focused it on the first press and Enter returned to the hub. Nested editors are full-height. Home order and weather refresh changes were read back through the UI and after process recreation. The isolated instrumentation test confirms that a delayed forecast cannot repopulate the cache after Weather is disabled. Thirty-four Debug and thirty-four Daily JVM tests passed. Debug and Daily lint reports have no issue elements.

The preceding build's weather alignment was captured at 1080 x 1920, 320 x 640 with 2x text, and 1600 x 900 with 1.3x text. The supplied reference changed that arrangement; current layout evidence is in the top section. Light and Dark Settings captures and a Dark-theme Home weather capture were reviewed. TalkBack was bound on the isolated emulator, Home exposed named controls, and the emulator was returned to TalkBack Off. This was a structural check, not a full spoken-output audit.

On an earlier signed Daily build (2,504,687 bytes, SHA256 `17221BD86103E6720DD2D0FCFC3FADD48564E812A4C872DA9EA8A9B8C5BF15DE`), the installed A17 copy matched the build and preserved data. Home focus vibration was enabled and read back; scrolling produced repeated 12 ms TOUCH vibrations, turning it Off stopped those events for the same check, and it was restored On. Android Power Saver and Moo Ultra Battery Saver suppress the focus ticks.

=====
PERFORMANCE RECEIPTS AND LIMITS
=====

The earlier controlled A17 signed-Daily comparison used three rounds of eight alternating 110 ms swipes, 350 ms pauses, with gfxinfo reset for Home and Apps. Focus vibration was Off in both runs to isolate rendering. Prior Home frames: 1133/1151/1130, janky frames 11/8/8, p99 26/28/27 ms. Comparison-build Home frames: 554/549/553, janky 7/11/8, p99 28/30/31 ms. Prior Apps frames: 1130/1138/1125, janky 3/5/3, p99 15/16/16 ms. Comparison-build Apps frames: 455/461/454, janky 4/5/5, p99 18/18/17 ms. The frame-count change makes percentages especially misleading; absolute jank is close and the p99 tail is slightly worse. This does not establish a scrolling speedup. Disabling Apps icons during a separate Debug run did not reduce jank, so icon decoding was not identified as this regression's cause.

API 36 emulator process-cold `am start -W` five-round median on the earlier comparison builds: 1,751 ms before, 1,500 ms after. This same-method observation is not a causal or A17 startup guarantee. On the A17, force-stop was auto-restarted by the launcher role and returned WARM, so a trustworthy process-cold sample was unavailable. A17 Settings-to-Moo route WaitTime median changed from 34 to 110 ms, but launch states mixed UNKNOWN/HOT, so it cannot establish a navigation improvement. Settled A17 Home: zero rendered frames over three seconds. Earlier observed A17 TOTAL PSS was 136,556 KiB; no matched baseline workload establishes a memory change. No Moo crash entries were found in the phone crash buffer. Battery energy and long-duration stability were not measured. The phone's non-debuggable build prevented direct app-private storage measurement.

=====
PRIVACY AND PLATFORM BASIS
=====

A final read-only security review found no concrete defect in the reviewed exported components, weather/wallpaper paths, notification/audio handling, backups, or embedded-secret scan. This was static source review, not a live network traffic capture.

Launcher app discovery and selected Home order/names/hidden rules are local preferences. The manifest declares `QUERY_ALL_PACKAGES` for a complete launcher app browser, including Private Space; this is broad visibility and needs a separate Google Play policy review before publication. Optional usage access supplies screen-time counts. Optional notification access and optional audio capture keep content/waveform data in memory; notification text retained for drawing is bounded. Optional Weather sends approximately 0.01-degree coordinates to Open-Meteo over HTTPS while Home is active and retains a reading/forecast day/time zone locally until refresh or Weather is disabled. Disabling Weather clears the persisted fields atomically and rejects a late network response. The 64 KiB forecast response cap limits memory allocation before JSON parsing. Optional rotating wallpapers fetch a Gist list and Unsplash images; a user-initiated web search opens DuckDuckGo in an external browser. No tracking SDK was added. Android backup includes only launcher preferences and requires encryption-capable cloud backup. These controls reduce exposure; the remote providers still receive their request/IP when the corresponding feature is used.

Android documentation distinguishes requirements from recommendations here: launcher package discovery uses Android's package-visibility model; runtime location/audio and special usage/notification access are feature-gated. Minimizing requested permissions, benchmarking on a release-like build, limiting background work, and restricting backups are platform recommendations followed where they fit Moo. References:
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- https://developer.android.com/topic/performance/memory/manage-app-memory
- https://developer.android.com/develop/background-work/background-tasks/optimize-battery
- https://developer.android.com/privacy-and-security/minimize-permission-requests
- https://developer.android.com/training/package-visibility/declaring
- https://developer.android.com/privacy-and-security/risks/backup-best-practices
- https://support.google.com/googleplay/android-developer/answer/10158779?hl=en
- https://developer.android.com/identity/data/autobackup

Evidence files are in the desktop task's `work/` directory: `a17-current-final-daily.json`, `a17-final-live-home.png`, `a17-final-live-settings.png`, `a17-final-live-apps.png`, `settings-final-surface-a/`, `settings-final-surface-dark/`, `settings-charcoal-hub.png`, `settings-charcoal-information.png`, `final-form-factors/report.json`, and the earlier matched benchmark receipts. The final APK installed hash and current lint/test reports were read back directly. The canonical Linux laptop sync is an optional follow-up under the user-authorized desktop scope.
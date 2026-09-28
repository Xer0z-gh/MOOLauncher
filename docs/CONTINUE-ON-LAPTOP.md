# Continuing on the laptop

Updated 2026-09-21. The active source is the laptop working tree, not GitHub.
Changes are uncommitted on `moo-laptop-continuation`; no push was requested.

=====
ACTIVE DEVELOPMENT
=====

- SSH: the laptop's Tailscale address and user are in Tanner's private notes (not in this public repo); hostname Laptop, Debian.
- Repository: `/home/xer0z/Workspace/Dev/Android/Moolauncher`.
- Desktop mirror: `D:\Workspace\Dev\Android\Moolauncher`.
- Toolchain/caches: `/home/xer0z/Workspace/Ops/Moo-Toolchain`.
- JDK21, Android platform36, build-tools35.0.0, Gradle wrapper8.11.1.
- `bash tools/build-laptop.sh :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`
- Add `--offline` once dependencies are cached. Do not skip lint when a fresh
  machine needs to download its lint dependencies.

The signing init script uses the same debug identity as the installed phone app.
The keystore is outside the repository in the toolchain's signing directory.
`install -r` preserves settings, but verify signatures and keep an APK/prefs backup.

=====
PHYSICAL PHONE
=====

USB serial `R5GL55YDDNT`, Galaxy A17 SM-S176V, Android16,1080x2340,450dpi.
Run commands on the laptop with `/usr/bin/adb -s R5GL55YDDNT` explicitly.
The desktop emulator is `emulator-5562` and is never the physical-phone target.
Default home is `app.olauncher.debug/app.olauncher.MainActivity`.

Never point the emulator preference/notification harnesses at the phone. Do not
clear its notifications, change its default home or rewrite its preference XML.
Back up and diff preferences around tests. Natural screen-time/weather caches
change; explicit settings tests should use the UI and restore the selected mode.
Match a fresh UI node before tapping. Long press with DOWN/wait/UP on the phone.
Stop if an unexpected app is foreground. Do not dump unrelated private app text.

For longer tests, temporarily keep the USB-powered display awake and restore
`stay_on_while_plugged_in` in a finally block. The device can otherwise lock
between UI dumps, invalidating a test even if KEYCODE_HOME was sent.

=====
EVIDENCE AND CONTINUITY
=====

Laptop `work/phone-*` directories contain APK/prefs backups, screenshots and
JSON reports. Scripts in the transfer staging directory execute locally on the
laptop so network latency does not affect the duration of injected swipes.
Desktop `work/pull_remote.py` synchronizes source and checks for conflicting
local edits using a hash baseline. Do not overwrite independent local changes.

Read `docs/HANDOFF.md` and `docs/TODO.md` for scope and remaining verification.
Historical baseline gates do not certify the current feature build.

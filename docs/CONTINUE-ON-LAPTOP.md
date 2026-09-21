# Continuing on the laptop

Everything is pushed to `Xer0z-gh/MOOLauncher`. The laptop already has `adb` and
the phone plugged into it over USB, which is a better setup than the desktop had
— the desktop reached the phone through a Tailscale relay, and a high-latency
link makes `input swipe` unreliable enough that scroll benchmarks are unusable.

```bash
git clone https://github.com/Xer0z-gh/MOOLauncher.git
cd MOOLauncher
```

## What the build needs

- **JDK 17+.** Gradle 8.11 and AGP 8.9 refuse to run on Java 8. On the desktop
  the JDK that works is the one bundled with Android Studio
  (`Android Studio/jbr`); on the laptop, `sudo apt install openjdk-17-jdk` or
  Android Studio's own.
- **Android SDK with platform 36.** `compileSdk 36`, `targetSdk 36`,
  `minSdk 24`. Point `local.properties` at it:
  ```
  sdk.dir=/home/xer0z/Android/Sdk
  ```

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # or Studio's jbr
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`install -r` upgrades in place and keeps every setting.

## Verifying on the phone

The phone is `R5GL55YDDNT`, a Samsung SM-S176V: Android 16 (API 36), 1080x2340,
450 dpi. Over USB it is just `adb` with no `-H` or `-s` needed if it is the only
device attached.

**It is his daily phone and his live launcher.** The rules that matter:

- **Do not run the emulator harnesses against it.** `test_panel.py`,
  `test_badges.py` and the settings matrix rewrite preferences, swipe rows away
  and tap *Clear all* — on this phone that rebinds a gesture he uses and wipes a
  shade full of his real notifications.
- **Back up preferences first**, and diff them afterwards:
  ```bash
  adb shell run-as app.olauncher.debug cat shared_prefs/app.olauncher.xml > prefs.bak
  ```
  `SCREEN_TIME_LAST_UPDATED` changes on its own every time the launcher resumes;
  anything else changing means something was written that should not have been.
- **Never tap a remembered coordinate.** Dump, match the node by exact text or
  `content-desc`, tap that node's own centre. A stale dump once opened the
  launcher chooser and cleared the default home activity.
- **A long press on the home screen must land on empty space.** A long press on
  an app row is that row's own gesture, not the launcher's. Below the last app
  is safe; the y that works on this screen is around 1990.

The read-only check that is safe to run is
`ClaudeVault/Projects/Moo Launcher/attachments/phone_panel.py` — it navigates to
the panel through Settings, reads back what rendered, screenshots it, and
changes nothing. It needs its `HOST` list changed to `[]` when adb talks to the
phone directly over USB.

## The 20-second manual check

Faster than any of it, and the one thing still unverified on the device:

1. Long press an empty part of the home screen → **Settings**
2. **Home screen** → scroll down to **Notification panel** → **Open**
3. The shade should be listed, grouped by app, newest first.

Tap a row to open what sent it. Swipe a row sideways to dismiss it. Long press a
row to mute that app — the same list that controls the home screen badges.

## Where the state is written down

- `ClaudeVault/Projects/Moo Launcher/Moo Launcher.md` — hub, what is built, what
  is open
- `.../Decisions.md` — every design decision and the reasoning
- `.../Toolchain.md` — how the emulator and the phone are driven
- `.../attachments/` — every harness

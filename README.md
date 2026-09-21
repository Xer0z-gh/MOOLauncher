# Moo Launcher

A minimal, text-first Android launcher. A fork of
[Olauncher](https://github.com/tanujnotes/Olauncher) that keeps its home screen
— a clock, a short list of app names, nothing else — and adds the parts of
[Before Launcher](https://play.google.com/store/apps/details?id=com.beforesoft.launcher)
worth having.

Built and tested against a Samsung Galaxy A17 5G (SM-S176V, Exynos 1330, 4 GB
RAM, Android 16). Every feature below was verified by running it on that phone,
not by a green build.

## What this adds over Olauncher

**Missed-notification badges.** A count beside each home app of what you missed
while you were away. It is not a mirror of the notification shade: the counter
goes up once per genuinely new notification and only returns to zero when you
open that app from the launcher — dismissing from the shade does not clear it,
because you still missed it. Tap a badge to see the recent lines. Ongoing,
foreground-service and group-summary notifications are filtered out, and you can
mute any app individually.

**Colour themes.** Thirteen, each previewed as your own home screen rather than
a colour square. The launcher paints its own background, so a theme never
depends on — or overwrites — your wallpaper.

**Assignable gestures.** Swipe up, swipe down, double tap and long press, each
taking any of: nothing, app list, app search, notification shade, launcher
settings, lock screen, launch a chosen app, or the missed-notifications view.

**Icons, where you want them.** Off, home only, app list only, or both — because
wanting icons while browsing every installed app but not on a spare home screen
is a coherent thing to want, and one switch cannot say it. Installed icon packs
(ADW/Nova format) are supported, full colour or greyscale.

**A settings hub** of four sections instead of one scroll of thirty rows, plus
date format, row spacing, text size, an animation switch, a font picker, a
screen-time and unlock counter, and weather.

## Weather

Uses [Open-Meteo](https://open-meteo.com), which needs no API key and no
account. It reads your *last known* location fix and never wakes the GPS — a
launcher has no business doing that, and a temperature accurate to the nearest
town is accurate enough. Off by default. If location services are off
device-wide there is no fix to read, and the line stays blank.

## Privacy

Nothing is collected and nothing is sent anywhere, with one exception: when you
turn weather on, a coarse latitude and longitude go to Open-Meteo to fetch a
temperature. Notification contents are read by a
`NotificationListenerService` to count and preview them, and never leave the
device — they are held in memory only and lost when the process dies. Screen
time and unlock counts come from Android's own `UsageStatsManager` and stay
local.

## Building

Needs JDK 17 or newer. Android Studio's bundled JBR works:

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
./gradlew :app:assembleDebug
```

`compileSdk 36`, `minSdk 24`. Debug builds install as `app.olauncher.debug`, so
they sit alongside whatever launcher you actually use.

## Licence and credits

GPLv3, inherited from Olauncher — see [LICENSE](LICENSE). Original work by
[tanujnotes](https://github.com/tanujnotes/Olauncher); everything added here is
under the same licence.

The bundled typeface is [Inter](https://rsms.me/inter/) under the SIL Open Font
License — see [INTER-FONT-LICENSE.txt](INTER-FONT-LICENSE.txt). Before Launcher
ships Fakt Pro, which is commercial and licensed to them, so it is not and will
not be included here.

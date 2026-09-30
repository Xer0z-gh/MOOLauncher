# Moo Launcher

Moo is a minimal, text-first home screen for Android. The home screen shows a clock and a short list of app names. Around that it adds missed-notification badges, six assignable gestures, 13 color themes, optional icons and icon packs, small information lines (date, battery, weather, screen time, unlocks, next alarm) and a battery saver mode. There are no ads, analytics, trackers or accounts. Moo is free software under the GPLv3, based on Olauncher by tanujnotes.

## Install

Requires Android 7.0 or newer.

- **GitHub Releases:** download the APK from the [latest release](https://github.com/Xer0z-gh/MOOLauncher/releases/latest).
- **Obtainium:** tap the badge to add Moo, and Obtainium will install updates from GitHub Releases.

  [<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.xer0z_gh.moo%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXer0z-gh%2FMOOLauncher%22%2C%22author%22%3A%22Xer0z-gh%22%2C%22name%22%3A%22Moo%20Launcher%22%7D)
- **F-Droid and Google Play:** not listed yet.

After you install it, press Home and choose Moo. You can also set it in Android's settings under Default apps > Home app. The exact path varies by phone.

## What it does

**Home screen.** A clock and a short list of app names. You can add information lines for the date, battery, weather, screen time, unlock count and next alarm. The music visualizer draws whatever the phone is playing; on the Play build it is part of Moo Pro. A daily wallpaper is optional.

**Notifications.** Each home app can show a badge (a count or a dot) for notifications you missed. Opening the app from Moo clears its count. Tap a badge to see the latest lines, and mute badges for any app you like. A notification panel with filters opens from any gesture you set to Missed notifications.

**Gestures.** You can assign six gestures: swipe up, down, left and right, double tap and long press. Each one can do nothing, or open the app list, app search, the notification shade, launcher settings, the missed notifications panel or an app you choose. It can also lock the screen (Android 9 and newer).

**Appearance.** There are 13 color themes, and each is previewed as your own home screen. On the Play build, System, Ink and Paper are free and the other 10 come with Moo Pro. Moo draws its own background, so a theme never replaces your wallpaper. Icons can be off, on the home screen, in the app list or in both. ADW/Nova-format icon packs work in full color or greyscale. Sliders set sizes, text size and text weight in steps. Inter is one of the fonts.

**App drawer.** The drawer sorts apps A to Z and can group them into categories. Search can open the app automatically when only one result is left. You can hide apps, and Private Space works on Android 15 and newer.

**Weather.** Weather comes from Open-Meteo in the GitHub/F-Droid build and from MET Norway in the Play build; neither needs an account or key. Moo uses either the phone's approximate location or a town you type in Settings. For the phone's location, Moo reads a recent location fix, or asks Android for a network location if there is none; it does not turn on GPS. With a typed town, Moo needs no location permission.

**Motion and power.** You can choose a motion style, and turn on a focus scroll layout with haptic ticks. Ultra battery saver runs the screen at 60 Hz, makes Home true black and wakes the phone less often. It can switch on and off with Android's Power Saver.

**Settings.** Settings are split into pages: Home screen, Appearance, Apps, Notifications, Weather, and Motion and power. Gestures are on the Motion and power page.

## Moo Pro (Google Play) and the FOSS build

There are two builds, made from the same source.

| | FOSS build (GitHub Releases, F-Droid, other stores) | Play build (Google Play) |
|---|---|---|
| Features | Every feature, including all 13 themes and the music visualizer | Every feature except 10 of the themes and the music visualizer, which come with Moo Pro |
| Moo Pro | Not needed | A small separate app on Google Play (`io.github.xer0z_gh.moo.pro`), bought once. With it installed, 10 color themes and the music visualizer unlock. System, Ink and Paper are free. |
| Proprietary Google libraries | None | None. Google Play sells Moo Pro as an ordinary paid app; Moo only checks that it is installed and signed with the same key. |
| Weather and place search | Open-Meteo | MET Norway and Photon (OpenStreetMap). Open-Meteo's free API is for non-commercial apps, and this build sells Moo Pro. |
| Ads, analytics, trackers, accounts | None | None |

The FOSS build is not a cut-down version, and nothing in it is locked. Buying Moo Pro on Google Play pays for development. If you want to support the project and you use Play, that is the way to do it.

## Privacy

Moo sends data off the device only in these cases:

1. **Weather, when it is on.** Moo sends the coordinates of either your approximate location or the town you typed, rounded to 2 decimal places (roughly 1 km), to Open-Meteo (FOSS build) or MET Norway (Play build).
2. **Place search.** When you search for a town, Moo sends the name you typed and your language setting to Open-Meteo's geocoder (FOSS build), or the name you typed to Photon (Play build), with your language only if it is English, German or French.
3. **Daily wallpaper, when it is on.** Moo downloads a wallpaper list from GitHub (a gist that Olauncher's author maintains). The images come from images.unsplash.com, images.pexels.com, i.redd.it and images2.imgbox.com.
4. **Moo Pro (Play build only).** Moo checks on the phone, with no network request, whether the Moo Pro app is installed. Google Play handles buying it.

As with any download, the service at the other end also sees your IP address.

Notification contents, app usage, the list of installed apps and the audio used by the visualizer all stay on the device. None of it is stored anywhere else. The accessibility service does only two things: it locks the screen and opens the notification shade when you use a gesture set to do that. It does not read what is on the screen.

Full policy: [PRIVACY.md](PRIVACY.md).

## Permissions

| Permission | Why |
|---|---|
| `QUERY_ALL_PACKAGES` | To list and open every installed app, and for search and icon packs |
| `PACKAGE_USAGE_STATS` (Usage access) | Screen time and unlock count |
| `ACCESS_COARSE_LOCATION` | Weather from your approximate location. Optional: a typed town does not need it |
| `RECORD_AUDIO` | The music visualizer only. It reads the output mix and does not record or store audio |
| `MODIFY_AUDIO_SETTINGS` | Needed with `RECORD_AUDIO` to attach the visualizer to the output mix |
| Notification listener | Badges, the notification panel, and media info for the visualizer |
| Accessibility service | Locking the screen and opening the notification shade from a gesture |
| `REQUEST_DELETE_PACKAGES` | Uninstalling an app from the app list |
| `EXPAND_STATUS_BAR` | Opening the notification shade |
| `SET_WALLPAPER` | The optional daily wallpaper |
| `SET_ALARM` | Opening your alarm app |
| `VIBRATE` | Haptic ticks while scrolling |
| `INTERNET` | Weather, place search and the daily wallpaper |
| `ACCESS_HIDDEN_PROFILES` | Private Space on Android 15 and newer |

Moo asks for usage access, notification access, the accessibility service, location and the microphone only when you turn on a feature that needs one of them.

## Verifying the APK

- Package ID: `io.github.xer0z_gh.moo`
- Signing certificate SHA-256:
  `ed92d7271208c877d38eca0edc41238b058c26e64a9783b58b8d6e34c174e13d`

Check a downloaded APK with:

```bash
apksigner verify --print-certs moo.apk
```

The certificate SHA-256 digest line should match the value above. Depending on your build-tools version it reads `Signer #1 certificate SHA-256 digest` or `V2 Signer: certificate SHA-256 digest`.

For [AppVerifier](https://github.com/soupslurpr/AppVerifier), use:

```
io.github.xer0z_gh.moo
ED:92:D7:27:12:08:C8:77:D3:8E:CA:0E:DC:41:23:8B:05:8C:26:E6:4A:97:83:B5:8B:8D:6E:34:C1:74:E1:3D
```

## Building

You need JDK 17 or newer. The JBR bundled with Android Studio works.

```bash
./gradlew assembleFossDebug     # FOSS build, installs as io.github.xer0z_gh.moo.debug
./gradlew assembleFossRelease   # FOSS release
./gradlew bundlePlayRelease     # Play build (Moo Pro unlocks it)
./gradlew :prokey:bundleRelease  # the Moo Pro key app
```

Debug builds use the `.debug` suffix, so they install beside a release copy. Release signing reads `keystore.properties` in the project root. That file is gitignored and holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. If the file is missing, release builds come out unsigned.

`compileSdk 36`, `targetSdk 36`, `minSdk 24`.

## Licence and credits

Moo is free software under the GNU General Public License v3.0. See [LICENSE](LICENSE).

- Based on [Olauncher](https://github.com/tanujnotes/Olauncher) by Tanuj M. (tanujnotes), GPLv3.
- **Modification notice (GPLv3 section 5(a)):** Xer0z-gh has modified this program since September 2026. The changes are recorded in this repository's commit history and are released under the same licence.
- The bundled typeface is [Inter](https://rsms.me/inter/), under the SIL Open Font License 1.1. See [INTER-FONT-LICENSE.txt](INTER-FONT-LICENSE.txt).
- FOSS build: [Weather data by Open-Meteo.com](https://open-meteo.com/), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- Play build: weather forecasts from [MET Norway](https://api.met.no/), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); Moo derives each day's high and low from them. Place search by [Photon](https://photon.komoot.io/), with data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), licensed under the [ODbL](https://opendatacommons.org/licenses/odbl/).

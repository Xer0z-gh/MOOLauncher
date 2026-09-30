# Moo Launcher privacy policy

Effective 2026-09-30. Applies to Moo Launcher (package `io.github.xer0z_gh.moo`) on Google Play,
GitHub, F-Droid and any other store.

Moo Launcher has no accounts, no ads, no analytics and no trackers. The developer receives no
data from the app. Everything below happens between your phone and the service named, and only
when you turn on the feature that needs it.

## What leaves your phone, and when

| Feature (off unless you turn it on) | Sent to | What is sent |
| --- | --- | --- |
| Weather, using the phone's location | GitHub and F-Droid version: Open-Meteo (`api.open-meteo.com`). Google Play version: MET Norway, the Norwegian Meteorological Institute (`api.met.no`) | Approximate coordinates, rounded to about 1 km |
| Weather, using a place you typed | The same service as above | That place's coordinates, rounded the same way |
| Searching for a weather place | GitHub and F-Droid version: Open-Meteo's geocoder (`geocoding-api.open-meteo.com`). Google Play version: Photon, run by komoot with OpenStreetMap data (`photon.komoot.io`) | The name you typed, and your language (on Photon, only when it is English, German or French) |
| Daily wallpaper | GitHub (`gist.githubusercontent.com`, a list kept by Olauncher's author) and the image hosts it names: `images.unsplash.com`, `images.pexels.com`, `i.redd.it`, `images2.imgbox.com` | A normal download request |

**Moo Pro (Google Play version).** Moo Pro is a separate app sold on Google Play. Moo Launcher checks,
on the phone and without any network request, whether that app is installed. If you buy Moo Pro,
Google Play handles the payment and gives the developer the order details it gives every seller
(order number, date, price, country or region, postal code). They are used only for accounting and
refunds.

Like any download, each request also carries your IP address. Most requests also carry Android's
standard user-agent (device model and Android version); on the Google Play version, the weather and
place-search requests carry the app's name and version instead, as MET Norway asks. Moo
does not keep or use any of it. The services' own policies: Open-Meteo <https://open-meteo.com/en/terms>,
MET Norway <https://www.met.no/en/About-us/privacy>, komoot (Photon) <https://www.komoot.com/privacy>.

Searching the web with `!` in app search opens your browser at DuckDuckGo with what you typed;
the browser sends it, not Moo.

## What stays on your phone

- **Installed apps** are read to show, search and launch them.
- **Notifications** are read (with your permission) to count what you missed, show the
  notification panel and find the playing song. They are kept in memory only and are gone when
  the app stops.
- **App usage** (with your permission) gives the screen-time and unlock counts.
- **Audio playing on the phone** (with microphone permission) draws the music visualizer. Moo
  reads the sound that is already playing; it never records, stores or sends audio.
- **The accessibility service** (if you turn it on) is used only to lock the screen and open the
  notification shade when you make that gesture. It reads nothing on your screen.
- **Your settings** are stored in the app's private storage and in Android's own backup if your
  phone backs up apps.

## Keeping and deleting data

The developer stores no data from the app. Everything Moo keeps is on your phone, and it is deleted
when you clear Moo's storage or uninstall it.

## Children

Moo Launcher is not directed at children and collects no personal data from anyone.

## Changes and contact

Changes to this policy are published in this file, with a new effective date, in the app's public
repository: <https://github.com/Xer0z-gh/MOOLauncher/blob/master/PRIVACY.md>.
Questions: write to the developer email shown on Moo Launcher's Google Play page, or open an issue
at <https://github.com/Xer0z-gh/MOOLauncher/issues>.

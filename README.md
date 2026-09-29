# GeoShutter

Open-source geotagging for **Sony** and **Fujifilm** cameras, on Android and iPhone. Your
phone sends its GPS position to the camera over Bluetooth Low Energy, so every photo
records where it was taken. GeoShutter also sets the camera's clock and time zone, and
works as a Bluetooth remote shutter for Sony cameras.

Website: [geoshutter.sarmad.no](https://geoshutter.sarmad.no)

GeoShutter is a standalone app by Sarmad Jari, based on [Alpha GPS](https://alphagps.app)
by [Saschl](https://github.com/Saschl) ([Saschl/alpha-gps](https://github.com/Saschl/alpha-gps))
and licensed under the [GPL-3.0](LICENSE) like the original; see
[Credits](#credits-and-support). It has its own app ID (`com.sarmadjari.geoshutter` on
Android and iPhone), so it installs next to Alpha GPS.

**Tested with this app:** Fujifilm **X100VI** and Sony **α1 II** (ILCE-1M2), on Android
(see [Supported cameras](#supported-cameras)).

## What it does

- **Geotagging**: sends your position to every connected camera, by default every 5
  seconds (per camera, up to every 30 s for Sony), using the same Bluetooth
  location-linking feature as Sony's Imaging Edge Mobile and Creators' App. Location is
  only used while a camera is connected and switched on.
- **Time sync**: sets the camera's date and time when it connects, and adds time zone and
  daylight-saving information to each position when the camera supports it. On Fujifilm
  cameras it also sets the time zone (AREA SETTING) and daylight saving time.
- **Automatic reconnect**: pair once, and the app reconnects whenever you switch the
  camera on, also in the background and with the phone locked.
- **Several cameras** at the same time.
- **Status at a glance**: on Android a status notification, a Quick Settings tile
  (on/off) and a home-screen widget, on the iPhone a home-screen widget and a Control
  Center control (on/off), show whether GeoShutter is on and which cameras receive your
  location.
- **Remote shutter**: one tap runs a full half-press → focus → shutter → release cycle,
  driven by the camera's status messages.
- **Per-camera settings**: rename, enable/disable, remote control, how often the location
  is updated, keeping a Sony camera awake while it is connected, a connection-setup delay
  for slow-starting cameras, and the camera's own *Automatic time correction* and
  *Automatic area adjustment* settings.
- **Built-in help**: step-by-step troubleshooting guide, FAQ and log viewer.
- **Fujifilm**: cameras of the FUJIFILM XApp generation, tested on the X100VI with
  Android (the iPhone app has the same support: a first test paired and set up an
  X100VI, geotags from the iPhone are still to be checked), using
  Fujifilm's own Bluetooth geotagging protocol: the camera asks for the location
  (every 10 s by default, adjustable), GeoShutter sets its date, time and time zone when
  it is switched on, reads and changes its *Smartphone location sync* and *Connect while
  power off* settings, and keeps the location of a camera that is switched off or asleep
  up to date in standby, so the first photo after switching it on is tagged right away.
  Based on [furble](https://github.com/gkoh/furble); see
  [docs/fujifilm-protocol.md](docs/fujifilm-protocol.md).
- **Sony power handling**: *Keep the camera awake* holds off the camera's power save while
  it is connected, so every photo is tagged; a camera switched off but still connected
  (*Cnct. while Power OFF*) is shown as *Switched off* and gets no location.
- **Private by design**: no account, no ads, no tracking. Crash reporting is opt-in, only
  exists in builds configured with a Sentry DSN, and the FOSS Android build (`foss`
  flavor, intended for F-Droid) has no crash reporter at all. See the
  [privacy policy](privacy.md).

## Requirements

| | Android | iPhone |
|---|---|---|
| System | Android 8.0 (API 26) or later with Bluetooth LE | iOS 18.0 or later (iPhone app) |
| Automatic background reconnect | Android 12 or later. On Android 8–11, turn on **Always On** for the camera. | yes |
| Permissions | precise location with "Allow all the time", Nearby devices (Bluetooth), notifications (Android 13+) | location **Always** with **Precise Location**; cameras are added through the iOS accessory picker |
| Camera | a Sony camera that can receive location information from Imaging Edge Mobile or Creators' App over Bluetooth, or a Fujifilm camera that syncs its location with FUJIFILM XApp (current, secured Bluetooth firmware) | the same |

GeoShutter is tested on a Samsung phone with Android 17; Alpha GPS was tested on Android 10,
12, 13, 15 and 16. Some manufacturers customize Android's background handling, so behavior
can differ between phones. The iPhone app is built and unit-tested, but this app has not
been tested on an iPhone with a camera yet.

## Install

GeoShutter isn't in the app stores yet. [Build it from source](#build-from-source); once
builds are published, Android APKs will be attached to the
[GitHub releases](https://github.com/sarmadjari/GeoShutter/releases). There are two
Android builds:

- `gplay`: with Google Play services location, the in-app review prompt and opt-in crash
  reporting.
- `foss`: without Google Play services and without crash reporting (intended for
  F-Droid).

## Using the app

### Android

1. **Grant the permissions** on first start: precise location, Nearby devices (Bluetooth)
   and notifications, then background location ("Allow all the time").
2. **Put the camera into Bluetooth pairing mode** (menu names vary by model, for example
   MENU → Network → Bluetooth → Pairing).
3. Tap **Add camera**. Android's companion-device chooser lists nearby Sony (and
   Fujifilm) cameras; pick yours. If the phone is not paired with it yet, the app starts Bluetooth pairing:
   confirm on the phone and on the camera. A Fujifilm camera shows a six-digit code: press
   MENU/OK on the camera within 30 seconds (Android confirms on the phone by itself and
   shows no code).
4. The camera now appears under **My Cameras**, with the camera's own name and its brand
   and model underneath (for example *ILCE-1M2* with *Sony α1 II*, or *X100VI-…* with
   *Fujifilm X100VI*). On
   Android 12 and later, Android starts the app's foreground service whenever the camera
   shows up, even when the app is closed: it connects within seconds, syncs the time and
   sends your location. When the camera is switched off, the service stops.
5. **Always On** (per camera: *Keep active (Always On)*) keeps the foreground service
   running and reconnecting on its own. It is required on Android 8–11 and helps on
   phones with aggressive battery management (for example Xiaomi). While no camera is
   connected it does not access your location. Combine it with *Start App on Device boot*
   in Settings.
6. **Status at a glance.** While GeoShutter is on, a notification shows what it is doing:
   *Sending location to X100VI-…* (brand and model underneath), *Connecting to …*,
   *X100VI-… is in standby – Location kept up to date for your next photo*, *X100VI-…: location
   sync is off on the camera* or *GeoShutter is on – Waiting for …*. Its **Turn off**
   button switches GeoShutter off. Its icon, like the tile's and the widget's, is the app
   icon's frame with a pin: filled while a camera receives your location, outlined
   otherwise. Two optional extras:
   - the **GeoShutter** Quick Settings tile turns GeoShutter on or off;
   - the **GeoShutter** home-screen widget only shows the state: each camera with a dot,
     green (receiving your location), blue (Fujifilm in standby: switched off or asleep,
     still receiving it), amber (connecting, or location sync off on the camera), red (away) or
     grey (GeoShutter off). Tap it to open the app.

   Turning GeoShutter on (tile or *Enable App*) connects right away to cameras that are
   already on. An X100VI stops advertising about 30 seconds after it loses its connection:
   if it doesn't connect, wake it or switch it off and on.

### iPhone

1. Tap **Add camera**, put the camera into pairing mode as shown, then tap **Search for
   camera**. iOS shows its accessory picker with nearby Sony and Fujifilm cameras; select
   yours. iOS pairs with a Sony camera right away. A Fujifilm camera is paired when the
   app first connects to it: confirm the code on the iPhone and on the camera (MENU/OK)
   within 30 seconds. Fujifilm on the iPhone is new: a first test paired and set up an
   X100VI.
2. When the first camera connects, allow **location access**, choose **Always** when iOS
   offers it (needed for background geotagging) and keep **Precise Location** on. If you
   missed the prompt, the app offers a shortcut to the Settings app.
3. From then on the app reconnects automatically when the camera is switched on, also in
   the background: iOS keeps a pending connection to your saved cameras and can relaunch
   the app when one connects. *Transmission notification* in Settings shows a
   notification while location is being sent.
4. **Status at a glance.** Add the **GeoShutter** widget to the home screen (small or
   medium): it shows whether GeoShutter is on and each camera with a dot, green
   (receiving your location), blue (Fujifilm in standby), amber (location sync off on
   the camera), red (not connected, also while a camera is being set up, as in the app)
   or grey (GeoShutter off). The **GeoShutter**
   control, added to Control Center (or the Lock Screen), turns GeoShutter on or off like
   *Enable App*. Both show the app icon's frame with a pin: filled while a camera
   receives your location, outlined otherwise. They show what the app last reported.
   While GeoShutter is connected to a camera, iOS updates the widget at most every
   5 minutes, so it can be up to 5 minutes behind the app. A camera that drops for a few
   seconds, as a Sony camera in power save does about every minute, keeps its state on the
   widget.
   The **Live Activity** shows the same status on the Lock Screen and in the Dynamic
   Island and follows changes within seconds (a camera that drops counts as away after
   30 s). It shows while GeoShutter is on. iOS lets it start only when you open the app
   or switch GeoShutter on with the control, and ends it after 8 hours, so opening the
   app starts it again. Turn it off with *Live Activity* in Settings.
5. Cameras that were added with app versions before 1.6.2 need a one-time **Confirm
   cameras** step so iOS can manage them; iOS usually finishes it within seconds without
   further questions. All settings carry over and no new pairing is needed.

### Camera details (both platforms)

Tap a camera on **My Cameras** to open its details. A status card at the top shows what
the camera is doing, with the same colors as the widget: *Receiving your location*
(green), *Standby* (blue), *Connecting…* or *Location sync is off* (amber), *Not connected*
(red), *Switched off* (red; a Sony camera still connected while off) or *Disabled* (grey),
with the brand, model and (Android) Bluetooth address. The settings follow in groups:
**General**, **Location and time**, then **Standby** (Fujifilm) or **Power** and **Remote
control** (Sony), then **Advanced**. Each setting has a one-line summary; tap ⓘ next to
its name for the full explanation. Tapping anywhere on a row with a switch toggles it. The
camera list shows the same states in short: *Standby · location kept up to date*,
*Location sync is off on the camera* or *Switched off*.

| Setting | What it does |
|---|---|
| Rename | Changes the name shown in the app. On iPhone, cameras added through the accessory picker are renamed in the iOS accessory record instead. |
| Enable device | Stops or resumes using this camera without removing it. |
| Keep active (Always On) | Android only, see above. |
| Enable remote control | Sony. Watches the camera's Bluetooth remote. When the camera allows remote control (camera menu *Bluetooth Rmt Ctrl*), a **Trigger remote shutter** button appears on the camera card. |
| Delay connection setup | Waits 1–10 s (default: off) after the camera connects before the GPS setup starts. Try it if the camera starts slowly or its screen stays black. |
| Automatic time correction, Automatic area adjustment | Sony. Reads and changes these settings **on the camera**. The camera must be connected, and not every model supports it. |
| Location updates while on (Sony) | How often your phone sends its location to the camera (5 s to 30 s). **Every 5 s is recommended** and the default; the camera keeps using the last location for about a minute, so longer intervals still tag every photo, only with an older position. |
| Keep the camera awake | Sony, off by default. A Sony camera goes into power save after its *Power Save Start Time* (1 minute by default) and then ends the Bluetooth connection, so photos taken right after waking it get no location for a few seconds. With this on, GeoShutter sends the camera the same "stay awake" message as Sony's own app every 5 s while it is connected: the connection stays up and every photo is tagged, but the camera uses more battery because its screen stays on until you switch it off. Tested on an α1 II. |
| Set date, time and time zone | Fujifilm, Android, on by default. When the camera is switched on or wakes up and connects, sets its clock, time zone (AREA SETTING) and daylight saving time to the phone's. The camera accepts the time only then, so turning this on takes effect the next time you switch the camera on. |
| Smartphone location sync | Fujifilm, Android. Reads and changes the camera's SMARTPHONE LOCATION SYNC. setting; while it is off the camera doesn't ask for the location, and GeoShutter doesn't use your phone's location for it. The camera must be connected. |
| Location updates while on (Fujifilm) | Fujifilm, Android. How often the camera asks for your location while it is on (10 s to 8 min); your phone's GPS follows. **Every 10 s is recommended**: accurate tags while you move, with little battery difference up to about 30 s. |
| Stay connected when off | Fujifilm, Android. Reads and changes the camera's CONNECT WHILE POWER OFF setting (see below). The camera must be connected. |
| Location updates in standby | Fujifilm, Android. How often your phone refreshes its location while the camera is in standby (30 s to 5 min). **Every minute is recommended**: the first photo after switching on gets a location at most about a minute old, and the GPS can rest in between. |

To remove a camera, swipe its card to the left, or tap *Delete Device* at the bottom of its
details; both ask for confirmation. Removing it also deletes the Android companion
association or the iOS accessory pairing.

### Settings

- **Android**: *Enable App* (master switch, same as the tile and the notification's *Turn
  off*), *Start App on Device boot* (for Always On
  cameras), *Haptic feedback*, show the welcome screen again, log level, *Location
  Provider* (Google Play build only: Google Play services or the Android platform
  provider), *Transmission Event Sounds* (connected, disconnected, location acquired,
  location invalid), *Battery & Background Settings*, language, what's new and *Error
  Reporting Settings* (Google Play build with a Sentry DSN only).
- **iPhone**: *Enable App* (same as the Control Center control), *Transmission notification*, *Haptic feedback*, language,
  what's new, log level and error reporting (builds with a Sentry DSN only).

## Troubleshooting

The app contains a step-by-step **troubleshooting guide** (ⓘ → *Troubleshooting Guide*,
or *Need help?* below the camera list). The most common fixes:

- **Older cameras need location linking switched on manually** (for example α6400,
  ZV-E10, α6100, α7 III): MENU → Network → Loc. Info. Link Set. → Location Info. Link → On.
  Turn on *Auto Time Correct.* and *Auto Area Adjust.* in the same menu to take over time
  and time zone from the phone. Newer cameras activate linking automatically.
- **Connected, but the photos get no location**: typical when the camera was set up with
  Creators' App or Imaging Edge Mobile before. Remove the old pairing everywhere, then add
  the camera again:
  - phone: Android *Settings → Connected devices → your camera → Forget*; iPhone
    *Settings → Bluetooth → ⓘ → Forget This Device*
  - camera: *MENU → Network → Bluetooth → Manage Paired Device* (older models don't have
    this item; pairing again replaces the old connection)
  - Sony app: *Creators' App → Cameras → Setup → Unpair*
- **Bluetooth remote and location linking conflict** on older cameras such as the α6400:
  set *Bluetooth Rmt Ctrl* to Off on the camera and turn off *Enable remote control* in
  the app. Newer models (α7 IV and later) support both.
- **Imprecise positions**: allow precise location (Android: *Settings → Apps → GeoShutter →
  Permissions → Location → Use precise location*; iPhone: *Settings → Privacy & Security →
  Location Services → GeoShutter → Precise Location*).
- **The camera starts slowly or its screen stays black**: set *Cnct. while Power OFF* (or
  *Cnct. during Power OFF*) to Off on the camera, or use *Delay connection setup* for that
  camera.
- **A Sony camera wakes up about every minute**: its *Cnct. while Power OFF* is on
  (MENU → Network → Cnct./Remote Sht.). It keeps the sleeping camera reachable, GeoShutter
  reconnects, which wakes it, and a minute later it falls asleep again. Set it to Off:
  GeoShutter doesn't need it on Sony cameras (they take no location while off), and it
  costs camera battery. To keep a camera ready for tagged photos, turn on *Keep the camera
  awake* in its details instead.
- **The first photos after switching a Sony camera on have no location**: a Sony camera
  ends the connection when it is switched on and takes the location only once the phone has
  reconnected, about 10 s later (measured on an α1 II; about 16 s with *Cnct. while Power
  OFF* on, which is meant for image transfer and brings no benefit for geotagging). Photos
  right after waking it from power save are affected the same way; *Keep the camera awake*
  avoids that.
- **"Pairing Failed" dialog**: put the camera into pairing mode, delete old pairings on
  the camera and in the phone's Bluetooth settings, then add it again.

On Android also try:

- Turn the camera off, wait a minute and turn it on again, or toggle Bluetooth.
- Remove the camera and add it again.
- Exclude the app from battery optimizations (Settings → *Battery & Background Settings*,
  see also [dontkillmyapp.com](https://dontkillmyapp.com)).
- Turn on **Always On** for the camera (required on Android 8–11).
- In the Google Play build, switch the *Location Provider*.

On iPhone also check that location access is **Always** with **Precise Location**, and
confirm your cameras if the app asks for it.

**Still stuck?** Open the logs (list icon on *My Cameras*; raise the log level in Settings
if needed), copy the relevant lines and open an issue in the
[GeoShutter issue tracker](https://github.com/sarmadjari/GeoShutter/issues). The logs
contain your cameras' Bluetooth addresses but no location data. Problems with the store
versions of Alpha GPS can also be reported [upstream](https://github.com/Saschl/alpha-gps/issues).

## Supported cameras

<!-- Keep in sync with testedCameras and upstreamCameras in website/src/pages/index.astro. -->

**Tested with GeoShutter** (on Android):

| Camera | Tested |
|---|---|
| Fujifilm X100VI (firmware 1.32) | geotagging (EXIF position within 1 m), date, time and time zone, the camera's *Smartphone location sync* and *Connect while power off* settings, standby, location intervals, pairing |
| Sony α1 II (ILCE-1M2) | geotagging, time sync, *Keep the camera awake*, location intervals, switched-off detection, power save and switch-on behavior |

**Sony:** any Sony camera that can receive location information from Imaging Edge Mobile or
Creators' App should work, because the app uses the same Bluetooth feature. Reported
working with Alpha GPS: **A1, A7 V, A6400, A6700 and ZV-E10**. Reports about other models,
working or not, are welcome in the
[issue tracker](https://github.com/sarmadjari/GeoShutter/issues).

**Fujifilm:** cameras that geotag through FUJIFILM XApp, tested on the X100VI with
Android; other models with the same app support should work but are untested. The iPhone
app supports them the same way (a first test paired, registered and set up an X100VI;
geotags from the iPhone are still to be checked): there the camera is paired on the app's
first connection, so confirm the code on the iPhone and on the
camera (MENU/OK) within 30 seconds. They need
firmware with Fujifilm's secured Bluetooth connection (from about July 2025); older
firmware with the legacy protocol is detected but not supported.
Add the camera like a Sony camera (pairing mode, then *Add camera*; on the iPhone, the
accessory picker lists Sony and Fujifilm cameras). The camera asks for
the location itself. Remote control is a Sony feature; a Fujifilm camera's details offer
*Set date, time and time zone*, *Smartphone location sync* and the location intervals
instead. With the camera's *CONNECT WHILE POWER OFF* setting on (camera menu: NETWORK/USB
SETTING → Bluetooth/SMARTPHONE SETTING, or *Stay connected when off* in its details in
GeoShutter), a camera that is switched off or asleep stays connected in standby:
GeoShutter shows it as *Standby* (blue) and keeps its location up to date, refreshing your
phone's location once a minute by default, so the first photo after switching the camera
on is tagged right away. With the setting off, the camera disconnects when switched off.
Switching the camera on drops the connection; GeoShutter reconnects within about 10
seconds. The camera list shows the camera's own name (for example `X100VI-…`) with the
model underneath. [docs/fujifilm-protocol.md](docs/fujifilm-protocol.md) has the details
and what is still unverified.

## Build from source

Requirements: a JDK 17 or newer to launch Gradle (Gradle then provisions its daemon JVM,
JetBrains JDK 21, as configured in `gradle/gradle-daemon-jvm.properties`), the Android SDK
with platform 37.1, and for the iOS app a Mac with a current Xcode (26.1 SDK or later).

### Android

```sh
./gradlew :app:assembleGplayDebug        # Google Play flavor (default)
./gradlew :app:assembleFossDebug         # FOSS flavor, no Google Play services or Sentry
./gradlew :app:assembleGplayRelease
```

- Release builds are signed only when `app/keystore.jks` (or `SIGNING_KEYSTORE_PATH`)
  is a non-empty file and `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` and
  `SIGNING_STORE_PASSWORD` are set; otherwise they are unsigned.
- Native libraries are stripped with NDK `29.0.14206865`, pinned for reproducible F-Droid
  builds. Without that NDK the build still works but packages them unstripped.
- Install a debug build on a phone with USB debugging enabled:
  `adb install -r app/build/outputs/apk/gplay/debug/app-gplay-debug.apk`. GeoShutter
  (`com.sarmadjari.geoshutter`) and Alpha GPS (`com.saschl.cameragps`) can be installed
  side by side. Enable each camera in only one of them: otherwise both apps connect to
  it and send locations.

### Crash reporting (optional)

The repository contains no Sentry DSN, so a fresh clone builds apps that never show the
error-reporting consent dialog or setting and never send anything. GeoShutter's own
builds report to the EU-hosted Sentry organization `sarmad-jari` (projects
`geoshutter-android` and `geoshutter-ios`); the keys live only in untracked files:

| | Android (Google Play flavor) | iOS |
|---|---|---|
| DSN | `sentry.dsn=…` in `local.properties` (git-ignored), the Gradle property `sentry.dsn`, or `SENTRY_DSN` | `SENTRY_DSN = https:/$()/…` in `iosApp/Config/Local.xcconfig` (git-ignored, included by `iosApp/Config/GeoShutter.xcconfig`; `//` starts a comment in xcconfig files, hence `$()`). It becomes the `SentryDSN` Info.plist key. |
| Release uploads (ProGuard mappings, source context) | `sentry.org`, `sentry.project` and `sentry.authToken` in `local.properties` (or `SENTRY_ORG`, `SENTRY_PROJECT`, `SENTRY_AUTH_TOKEN`, or a `sentry.properties` file). Without a token nothing is uploaded; `-PdisableSentryUpload=true` turns uploads off anyway. | not set up (uploading dSYMs would be a separate step) |
| CI (`build-and-release.yml`) | secrets `SENTRY_DSN` and `SENTRY_AUTH_TOKEN`, variables `SENTRY_ORG` and `SENTRY_PROJECT` | not built in CI |

A value that isn't a valid DSN (`https://<32-hex public key>@<host>/<project id>`) is
ignored with a build warning (Android) or a log line (iOS), and error reporting stays off.

### iOS

1. Open `iosApp/alphagps.xcodeproj` in Xcode and let it resolve the Swift package
   `sentry-cocoa`.
2. Select the `alphagps` scheme. The *Compile Kotlin* build phase runs
   `./gradlew :sharednew:embedAndSignAppleFrameworkForXcode`, which builds the shared
   Kotlin code as the `sharedKit` framework (Apple silicon simulators and devices).
3. To run it on your iPhone, set your Apple developer team: `DEVELOPMENT_TEAM = …` in
   `iosApp/Config/Local.xcconfig` (git-ignored), or pick it in Xcode's *Signing &
   Capabilities*. The bundle IDs are `com.sarmadjari.geoshutter` (app) and
   `com.sarmadjari.geoshutter.widgets` (the widget and Control Center control, target
   `GeoShutterWidgets`, embedded in the app). Both use the app group
   `group.com.sarmadjari.geoshutter`, which needs a paid Apple Developer Program team
   (a free personal team can't sign app groups).

Bluetooth does not work in the iOS Simulator; test camera features on a real iPhone.

### Tests

```sh
./gradlew :sharednew:testAndroidHostTest       # shared logic (commonTest) on the JVM
./gradlew :sharednew:iosSimulatorArm64Test     # shared + iOS tests on the simulator (macOS, see note)
./gradlew :app:testGplayDebugUnitTest :app:testFossDebugUnitTest
./gradlew :app:lintGplayDebug :app:lintFossDebug   # Android lint (fails on errors)
./gradlew :app:connectedGplayDebugAndroidTest  # instrumented tests, needs a device or emulator
python3 -m unittest discover -s tools/ios_localization -v
(cd tools/sony_shutter && python3 -m unittest test_intervalometer -v)
```

The iOS tests link the Sentry framework that Xcode downloads, so open the project in
Xcode once first (or pass `-Psentry.cocoa.frameworkPath=/path/to/Sentry-Dynamic.xcframework`).
To exercise the app without a camera, use the [Sony camera simulator](tools/sony_camera_sim/README.md).

### Website

```sh
cd website && npm ci && npm run dev    # local preview; npm run build writes website/dist
```

Pushes to `main` that change `website/` deploy it to
[geoshutter.sarmad.no](https://geoshutter.sarmad.no) through GitHub Pages
(`.github/workflows/deploy-pages.yml`; the custom domain is set in the repository's Pages
settings and a DNS CNAME record points it to `sarmadjari.github.io`). To deploy by hand,
run `gh workflow run "Deploy Website to GitHub Pages"` or use *Run workflow* on the
Actions tab.

## Repository layout

| Path | Contents |
|---|---|
| [`app/`](app) | Android app: Compose host, CompanionDeviceManager integration, foreground service, Android Bluetooth and location code, flavors `gplay` and `foss` |
| [`sharednew/`](sharednew) | Kotlin Multiplatform module shared by both apps: Bluetooth protocol and session logic, location transmission, database, most of the UI and all shared strings. `src/iosMain` holds the iOS app logic. |
| [`iosApp/`](iosApp) | Thin SwiftUI/Xcode shell that embeds the shared `sharedKit` framework, and the widget extension (`GeoShutterWidgets`, with `WidgetShared` compiled into both) |
| [`website/`](website) | Astro landing page |
| [`fastlane/metadata/android/`](fastlane/metadata/android) | F-Droid store metadata |
| [`localization/ios/`](localization/ios) | XLIFF files for translating the iOS permission texts on Weblate |
| [`tools/`](tools) | App icon generator, App Store screenshot generator, iOS localization bridge, Sony camera simulator, Python intervalometer |
| [`.github/workflows/`](.github/workflows) | APK release on `v*` tags, website deployment |

## Documentation

- [AGENTS.md](AGENTS.md): project context, conventions and workflows for contributors
  and AI coding assistants. Keep it up to date when things change.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): architecture, Sony Bluetooth protocol and
  platform details.
- [docs/fujifilm-protocol.md](docs/fujifilm-protocol.md): the Fujifilm protocol, its
  sources, verification status and research plan.
- [privacy.md](privacy.md): GeoShutter's privacy policy.
- Tool guides: [app icon](tools/app_icon/README.md),
  [App Store screenshots](tools/app_store/README.md),
  [iOS localization](tools/ios_localization/README.md),
  [Sony camera simulator](tools/sony_camera_sim/README.md),
  [intervalometer](tools/sony_shutter/README.md).

## Contributing

Pull requests are welcome. Please read [AGENTS.md](AGENTS.md) first and update the
documentation together with the code.

Translations live in `sharednew/src/commonMain/composeResources/values-*/strings.xml`
(English source in `values/`); corrections and new languages are welcome as pull
requests. Most of them come from Alpha GPS's
[Weblate project](https://hosted.weblate.org/engage/alpha-gps/).

## Credits and support

GeoShutter is based on **Alpha GPS** by [Saschl](https://github.com/Saschl) and its
contributors ([source](https://github.com/Saschl/alpha-gps),
[website](https://alphagps.app), GPL-3.0). The original app is available on the
[App Store](https://apps.apple.com/us/app/alpha-gps-camera-geotagging/id6760982303) and
[Google Play](https://play.google.com/store/apps/details?id=com.saschl.cameragps).
GeoShutter keeps its license, and its in-app About card names both.

- Fujifilm protocol: [furble](https://github.com/gkoh/furble) by Guo-Rong Koh (MIT
  License).
- Sony protocol: first worked out in
  [anoulis/sony_camera_bluetooth_external_gps](https://github.com/anoulis/sony_camera_bluetooth_external_gps);
  [mlapaglia/AlphaSync](https://github.com/mlapaglia/AlphaSync) provided insights for
  cameras that use Sony's Creators' App instead of Imaging Edge. Sony power findings drew
  on [sarnau/sony-camera-protocol](https://github.com/sarnau/sony-camera-protocol) and
  [ekutner/camera-gps-link](https://github.com/ekutner/camera-gps-link).

If the app helps your photography, consider supporting the author of Alpha GPS:

<a href="https://buymeacoffee.com/wj8tism4dq"><img width="244" height="54" alt="Buy Me a Coffee" src="https://github.com/user-attachments/assets/59ffdcd5-d287-479f-b067-d97b67519691" /></a>

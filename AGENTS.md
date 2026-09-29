# AGENTS.md: GeoShutter project context

Persistent context for AI coding assistants and human contributors. Read it at the start
of every session. **Keep it current**: when a change alters behavior, build setup,
documentation structure or reveals something non-obvious, update this file (and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)) in the same change, and add a line to the
[session log](#9-session-log).

Last full code review: 2026-09-28, app version 1.6.3 (Android `versionCode` 163, iOS build
163). At that point the code was identical to upstream `Saschl/alpha-gps` commit `c36150c`.

## 1. What this project is

- **GeoShutter** (`sarmadjari/GeoShutter`, website https://geoshutter.sarmad.no) is a
  **standalone app** by Sarmad Jari, based on **Alpha GPS** (`Saschl/alpha-gps`, website
  https://alphagps.app), which it credits (README, website, in-app About card). GitHub
  still lists the repository as a fork (*Settings → Danger Zone → Leave fork network*
  would detach it permanently; the maintainer hasn't decided). Git remotes: `origin` = this
  repository, `upstream` = Saschl/alpha-gps (only for reference; see below).
- The app sends the phone's GPS position to **Sony** and **Fujifilm** cameras over
  Bluetooth LE (geotagging), syncs date/time/time zone, works as a Bluetooth remote shutter
  for Sony and can change the camera's own location and time settings. Android and iOS,
  built with Kotlin Multiplatform and Compose Multiplatform. License GPL-3.0. Tested on a
  Fujifilm X100VI and a Sony α1 II (Android).
- **Naming decision (2026-09-28):** the repository documentation is branded GeoShutter.
  The **Android app ID is GeoShutter's own, `com.sarmadjari.geoshutter`, with the
  launcher label "GeoShutter"** (user decision, so it installs next to Alpha GPS). Since
  2026-09-29 the **app calls itself GeoShutter** too: all strings in every language, the
  iOS display name (`INFOPLIST_KEY_CFBundleDisplayName`, `InfoPlist.xcstrings`) and the
  Help links (`ui/help/ProjectLinks.kt`: this repository's README and Issues, no personal
  e-mail). "Alpha GPS" remains only where the app credits it: the About card (license,
  based on Alpha GPS by Saschl, furble) and donations (Help, the donation prompt) that go
  to Saschl. Since 2026-09-29 (standalone) the iOS bundle ID is GeoShutter's own,
  `com.sarmadjari.geoshutter`, the Apple team comes from the untracked
  `iosApp/Config/Local.xcconfig` (`DEVELOPMENT_TEAM`), the iOS Tip Jar (StoreKit products
  of the Alpha GPS App Store app) is gone, and the iOS-internal keys (restore identifier,
  notification and UserDefaults keys) use the `com.sarmadjari.geoshutter` prefix. Alpha
  GPS's internal names remain: the code namespaces (`com.saschl.cameragps`,
  `com.sasch.cameragps.sharednew`), the Xcode project/target/scheme `alphagps` and the
  `ALPHA_GPS_*` debug environment variables. Do not rename these unless the user asks;
  changing an app ID changes the app identity (lost settings/pairings).
- **Upstream is ignored from 2026-09-28 on:** the fork is developed independently; there
  is no need to keep changes merge-friendly with `Saschl/alpha-gps`.
- Cameras: **Sony** and **Fujifilm** with the secure Bluetooth protocol, on Android and
  iOS (tested on the X100VI with Android; other XApp cameras untested; Fujifilm on iOS
  since 2026-09-29: a first test on the maintainer's iPhone paired, registered and set up
  the X100VI, geotags from the iPhone not checked yet). The Fujifilm code started as a
  port of furble and was extended from an analysis of Fujifilm's app; geotagging and the
  date/time/time zone sync work on an X100VI. `docs/fujifilm-protocol.md` is the protocol
  reference. It was developed on the branch `feature/fujifilm-support`.

## 2. Repository map

| Path | What |
|---|---|
| `app/` | Android app (app ID `com.sarmadjari.geoshutter`, launcher label "GeoShutter"; code namespace `com.saschl.cameragps`). Flavors `gplay` (default: Play Services location, Play review, Sentry) and `foss` (none of these). Flavor code in `app/src/gplay` and `app/src/foss`. |
| `sharednew/` | KMP module (`com.sasch.cameragps.sharednew`, note `sasch`). `commonMain`: BLE protocol + session orchestration, location transmission, Room DB, shared Compose UI, strings. `iosMain`: the whole iOS app logic. `androidMain`: small platform bits. Tests in `commonTest`, `iosTest`, `androidHostTest`, `androidDeviceTest`. |
| `iosApp/` | Xcode project `alphagps.xcodeproj` (target/scheme `alphagps`): thin SwiftUI shell, `Info.plist`, `InfoPlist.xcstrings`; `Config/GeoShutter.xcconfig` (base configuration) + untracked `Config/Local.xcconfig`. Widget extension target `GeoShutterWidgets` (folder `GeoShutterWidgets/`: widget, Control Center control) and `WidgetShared/` (status model, views, the control's intent, custom symbols; in both targets). |
| `docs/ARCHITECTURE.md` | Deep technical reference (protocol, flows, platform shells, persistence, CI). |
| `docs/fujifilm-protocol.md` | Fujifilm protocol reference: UUIDs, setup sequence, every message with its bytes, observed camera behavior, the source of each fact, verification status, how to investigate, furble MIT license. |
| `website/` | Astro landing page, deployed to GitHub Pages at https://geoshutter.sarmad.no (`deploy-pages.yml`). |
| `fastlane/metadata/android/en-US/` | F-Droid listing for the `foss` build. |
| `localization/ios/` | XLIFF for Weblate (iOS permission texts), see `tools/ios_localization`. |
| `tools/` | `app_icon` (generates every app icon asset), `app_store` (iOS screenshots), `ios_localization` (XLIFF bridge), `sony_camera_sim` (Bumble fake camera), `sony_shutter` (Python intervalometer), `tests/` (stale Swift test, see §8). |
| `artwork/app-icon.svg` | Master app icon artwork (generated by `tools/app_icon`). |
| `.claude/skills/accessorysetupkit.md` | AccessorySetupKit notes for the iOS app. |
| `.github/workflows/` | `build-and-release.yml` (APKs on `v*` tags), `deploy-pages.yml` (website). |

## 3. Build and test (verified 2026-09-28 on macOS)

```sh
./gradlew :sharednew:testAndroidHostTest                         # commonTest on the JVM (fast, run first)
./gradlew :sharednew:iosSimulatorArm64Test                       # commonTest + iosTest (macOS + Xcode)
./gradlew :app:testGplayDebugUnitTest :app:testFossDebugUnitTest
./gradlew :app:lintGplayDebug :app:lintFossDebug                 # lint gate, must stay at 0 errors
./gradlew :app:assembleGplayDebug   # or :app:assembleFossDebug
./gradlew :app:assembleGplayRelease                             # unsigned without signing env vars
./gradlew :app:connectedGplayDebugAndroidTest                    # needs device/emulator (not run in review)
python3 -m unittest discover -s tools/ios_localization -v
(cd tools/sony_shutter && python3 -m unittest test_intervalometer -v)
(cd tools/sony_camera_sim && python self_test.py)                # needs `pip install -r requirements.txt` (bumble)
(cd website && npm ci && npm run build)
```

- iOS app: open `iosApp/alphagps.xcodeproj`; the *Compile Kotlin* phase runs
  `./gradlew :sharednew:embedAndSignAppleFrameworkForXcode` (framework `sharedKit`,
  `iosArm64` + `iosSimulatorArm64` only). Bundle ID `com.sarmadjari.geoshutter`; set your
  Apple team as `DEVELOPMENT_TEAM` in `iosApp/Config/Local.xcconfig` (git-ignored). Bluetooth
  does not work in the iOS Simulator. A simulator build without signing, from the command
  line (verified 2026-09-29; `GRADLE_OPTS` because of the gotcha below):
  `JAVA_HOME=<JDK 17+> GRADLE_OPTS=-Dorg.gradle.java.home=<JDK 17+> xcodebuild -project
  iosApp/alphagps.xcodeproj -scheme alphagps -configuration Debug -destination
  'generic/platform=iOS Simulator' -derivedDataPath /tmp/gs_ios_build ARCHS=arm64
  CODE_SIGNING_ALLOWED=NO build`; then `xcrun simctl install`/`launch` (a screen can be
  shown directly with `SIMCTL_CHILD_ALPHA_GPS_SCREENSHOT=<scenario>`, e.g. `pairing`).
- **On the maintainer's iPhone** (15 Pro Max, iOS 27, UDID `00008130-00044C883693803A`,
  Developer Mode on; paid team `3653BRXND8`, set in the untracked `Local.xcconfig`;
  verified 2026-09-29): with the same environment, `xcodebuild … -destination
  'id=<UDID>' -allowProvisioningUpdates -allowProvisioningDeviceRegistration build` (the
  last flag registered the iPhone in the developer account), then
  `xcrun devicectl device install app --device <UDID> <…/Debug-iphoneos/alphagps.app>` and
  `xcrun devicectl device process launch --device <UDID> com.sarmadjari.geoshutter`.
- Gradle daemon JVM: JetBrains JDK 21, auto-provisioned via
  `gradle/gradle-daemon-jvm.properties`; any JDK 17+ can launch the wrapper.
- **Machine gotcha:** if Gradle fails with "Value '…' given for org.gradle.java.home Gradle
  property is invalid", `~/.gradle/gradle.properties` points to a JDK that no longer
  exists. Work around with `-Dorg.gradle.java.home=<JDK 17+ home>` (on the maintainer's Mac:
  `~/Library/Java/JavaVirtualMachines/corretto-17.0.20.1/Contents/Home`); ask before
  editing the user's global Gradle config.
- Lint gate: `./gradlew :app:lintGplayDebug :app:lintFossDebug` (0 errors as of
  2026-09-28; `MissingTranslation` is a warning because translations are partial).
  `:sharednew:lint` is not usable as a gate: it reports ~211 `RestrictedApi` errors in
  Room's KSP-generated code.
- Sentry: org `sarmad-jari` (EU region, org id `o4512164121149440`), projects
  `geoshutter-android` (id `4512164155293776`) and `geoshutter-ios` (id
  `4512164158767184`). Nothing Sentry-related is committed: on the maintainer's Mac the
  Android values live in `local.properties` and the iOS DSN in
  `iosApp/Config/Local.xcconfig` (both git-ignored; the DSN public keys are filled in by
  the user, never pasted into chats or commits). Without a valid DSN the apps offer no
  error reporting; uploads only run with an auth token. CI: variables `SENTRY_ORG`,
  `SENTRY_PROJECT` are set on GitHub; secrets `SENTRY_DSN`/`SENTRY_AUTH_TOKEN` are the
  user's to add. See README "Crash reporting (optional)".
- Without NDK `29.0.14206865` builds print "Unable to strip … packaging them as they are";
  harmless locally, but release/F-Droid builds must use that NDK (reproducible builds).
- `:sharednew:iosSimulatorArm64Test` links Sentry Cocoa from Xcode's DerivedData (open the
  project in Xcode once so SPM resolves `sentry-cocoa`), or pass
  `-Psentry.cocoa.frameworkPath=…`.

### Debugging on the maintainer's phone (Samsung SM-F976B, Android 17)

- `adb` is `~/Library/Android/sdk/platform-tools/adb`. Install:
  `adb install -r app/build/outputs/apk/gplay/debug/app-gplay-debug.apk`.
- Shared-code logs (KmLogging) reach logcat with class tags: `CameraSessionOrchestrator`,
  `FujifilmSessionController`, `BleSessionCoordinator`, `BleOperationQueue`,
  `LocationTransmissionManager`. System side: `CDM_DevicePresenceProcessor` (presence
  events 0 appeared, 1 disappeared, 2 BT connected, 3 BT disconnected), `BtGatt`, `smp`,
  `bt_bta_gattc`. Record with `adb logcat -v time -T 1 > file` in the background.
- App-module (Timber) logs are not in logcat: they are in the app's Room database. Debug
  builds allow `adb exec-out run-as com.sarmadjari.geoshutter cat databases/log_database`
  (also `-wal`, `-shm`), then query `log_entries` with sqlite3 (`timestamp` is epoch ms;
  cast when comparing: `timestamp >= CAST(strftime('%s', '…') AS INTEGER) * 1000`).
- `adb shell dumpsys companiondevice` (associations, present devices) and
  `dumpsys bluetooth_manager` (bonds with identity addresses) are the quickest state checks.
- `screencap` on the Fold prints a multi-display warning before the PNG; strip everything
  before the PNG signature.
- After installing a new build, Android rebinds the companion service without repeating
  "appeared"; `RebootReceiver` therefore connects the saved cameras (`ConnectSaved`) when
  the app is enabled. A Fujifilm camera is reached only if it still advertises.
- Quick Settings tile from adb: `adb shell cmd statusbar expand-settings`, then
  `adb shell cmd statusbar click-tile com.sarmadjari.geoshutter/com.saschl.cameragps.status.StatusTileService`.
  With the panel closed the click is queued and delivered when the panel next opens (it
  then toggles once more). `cmd statusbar add-tile …` adds the tile.
- FUJIFILM XApp is installed on this phone for reference. Force-stop it before Fujifilm
  tests (`adb shell am force-stop com.fujifilm.xapp`): it shares the phone's Bluetooth bond
  and would set the camera clock itself. Pairing it with the X100VI gave the camera a new
  identity address, so the camera had to be added to GeoShutter again. The Developer
  options HCI snoop log produced no file here; `dumpsys bluetooth_manager`'s
  BTSNOOP_LOG_SUMMARY keeps only pairing (SMP) packets, not GATT traffic.
- Never run `connectedAndroidTest` on this phone: it uninstalls the app afterwards, which
  deletes the companion associations (every camera must be added again). `am instrument`
  also force-stops the app. Only assemble the instrumented tests
  (`:app:assembleGplayDebugAndroidTest`).

## 4. Architecture in brief

Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

- After service discovery `FujifilmSessionController.detect` picks the protocol: Sony
  (`DD11` present, checked first), Fujifilm secure (status characteristic present; runs
  its own handshake and gets locations only on request), legacy Fujifilm (error), or Sony
  for anything else. See `docs/fujifilm-protocol.md`.
- `CameraSessionOrchestrator` (shared) owns per-device sessions
  (`CameraSessionRegistry` StateFlow observed by both UIs), a sequential
  `BleOperationQueue` per device, the handshake (`BleSessionCoordinator`: DD01 subscribe →
  DD21 read → DD30 → DD31 → CC13 time sync), remote control (`RemoteControlCoordinator`),
  camera settings DD32/DD33 (`CameraAutoCorrectionController`) and location sending every
  5 s (`LocationTransmissionManager`).
- Platform shells implement `BlePeripheralTransport` + `LocationSource` and own the
  lifecycle:
  - Android: `AppServices` (app-scoped graph), CompanionDeviceManager association +
    presence (`CameraDeviceCompanionService`, Android 12+) → foreground
    `LocationSenderService`; optional per-camera *Always On*; `AndroidBleTransport`
    (direct connect on presence, `autoConnect` fallback, bonded devices only).
    `StatusPublisher` drives the status notification, Quick Settings tile and home-screen
    widget from the shared `GeoShutterStatus`; `GeoShutterSwitch` turns the app on (and
    connects cameras that are already on) or off.
  - iOS: `IosBluetoothController` (facade/policy), `IosCentralShell` (CBCentralManager with
    state restoration, created in `AppDelegate` via `ensureInitialized()`),
    `IosBleTransport` (pairing gate), AccessorySetupKit (`IosAccessoryShell`/`Coordinator`)
    for adding, migrating, renaming and removing cameras.
- Persistence: shared Room DB `LogDatabase` v8 (`camera_devices`, `log_entries`), schemas
  in `sharednew/schemas`. Preferences: Android `SharedPreferences` `camera_gps_prefs`,
  iOS `NSUserDefaults` keys `ios.*`.
- Logging: KmLogging in shared code → Timber (Android) / `IosLogging` (iOS) → Room log
  viewer; opt-in Sentry via `CrashReportPolicy`.

## 5. Invariants: do not break

- All orchestrator state is confined to `Dispatchers.Main.immediate`; transport callbacks
  only emit `BleTransportEvent`s.
- Device identifiers are uppercase (Android MAC, iOS peripheral UUID) and live in
  `CameraDevice.mac`.
- Every GATT operation goes through `BleOperationQueue` (one in-flight operation per
  device); never write to a characteristic directly from coordinators or UI.
- `DD01` "location disabled" is advisory only; it must never gate the handshake or sending.
- Sony behavior must not change as a side effect of adding other brands: detection checks
  Sony first, and Fujifilm sessions (`CameraSession.protocol`) are kept out of the Sony
  handlers. Deliberate Sony changes are user decisions (see §7).
- Fujifilm protocol changes need evidence (capture, furble source or observed camera
  response) recorded in `docs/fujifilm-protocol.md`; files porting furble logic credit it
  (MIT) in their header, new files name "Sarmad Jari" as author. Geotag packets contain
  coordinates: log their size, never their bytes.
- `foss` must stay free of Google Play services and Sentry: Google/Sentry dependencies only
  via `gplayImplementation` or `iosMain`; the Sentry KMP plugin keeps `autoInstall`
  disabled. Flavor-specific code goes in `app/src/gplay` + a no-op in `app/src/foss`.
- Crash reporting starts only when enabled **and** the consent dialog was answered
  (`CrashReportPolicy.shouldInitialize`), and is only offered when the build has a
  Sentry DSN (`CrashReporting.AVAILABLE` / `IosCrashReporting.AVAILABLE`). Never
  hard-code DSNs or Sentry orgs. Never log coordinates. Redact MAC addresses in every new
  Sentry path (`CrashReportPolicy.redact`).
- Logging must never crash the app: `LogRepository` drops database failures and writes
  serially. Keep it that way (the iOS database can be unreadable on background relaunches
  before the first unlock).
- Android permission robustness: the permission screen can be skipped ("Continue
  anyway"), so Bluetooth calls that need Nearby devices (`bondedDevices`, device names)
  must go through `bondedAddressesOrNull()` / `nameOrNull()` in `AssociatedDeviceCompat.kt`.
  Service starts from background paths (`startForegroundService`, `startForeground`)
  catch `IllegalStateException`.
- iOS: create the CBCentralManager synchronously on every launch (state restoration); never
  gate it on AccessorySetupKit state. See `.claude/skills/accessorysetupkit.md`.
- New BLE characteristic: add it to `SonyBluetoothConstants`, to
  `IosBleTransport.knownCharacteristicUuids` (iOS ignores unknown ones); for a new service
  also add the service UUID to `IosBleTransport` and to `NSAccessorySetupBluetoothServices`
  in `Info.plist`; add it to `tools/sony_camera_sim` if it should be simulated.
- Room: bump the DB version, add an `AutoMigration` and commit the generated schema JSON in
  `sharednew/schemas`.
- Strings: add English source strings to
  `sharednew/src/commonMain/composeResources/values/strings.xml` (iOS-only ones in
  `sharednew/src/iosMain/composeResources`, Android notification strings in
  `app/src/main/res`). Most translations came from Alpha GPS's Weblate project
  (`alpha-gps`), which GeoShutter no longer syncs with; edit them here, carefully, and keep
  brand names as they are. Changing English source text leaves translations outdated.
  The language picker is generated from the `values-*` folders.
- Releases: bump Android `versionCode`/`versionName` (`app/build.gradle.kts`) **and** iOS
  `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` (`project.pbxproj`); add a
  `ReleaseNotesCatalog` entry + strings for user-facing changes; optionally an F-Droid
  changelog `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- Keep `sentryCocoa` in `gradle/libs.versions.toml` equal to the SPM pin in the Xcode
  project.

## 6. Documentation map: what to update when code changes

| Doc | Depends on these code facts |
|---|---|
| `README.md` (GeoShutter-branded) | features, min OS (Android `minSdk` 26, CDM presence needs Android 12+, iOS 18.0), permissions, Add-camera flows, details/settings lists, troubleshooting (mirrors the in-app guide strings `guide_*`), confirmed cameras, build/test commands, repo layout |
| `docs/ARCHITECTURE.md` | protocol constants and packet layouts, handshake order, timeouts/intervals, class responsibilities, flavors, DB schema, CI |
| `docs/fujifilm-protocol.md` | everything in `bluetooth/fujifilm/` (UUIDs, packets, setup order, subscriptions), the Fujifilm parts of `CameraSessionOrchestrator` (time sync triggers, silence watchdog), `AndroidBleTransport` connection retries, the iPhone parts (`IosAccessoryPickerItems`, `IosBleTransport` gate and services, `IosLocationSource`; section *iPhone*); every hardware observation and its date; keep the evidence tags (F, A, T, M, X) |
| `privacy.md` (GeoShutter's policy: provider Sarmad Jari, contact through GitHub Issues) | every data flow: location use, on-device data, Sentry opt-in and payload, Google Play services / Apple services. Bump "effective as of" on content changes |
| `website/src/pages/index.astro` | features, the Fujifilm section, FAQ (also emitted as JSON-LD), min OS versions, tested and reported cameras (keep in sync with README), credits, links |
| `website/public/seo/og-image.svg` | brand text in link previews |
| iPhone widget and control (`iosApp/GeoShutterWidgets/`, `iosApp/WidgetShared/`, `status/StatusSnapshot.kt`, `status/IosWidgetBridge.kt`) | README (iPhone, *Status at a glance*), `docs/ARCHITECTURE.md` §6 (widget and control row); the JSON keys in `StatusSnapshot.toJson` and `WidgetStatus` must match |
| `fastlane/metadata/android/en-US/*` | F-Droid listing of the `foss` build (GeoShutter name; changelogs of old upstream versions unchanged) |
| `tools/*/README.md` | tool CLIs; the simulator README mirrors the GATT table |
| App and status icons (`artwork/`, Android `ic_launcher_*` and `ic_status_*`, iOS `AppIcon.appiconset` and the symbols in `iosApp/WidgetShared/Assets.xcassets`, F-Droid `icon.png`, `website/public/icon.svg`) | all generated by `tools/app_icon` (`make-icons.mjs`; the shapes are in `shapes.mjs`, the status icons also come from `status-icons.mjs` alone, without dependencies); change the shapes there and rerun, never edit the outputs by hand |
| `.claude/skills/accessorysetupkit.md` | iOS AccessorySetupKit flow |
| In-app help (strings `faq_*`, `guide_*`, `ios_troubleshooting_*`, `HelpScreen.kt`, `IosHelpScreen.kt`) | user-facing docs inside the app; see §8 for open issues |

Website deploy config: `website/astro.config.mjs` (`site` https://geoshutter.sarmad.no) and
`website/public/robots.txt`. GitHub Pages is enabled with GitHub Actions as the source and
the custom domain `geoshutter.sarmad.no` (set in the Pages settings, so no `CNAME` file is
needed). DNS (Domeneshop, name servers `ns*.hyp.net`) has a CNAME record
`geoshutter.sarmad.no` → `sarmadjari.github.io`. GitHub's Let's Encrypt certificate renews
itself and *Enforce HTTPS* is on. If the certificate ever stops being issued, save the
custom domain again (clear it, then set it) to restart provisioning. After changing the
domain or HTTPS setting, run the deploy workflow again: the HTTP→HTTPS redirect only took
effect after a new deployment.

## 7. User preferences and decisions

- Ask (with choices) before design decisions: branding, legal texts, repo settings,
  anything user-visible in the app.
- 2026-09-29: the maintainer asked to remove "AI-looking" characters from the website. Its
  text uses plain punctuation: no semicolons, no en or em dashes, no arrows, and no colons
  that join two clauses (write two sentences). Colons that introduce a list or follow a
  label ("Sony:") are fine. Ask before applying the same sweep to other texts.
- 2026-09-28: rebrand repository docs to GeoShutter; keep describing the shipped app as
  Alpha GPS; privacy provider/contact remain Saschl; repo/issue/privacy links point to
  `sarmadjari/GeoShutter`; store buttons stay on the Alpha GPS listings; GitHub Issues
  were enabled on the fork; in-app text changes were deferred (done 2026-09-29, below).
- 2026-09-28: the Android app gets its own permanent app ID `com.sarmadjari.geoshutter`
  and launcher label "GeoShutter" in all flavors and build types, so it runs next to the
  Play Store Alpha GPS on the maintainer's phone. The iOS bundle ID is unchanged.
- 2026-09-28: every camera, Sony included, connects directly (fast) when Android reports
  it nearby; the camera list shows each camera's own name with brand and model underneath
  (for example "Sony α1 II", "Fujifilm X100VI"; Sony model codes as marketing names).
- 2026-09-28: GeoShutter's state is shown outside the app in all three places the user
  picked: status notification (with *Turn off*), Quick Settings tile (on/off) and
  home-screen widget. The widget is **monitoring only** (no on/off button; a tap opens
  the app). Live Updates / promoted notifications were rejected (Google reserves them for
  user-initiated, time-sensitive activities). Turning GeoShutter on connects right away
  to saved cameras that are already on.
- 2026-09-29: GeoShutter sets a Fujifilm camera's date, time and time zone on every
  connection, as Fujifilm's app does, with a per-camera option (*Set date, time and time
  zone*, on by default) to turn it off. The camera details also offer the camera's own
  SMARTPHONE LOCATION SYNC. setting, like Sony's camera settings.
- 2026-09-29: a Fujifilm camera with location sync off is shown as "Location sync off"
  (amber, with a note on the camera card) and the phone's location isn't used for it.
- 2026-09-29: a Fujifilm camera in standby (switched off or asleep, CONNECT WHILE POWER
  OFF on) is shown as "Standby" with a **blue** dot and keeps receiving the location, so
  the first photo
  after switching on is tagged; while every such camera is off the phone's location is
  refreshed about once a minute instead of every 5 s (user's choice over full rate or
  stopping).
- 2026-09-29: the Fujifilm camera details also offer *Stay connected when off*
  (the camera's CONNECT WHILE POWER OFF), *Location updates while on* (the camera's
  request interval, 10 s–8 min, recommended 10 s; the phone's GPS follows it, as in
  Fujifilm's app) and *Location updates in standby* (30 s–5 min, recommended 1 min).
- 2026-09-29: camera details layout (both platforms): the camera's name as the screen
  title; a status card first (colored dot, state, one explaining line, brand · model ·
  Android address); then settings grouped in cards like the Settings screen: *General*,
  *Location and time* + *Standby* (Fujifilm) or *Date and time* + *Remote control* (Sony),
  *Advanced*; *Delete Device* last, confirmed with the list's swipe dialog. Rows show a
  one-line summary; the long explanation sits behind an ⓘ that stays with the title's last
  word; the whole row toggles its switch. Section headings are small and in the primary
  color (`SharedSettingsCard`, also used by the Settings screen). One status palette for
  the app (`ui/TransmissionDot.kt`: green, blue, amber, red, grey), the same as the
  widget's. Keep new setting texts short (one line in English at phone width).
- 2026-09-29: the app is named **GeoShutter** inside the app on both platforms (all
  languages, iOS home screen). Help links go to this repository (README, Issues) instead
  of Saschl's old repository and personal e-mail; donations stay with Saschl and say so
  (user's choice over removing them or leaving the links).
- 2026-09-29: **GeoShutter is standalone**, crediting Alpha GPS: own iOS bundle ID
  `com.sarmadjari.geoshutter` with the team set locally, no iOS Tip Jar, its own privacy
  policy (provider Sarmad Jari, contact through GitHub Issues), its own website at
  https://geoshutter.sarmad.no (GitHub Pages, custom domain; repository homepage and
  description updated), README and website without Alpha GPS store buttons (they are in
  the credits), and an About card crediting Alpha GPS (GPL-3.0) and furble (MIT).
- 2026-09-29: Sony features after hardware tests on the α1 II (the maintainer chose all
  four offered): *Keep the camera awake* per camera (**off by default**: Sony's power save
  and camera battery as before), *Location updates while on* for Sony (5–30 s, default and
  recommended 5 s, the old fixed value), a switched-off camera still connected through
  *Cnct. while Power OFF* shown as *Switched off* (red) with no location, and faster
  reconnects (none found; see §8). A Fujifilm-style standby isn't possible on Sony (it
  drops the connection when switched on and ignores locations sent while off).
- 2026-09-29: new app icon, the maintainer's pick of five concepts: camera focus
  brackets (white) around a coral location pin with a white dot, on a deep navy gradient
  (#1F2A3C → #0B111C, pin #FF5A4E). Android: adaptive icon with a one-color themed layer;
  the splash screen shows the launcher icon. iOS: default, dark and tinted variants.
- 2026-09-29: the status icons (notification and status bar, Quick Settings tile, widget
  header) use the app icon's elements instead of Material's GPS icons: the frame (focus
  brackets) with the pin while a camera receives the location, the empty frame while
  GeoShutter waits, a camera is in standby or GeoShutter is off. The maintainer's pick of
  three options (the others: an outlined pin, with or without a dot), like the old icons,
  which dropped their center dot. Drawables `ic_status_sending`/`ic_status_waiting` and
  SVG masters `artwork/status-icon-*.svg`, all generated by `tools/app_icon`.
- 2026-09-29: **iPhone widget and Control Center control** (the maintainer asked after
  the Android status icons changed): a home-screen widget (small, medium) like Android's
  and a Control Center/Lock Screen control (on/off) like the Quick Settings tile, both
  with the status symbols. Swift in a widget extension; the app publishes its status as
  JSON into the app group `group.com.sarmadjari.geoshutter`; the control's intent is a
  `LiveActivityIntent`, so it runs in the app. Not tested on a device (§8).
- 2026-09-29: **Fujifilm on iOS** (the maintainer asked for parity; no iPhone to test
  with, so it is built and unit-tested only). Design: the AccessorySetupKit picker gets a
  second item for Fujifilm (`0x04D8`) **without** the picker's own Bluetooth pairing, so the
  app's first connection pairs and registers the camera (the camera must be set up on the
  connection that pairs it); the iOS transport's pairing gate reads the Fujifilm status
  instead of subscribing. The brand comes from the picker item (`IosAccessoryShell.brandOf`)
  and drives the Fujifilm details, the model line (now also shown on iOS, Sony included)
  and the list name (the NOT4 name is kept over the Bluetooth name). The iOS onboarding
  texts no longer say "Sony camera" (all languages); the iOS pairing screen explains the
  Fujifilm code confirmation (English and German, others fall back to English).

## 8. Known issues and follow-ups (not fixed yet)

- Shared FAQ strings: `faq_permissions_answer` (also shown on iOS) lists Android-only items
  (battery optimization, Nearby devices); `faq_connect_camera_answer` describes an old flow
  ("click on the camera on the main screen") instead of *Add camera*. The translations of
  `how_about_privacy_answer` don't have the English sentence about opt-in error reports
  (Chinese mentions it).
- `tools/tests/AccessoryDiscoveryItemsTests.swift` uses the old generic
  `AccessoryDiscoveryItems<Accessory, String>` API; the class is now concrete and iOS 26.1
  only, so the test no longer compiles. It is not wired into any build.
- `settings.gradle.kts` references a `:camerasim` module that is not in the repository.
- F-Droid: `phoneScreenshots` are outdated iPhone captures (pre-AccessorySetupKit UI); only
  changelog `150.txt` exists; the inclusion request (fdroiddata MR !45654) was still open
  on 2026-09-28.
- The README has no screenshots (the old ones showed an outdated UI). Regenerate iOS shots
  with `tools/app_store`; Android has a `SCREENSHOT_MODE` flag in
  `app/.../ui/device/ScreenshotMockData.kt`.
- `values-fa` is empty (Persian appears in the language picker with English text);
  `values-ro` and `values-ja` are partial.
- Fork CI: `build-and-release.yml` needs `KEYSTORE_BASE64` and `SIGNING_*` secrets for
  signed APKs (Sentry secrets/variables are optional). Without them the build now
  succeeds but produces `*-release-unsigned.apk`, which the release step's file list
  (`app-*-release.apk`) doesn't match. No GitHub release has been published yet.
- Website: the link-preview image is an SVG, which most social networks don't show.
- Fork CI: pushes to `main` have never started a workflow run (not even ones touching
  `website/**`), while `workflow_dispatch` works. GitHub keeps push-triggered workflows off
  on forks until they're enabled on the repository's Actions tab. Until then, deploy the
  website by hand: `gh workflow run "Deploy Website to GitHub Pages" -R sarmadjari/GeoShutter`.
- `Info.plist` has both `NSAccessorySetupKitSupports` and `NSAccessorySetupSupports`; the
  second looks redundant.
- Fujifilm: see "Known gaps" in `docs/fujifilm-protocol.md`. Geotagging (EXIF position
  within 1 m, GPS time in UTC), the date/time/time zone sync, the location sync setting and
  standby and registration on the pairing connection work on an X100VI (firmware 01.32,
  2026-09-28/29); open: iOS untested (below), legacy firmware unsupported, remote
  Sony-only, the geotag speed field and fix time, the 7-byte local-time fallback. Each pairing gives the camera
  a new Bluetooth address. The camera applies the time only
  when it asked (NOT1, first connection after switching on or waking); writes at other
  times are accepted and ignored, so switching the option on takes effect at the next
  switch-on. The X100VI sometimes stays silent on a connection (no requests, ignores the
  time); the watchdog that repeats the setup and then reconnects is only unit-tested so
  far. Look for "stays silent after setup" in logcat to see it act. The camera ends the
  next connection about 20 s after the phone closed one; Fujifilm's app writes a
  disconnect reason first (not tried).
- iOS, first on-device test (2026-09-29, iPhone 15 Pro Max, iOS 27, from the app's log):
  the X100VI paired, registered and was set up on the app's first connection, with the
  time, NOT4 name and standby states; the α1 II paired in the picker, was set up in about
  a second, and a switched-off α1 II (*Cnct. while Power OFF*) was detected by ATT 0x9D
  (157) as on Android. iOS reported `didModifyServices` with an empty list on the α1 II
  (ignored). Still to check: geotags in the photos from the iPhone, the time applied by the
  X100VI, long standby in the background, the watchdog's reconnect. An iPhone that still
  had an old pairing with the α1 II (the camera had deleted it) failed with "Peer removed
  pairing information" and the pending connect was re-issued about four times a second
  until the camera was removed in the app; a back-off or a re-pair hint would be better.
- iOS: the direct connects are Android-only. The iPhone widget and Control Center control
  (2026-09-29) run on the maintainer's iPhone. The first test showed the α1 II amber on the
  widget (the one second it was being set up before it was found switched off) while the
  app showed it red: iOS had skipped the reload that followed. Fixed the same day: a
  camera being set up counts as not connected (as in the app), only states that lasted
  2 s are written, a quick second change gets one more reload 10 s later, and leaving the
  app reloads. Still to check on the device: the control with the app in the background
  and killed. They show the last status the app wrote (stale after the app is killed);
  iOS rations background reloads. Signing needs a paid Apple Developer Program team (app
  group).
- Both platforms: the *Pairing Failed* dialog's hints use Sony's menu path (MENU →
  Network → Bluetooth), also for Fujifilm cameras.
- Sony α1 II ends the connection itself (status 19) after 23–209 s, usually about a
  minute. Cause found 2026-09-29: its power save (Power Save Start Time, 1 minute by
  default) ends the connection about 30 s after the screen goes dark, and it comes back
  when woken; photos right after waking get no location. Fixed per camera by *Keep the
  camera awake* (off by default, the maintainer's choice); otherwise inherent.
- Sony: a switched-off camera is detected only by its ATT error 0x9D (verified on
  Android; iOS maps `CBATTErrorDomain` codes the same way, untested). A camera switched
  off with *Cnct. while Power OFF* on still accepts locations for up to about 1.5 minutes
  before it refuses them, so it shows as receiving until then. The camera simulator
  (`tools/sony_camera_sim`) has neither `CC02` nor the 0x9D refusal. The advertising power
  bits (docs/ARCHITECTURE.md §3, Power) are not used.
- Sony reconnect speed: after switching on, the α1 II needs about 12 s until it can be
  connected, then about 3 s of setup, most of it Android re-reading its services after the
  camera's Service Changed indication (inherent). No safe way to shorten it was found.
- An X100VI that is on but lost its connection more than about half a minute ago no
  longer advertises, so turning GeoShutter on can't reach it until it is woken or switched
  off and on (see `docs/fujifilm-protocol.md`, Camera behavior).
- iOS crash reports are not symbolicated automatically: no dSYM upload is set up (options:
  a sentry-cli build phase using an auth token, or Sentry's App Store Connect
  integration).
- The Settings screen still mixes styles: *App Controls* and *Log Settings* are
  `SharedSettingsCard`s (the log level in a nested surface), the entries below them
  (*Location Provider*, *Transmission Event Sounds*, …) are plain navigation cards.

### Fork status (checked 2026-09-28)

- `main` is identical to `upstream/main` (`c36150c`). The fork has only `main` and **no
  tags** (upstream has 88: release tags up to `v1.6.3` plus `beta` and `manual-build-*`).
  Pushing `v*` tags would trigger `build-and-release.yml` once workflows are active.
- GitHub Actions: no workflows registered and no runs yet (forks need workflows enabled in
  the Actions tab); no secrets or variables; Pages disabled.
- Security settings: Dependabot alerts and security updates disabled; secret scanning and
  push protection enabled; `main` unprotected. Upstream updates dependencies with
  Renovate, which is not installed on the fork, so dependency updates only arrive by
  merging upstream.
- Repository metadata (updated 2026-09-29): description about Sony and Fujifilm, "Based
  on Alpha GPS"; homepage https://geoshutter.sarmad.no; no topics; `.github/FUNDING.yml`
  shows Saschl's Buy Me a Coffee (donations go to him, the user's choice).
- Upstream identity baked into the code: resolved on 2026-09-29 (own iOS bundle ID and
  team setting, no Tip Jar, own Help links and privacy policy); only internal code names
  remain (see §1). (The upstream Sentry DSNs/org were removed on 2026-09-28; Sentry is now
  configured per build.)
- Upstream is actively working on branches not in the fork, e.g. `feat/unify-more-ui`
  (40 commits ahead of `main`) and `feat/wifi-features` (photo browser, 9 commits) as of
  2026-09-28; expect larger merges when they land.
- Tracked files that `.gitignore` excludes: `.idea/*` and upstream's Xcode user data
  `iosApp/alphagps.xcodeproj/project.xcworkspace/xcuserdata/sasch.xcuserdatad/…`.

## 9. Session log

- 2026-09-28: Full study of Android, shared and iOS code, tools, website and metadata.
  Aligned docs with the code: rewrote `README.md` (GeoShutter branding, both platforms),
  corrected `privacy.md` (opt-in reporting, `foss` without Sentry, on-device data, Apple
  and Google services; effective date 2026-09-28), rebranded the website and OG image,
  updated the F-Droid description and the simulator README, added
  `tools/sony_shutter/README.md`, `docs/ARCHITECTURE.md`, this file, `CLAUDE.md` and the
  AccessorySetupKit notes. Enabled GitHub Issues on `sarmadjari/GeoShutter`. Verified the
  commands in §3 (except the connected Android tests and the camera simulator self-test).
  A tool in the maintainer's editor appended `AGENTS.md`/`CLAUDE.md` to `.gitignore`; the
  user chose to keep both files committed, so that change was reverted. If it happens
  again, ask before touching `.gitignore`.
- 2026-09-28: Audited the fork on GitHub and locally; results in §8 "Fork status". No
  settings changed in this pass.
- 2026-09-28: Robustness pass (upstream is ignored from now on). `LogRepository` drops
  database failures instead of crashing, writes serially and counts the table only every
  50 inserts; iOS in-app logs keep stack traces; the log viewers reuse one date formatter
  per update. Sentry is configured per build (no DSN in the repo → no error reporting;
  uploads only with an auth token; plugin telemetry off); Android `gplay` redacts MAC
  addresses in events and breadcrumbs too. Android: permission-safe bond/name reads,
  background service starts catch `IllegalStateException`, the boot/update receiver
  honors *Enable App*, connects that can't start no longer stay "connecting", Always-On
  reconnects also stop on Bluetooth off, no CDM IPC during composition, release signing
  ignores blank CI secrets, app lint back to 0 errors, KSP/lint task wiring fixed.
  Verified with JVM and iOS simulator unit tests, app lint, all four APK variants and an
  iOS simulator build (DSN build setting reaches Info.plist).
- 2026-09-28: Connected the fork to the user's Sentry (`sarmad-jari`, EU). DSNs stay out
  of git by the user's choice: Android reads `local.properties`, iOS uses the untracked
  `iosApp/Config/Local.xcconfig` via the new base configuration `GeoShutter.xcconfig`;
  templates with org/project IDs were created locally, the user pastes the public keys.
  DSNs are validated (`CrashReportPolicy.isValidDsn`) so placeholders never enable
  Sentry. GitHub variables `SENTRY_ORG`/`SENTRY_PROJECT` set.
- 2026-09-28: Committed the day's work on `main` (3 commits, not pushed) and added
  experimental Fujifilm support on `feature/fujifilm-support`, re-implemented from furble
  (the earlier patch mentioned in the user's design note was not available): constants,
  packet builder, handshake controller, detection, pull-based delivery, service-scoped
  operations and indications on Android, CDM filter, altitude in `GeoLocation`, 17 tests,
  `docs/fujifilm-protocol.md`. All 23 UUIDs cross-checked against furble; packets checked
  against Python `struct.pack`. Detection sets the protocol on every connect, and remote
  monitoring is never started for Fujifilm sessions. Verified: JVM and iOS simulator
  tests, app tests and lint, all four APK variants, iOS simulator app build. Nothing is
  verified on a real Fujifilm camera yet.
- 2026-09-28: Android app ID changed to `com.sarmadjari.geoshutter` (launcher label
  "GeoShutter") on `feature/fujifilm-support`. Installed `gplayDebug` over USB on the
  maintainer's Samsung SM-F976B (Android 17, SDK 37) next to the Play Store Alpha GPS
  v1.6.2; it launches without crashes. Install command:
  `adb install -r app/build/outputs/apk/gplay/debug/app-gplay-debug.apk` (adb lives in
  `~/Library/Android/sdk/platform-tools`).
- 2026-09-28 (evening): first hardware tests with an X100VI and a Sony α1 II on that phone.
  Fujifilm pairing uses numeric comparison: the camera needs MENU/OK, the phone confirms by
  itself. The camera refused the handshake until its pairing screen ended (bond made
  without registration), then geotagging worked end to end (request every 10 s, location
  in photos). Fixes: connect right after pairing for Fujifilm; on Android 16+ keep a camera
  that stops advertising while connected (end on BT disconnect instead); direct connect for
  Fujifilm cameras on presence (they advertise only briefly after switching on); restart
  setup with retried rediscovery when a camera changes its services (the α1 II did so after
  encryption while the phone's cached table was stale, which stalled the Sony setup at
  `DD30`, status 159). Observed: the X100VI stays connected while switched off. Findings
  and the GATT table are in `docs/fujifilm-protocol.md`. 20 tests in `FujifilmSessionTest`;
  full suite green (185 JVM, 222 iOS simulator, app tests, lint, four APKs).
- 2026-09-28 (night): camera list shows each camera's own name with the model underneath
  (user's choice). For Fujifilm the name comes from NOT4 ("FUJIFILM-X100VI-…"; the GAP
  device name is only "X100VI"), stored unless renamed. A diagnostic build (not
  committed) watched all readable and notifying X100VI characteristics through on/off:
  nothing changes, so on/off can't be shown. Fujifilm direct connections are now retried
  three times before falling back to autoConnect: back within about 11 s after switching
  on (was 26 s to 1.5 min, once never).
- 2026-09-28 (night, later): direct connect on presence for Sony too (user's request) and
  Sony model names under the camera name (`cameraModelName`). On the α1 II the link came
  up 0.06–0.2 s after the camera appeared; it sent Service Changed right after encryption
  on each connection, and the restart/rediscovery handling made setup complete 3–5 s after
  the camera appeared (first hardware run of that code).
- 2026-09-28 (late night): GeoShutter's state outside the app. Shared `GeoShutterStatus`
  (3 tests); Android `StatusPublisher` drives one status notification (replaces
  `AndroidTransmissionNotificationPublisher`; foreground while the service runs, a quiet
  "waiting" notification otherwise, *Turn off* action), a Quick Settings tile (on/off) and
  a Glance home-screen widget, made monitoring-only at the user's request. Turning
  GeoShutter on (tile, *Enable App*) and app updates send `ConnectSaved`: direct
  connections to every enabled saved camera; the service stops after 40 s if none
  connected and none is Always On. On the phone an α1 II that was already on connected
  0.2–0.3 s after turning on and received locations 2–4 s later; an X100VI that had been
  on without a connection could not be reached (it had stopped advertising). Full suite
  green: 192 JVM tests, app tests, lint (0 errors), four APKs, instrumented-test APK.
- 2026-09-29 (night): Fujifilm date, time and time zone. A test showed the X100VI doesn't
  set its clock from the geotag packet. The research agent and the maintainer's static
  analysis of FUJIFILM XApp 1.0.3/2.7.6(1) (kept outside the repository) found the time
  service `e872b11f-…`/`c52edbce-…` (12 bytes: UTC, standard offset in hundredths of an
  hour, DST flag) and NOT7 = SMARTPHONE LOCATION SYNC. (uint16). GeoShutter now writes the
  time after each Fujifilm handshake, on NOT1 (at most every 10 s), when a silent camera
  first responds and when the option is turned on; the camera applied the date, time,
  AREA SETTING and DAYLIGHT SAVINGS. New per-camera option (database version 7,
  `timeSyncEnabled`) and the Fujifilm location sync setting in the camera details
  (brand-aware; Sony rows hidden for Fujifilm). Found silent X100VI connections and that
  the camera drops a connection about 20 s after the phone closed the previous one; added
  a silence watchdog and made the status show a silent Fujifilm camera as connecting.
  208 JVM tests pass (16 new).
- 2026-09-29 (morning): rewrote `docs/fujifilm-protocol.md` as a structured protocol
  reference (summary and UUID table, sources with evidence tags, name mapping furble ↔
  Fujifilm's app, advertising, pairing and registration, GATT database, setup sequence
  with a diagram and bytes, every message, runtime behavior, observed camera behavior,
  implementation map, verification status, gaps, how to investigate). Byte examples were
  recomputed with Python `struct` and match the unit tests; every UUID constant is in the
  doc and every full UUID in the doc matches a constant or a cited source. Corrected two
  details on the way: the camera ends the connection after a phone-closed one after 18–21 s
  (seen four times), and the silent-connection triggers.
- 2026-09-29 (late morning): finished the Fujifilm tests on the X100VI with the maintainer.
  A camera with location sync off still notifies NOT1 and the interval echo, so the
  silence watchdog leaves it alone; new "Location sync off" state (amber) and location
  tracking only while a ready camera wants it (the setting is read during setup, so no
  "Sending" flash and no GPS start). Found that the camera applies the time only when it
  asked (first connection after switching on or waking) and removed the ineffective
  immediate/extra writes. From the maintainer's XApp technical reference: the power switch
  characteristic (`f90f7d3a`, startup information service `804daa8e`) reads `01 02` on
  and `00 01` off in standby; new "Camera off" state (blue), locations kept flowing, the
  phone's location about once a minute while all cameras are off (verified: GPS on once a
  minute; a photo 2 s after switch-on carried the standby location, 1 m from the phone,
  GPS time UTC). CONNECT WHILE POWER OFF off: switching off disconnects. Status count
  strings are plurals now. Tests: 215 JVM, 252 iOS simulator, app tests, lint 0 errors.
- 2026-09-29 (noon): fresh pairing test passed: GeoShutter connected 30 ms after the bond
  and the setup was accepted on the pairing connection (no refusal). The camera got its
  third Bluetooth address in two days (one per pairing).
- 2026-09-29 (after noon): the maintainer saw a sleeping X100VI (automatic power off) shown
  green. Its power switch reads `01 01` (switch on, in the background) while asleep, which
  the first build treated as awake. Now anything but `01 02` counts as standby; the state
  and texts are "Standby" (switched off or asleep) instead of "Camera off", and the raw
  value is logged on changes ("is in standby (power switch 01 01)"). Verified on the camera.
- 2026-09-29 (afternoon): camera settings from the app. `REMOTE_BOOT_SETTING` (`7170fd5a`)
  is the X100VI's CONNECT WHILE POWER OFF: it reads `01 00` (two bytes, although
  Fujifilm's app writes one), and writing `00 00`/`01 00` switched the menu setting (checked
  on the camera). The sync interval is now per camera (database v8) and written at setup
  and at once when changed (20 s verified: requests every 20 s). The phone's location
  interval follows the most demanding camera (Sony 5 s, Fujifilm its interval, standby its
  standby interval); a stationary phone may get fewer GPS fixes (Google's service). The
  camera settings controller logs values it can't decode.
- 2026-09-29 (late afternoon): UI/UX pass on the camera details at the maintainer's
  request ("text not well placed or organized"). Before: titles wrapped letter by letter
  next to wide slider values, one flat list, *Remove* at the top, the name twice and the
  app name as the title. Now the layout in §7, shared by Android and iOS (iOS gained
  *Delete Device*). The camera card's long standby and location-sync-off notes became
  one-line status lines, and its dot uses the shared palette (amber for location sync
  off). Fixed on the way: the open camera details closed whenever the activity was
  recreated (dark mode switch, folding, rotation); the selected camera is now saved by
  address (`rememberSaveable` in `CameraDeviceManager`). Checked on the SM-F976B in light
  and dark mode with the X100VI (standby, connecting) and the α1 II (not connected): info
  dialogs, row toggling, delete dialog (cancelled).
- 2026-09-29 (afternoon): renamed the app to GeoShutter inside the app (all languages,
  including a Tamil transliteration the first scan missed; iOS display name), pointed Help
  at this repository and credited Saschl for donations (§7). Then a new app icon (§7):
  concepts rendered with resvg, the chosen one generated by `tools/app_icon` (sharp).
  Checked on the SM-F976B: app drawer icon, splash screen, Help screen; `actool` compiles
  the iOS catalog without warnings. The old themed icon was a plain disc (its monochrome
  layer reused the full-color foreground).
- 2026-09-29 (late afternoon): Sony power investigation with the maintainer's α1 II and a
  research pass. A probe build (not committed; `files/sony-probe.diff` in the session
  folder) logged the α1 II's full GATT (services BB00, CC00 with cc02–ccb0, DD00, EE00,
  FF00) and every notification while switching off/on and idling. Findings in
  docs/ARCHITECTURE.md §3 (Power). Built: keep-awake (`CC02` `03 08 10 00` every 5 s),
  Sony send interval (database v9: `sendIntervalS`, `keepAwakeEnabled`), switched-off
  detection (ATT 0x9D → `CameraSession.cameraOff`, retried every 30 s), UI rows in the
  Sony details (*Location and time*, *Power*) and a *Switched off* status. Verified on the
  camera: keep-awake held the connection over 2 minutes with the screen on; 15 s interval
  applied at once; switching off with *Cnct. while Power OFF* on showed *Switched off* and
  stopped the Sony location; switching on reconnected and resumed. The maintainer's α1 II
  was left with keep-awake off and 5 s; *Cnct. while Power OFF* was turned on for the test.
- 2026-09-29 (evening): made GeoShutter standalone with credits (§7): iOS bundle ID, team
  in `Local.xcconfig`, iOS-internal key prefixes, Tip Jar removed (the donation prompt opens
  Saschl's Buy Me a Coffee page), About card with license and credits (`AboutCredits`,
  both platforms), privacy policy rewritten (also mentions Fujifilm standby and the
  switched-off Sony case), README (install, tested cameras X100VI and α1 II, credits) and
  website rewritten with a Fujifilm section, F-Droid description, old root `CNAME`,
  `icon.svg` and App Store badge removed. Enabled GitHub Pages (Actions) with the custom
  domain and updated the repository description and homepage. The push didn't start the
  deploy workflow (see §8), so it was run by hand. The maintainer then added the DNS
  record; saving the custom domain again started the certificate (approved within a
  minute), HTTPS was enforced, and a second deployment turned on the HTTP→HTTPS redirect.
  https://geoshutter.sarmad.no is live. Right after a new DNS record, a resolver that looked
  the name up before (here the home router) keeps the "doesn't exist" answer for up to an
  hour (the zone's negative TTL is 3600 s).
- 2026-09-29 (evening): website punctuation made plain (no semicolons or dashes, §7).
  Then **Fujifilm on iOS** (§7): picker item for `0x04D8` without the picker's pairing,
  `Info.plist` company ID, `IosBleTransport` reworked (Sony and Fujifilm services only,
  lookup by service, `BleUuids.normalize`, Fujifilm status read as the pairing gate, stale
  gate answers dropped, `reconnect()`, `didModifyServices` → `ServicesChanged`), brand
  from the picker item (`brandOf`, `BluetoothDeviceInfo.brand`), model line on iOS, the
  Fujifilm name kept (`AccessoryCameraName.resolve(preferSavedName)`), Fujifilm settings
  in the iOS details data source, `IosLocationSource.setUpdateInterval` (ten-metre accuracy
  from 60 s), the pairing screen's Fujifilm note, brand-neutral onboarding texts in all
  languages. A code review found that a gate still waiting for a person outlived the
  queue's 30 s discovery timeout (a late pairing then went unused): fixed with
  `BlePeripheralTransport.finishDiscovery` (the queue tells the transport it stopped
  waiting), a 90 s discovery budget on iOS (`CameraSessionOrchestrator(discoveryTimeoutMs)`)
  and one retry for the Fujifilm gate. Verified: JVM 235 and iOS simulator 274 tests, a
  simulator build of the app (screenshot of the pairing screen). Not tested with a camera
  on an iPhone (§8).
- 2026-09-29 (night): status icons from the app icon (§7, option C of three shown in a
  browser preview): notification/status bar, Quick Settings tile and widget use
  `ic_status_sending` (frame with the pin) and `ic_status_waiting` (the frame alone),
  replacing Material's GPS icons; SVG masters in `artwork/`. `tools/app_icon` now keeps
  the shapes in `shapes.mjs`; `status-icons.mjs` writes the status icons (also run by
  `make-icons.mjs`; its other outputs came out byte-identical). Verified on the phone:
  status bar, widget and tile show the empty frame while waiting.
- 2026-09-29 (night): **iPhone widget and Control Center control** (§7). Kotlin: shared
  `cameraState()` and `headline()` (`StatusHeadline`, also used by the Android widget),
  `StatusSnapshot` + JSON (`commonMain`), `IosWidgetBridge` (app group writer, reload
  hook from Swift) and `IosStatusPublisher`, `IosBluetoothController.appEnabledState` and
  `setAppEnabled` (Settings and the control; the UI observes the flow), iOS strings
  `widget_*` (en, de). Swift: new target `GeoShutterWidgets` (project edited by script:
  synchronized folders `GeoShutterWidgets/` and `WidgetShared/`, embed phase, dependency),
  `StatusWidget`, `GeoShutterControl`, `SetGeoShutterEnabledIntent` (SetValue +
  LiveActivity intent), `WidgetBridge`, `WidgetPreviewScreen`, `Localizable.xcstrings`
  (en, de), app group entitlements. `tools/app_icon/status-icons.mjs` also writes the
  custom SF Symbols (SF Symbols template, frame strokes outlined). Verified: JVM 239 and
  iOS simulator 278 tests, lint, a simulator build with the extension embedded (symbols
  and intents metadata in both bundles), the preview screen, and the app's JSON read back.
  A code review found the widget texts stayed in the old language after a change in the
  app's language picker (the process keeps running): the publisher now reloads them from
  `appLanguagePreference.selected`.
- 2026-09-29 (night): installed GeoShutter on the maintainer's iPhone 15 Pro Max over
  USB-C (Debug build, team 3653BRXND8, app and widget extension with the app group); the
  iPhone had to have Developer Mode turned on and was registered in the developer account
  by xcodebuild. First on-device tests of the iPhone app (Fujifilm, widget, control) are
  up to the maintainer.
- 2026-09-29 (night): first on-device test of the iPhone app (logs copied from the app's
  container with `xcrun devicectl device copy from --domain-type appDataContainer
  --domain-identifier com.sarmadjari.geoshutter --source Documents/log_database.db`, plus
  `-wal`/`-shm`, then read with sqlite3). Fujifilm and Sony worked (§8). The widget showed
  a stale amber state for the switched-off α1 II: fixed (§8, `iosStatus`, debounce 2 s,
  settle reload, reload on leaving the app; `IosStatusTest`), rebuilt and reinstalled.

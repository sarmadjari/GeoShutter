# AGENTS.md: GeoShutter project context

Persistent context for AI coding assistants and human contributors. Read it at the start
of every session. **Keep it current**: when a change alters behavior, build setup,
documentation structure or reveals something non-obvious, update this file (and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)) in the same change, and add a line to the
[session log](#9-session-log).

Last full code review: 2026-09-28, app version 1.6.3 (Android `versionCode` 163, iOS build
163). At that point the code was identical to upstream `Saschl/alpha-gps` commit `c36150c`.

## 1. What this project is

- **GeoShutter** (`sarmadjari/GeoShutter`) is a fork of **Alpha GPS**
  (`Saschl/alpha-gps`, website https://alphagps.app). Git remotes: `origin` = fork,
  `upstream` = Saschl/alpha-gps. Check `git log main..upstream/main` for new upstream work
  before larger changes.
- The app sends the phone's GPS position to **Sony** cameras over Bluetooth LE
  (geotagging), syncs date/time/time zone, works as a Bluetooth remote shutter and can
  toggle the camera's automatic time correction and area adjustment. Android and iOS,
  built with Kotlin Multiplatform and Compose Multiplatform. License GPL-3.0.
- **Naming decision (2026-09-28):** the repository documentation is branded GeoShutter.
  The **Android app ID is GeoShutter's own, `com.sarmadjari.geoshutter`, with the
  launcher label "GeoShutter"** (user decision, so it installs next to Alpha GPS). Everything
  else still belongs to upstream (Saschl): the code namespace/packages
  (`com.saschl.cameragps`, `com.sasch.cameragps.sharednew`), the in-app name "Alpha GPS"
  (strings), the iOS bundle ID `com.saschl.cameragps` and display name, StoreKit product
  IDs, the Core Bluetooth restore identifier and the store listings. Do not rename further
  identifiers unless the user asks for it; changing an app ID changes the app identity
  (new store listing, lost settings/pairings).
- **Upstream is ignored from 2026-09-28 on:** the fork is developed independently; there
  is no need to keep changes merge-friendly with `Saschl/alpha-gps`.
- Cameras: **Sony** (Android and iOS) and, experimentally on Android, **Fujifilm** with the
  secure Bluetooth protocol (X100VI and other XApp cameras). The Fujifilm code is ported
  from furble and not yet verified on hardware; `docs/fujifilm-protocol.md` holds the
  protocol, its evidence and the research plan. It was developed on the branch
  `feature/fujifilm-support`.

## 2. Repository map

| Path | What |
|---|---|
| `app/` | Android app (app ID `com.sarmadjari.geoshutter`, launcher label "GeoShutter"; code namespace `com.saschl.cameragps`). Flavors `gplay` (default: Play Services location, Play review, Sentry) and `foss` (none of these). Flavor code in `app/src/gplay` and `app/src/foss`. |
| `sharednew/` | KMP module (`com.sasch.cameragps.sharednew`, note `sasch`). `commonMain`: BLE protocol + session orchestration, location transmission, Room DB, shared Compose UI, strings. `iosMain`: the whole iOS app logic. `androidMain`: small platform bits. Tests in `commonTest`, `iosTest`, `androidHostTest`, `androidDeviceTest`. |
| `iosApp/` | Xcode project `alphagps.xcodeproj` (target/scheme `alphagps`): thin SwiftUI shell, `Info.plist`, `InfoPlist.xcstrings`; `Config/GeoShutter.xcconfig` (base configuration) + untracked `Config/Local.xcconfig`. |
| `docs/ARCHITECTURE.md` | Deep technical reference (protocol, flows, platform shells, persistence, CI). |
| `docs/fujifilm-protocol.md` | Fujifilm protocol with furble evidence, verification status, research plan, furble MIT license. |
| `website/` | Astro landing page (upstream deploys it to alphagps.app). |
| `fastlane/metadata/android/en-US/` | F-Droid listing for the `foss` build. |
| `localization/ios/` | XLIFF for Weblate (iOS permission texts), see `tools/ios_localization`. |
| `tools/` | `app_store` (iOS screenshots), `ios_localization` (XLIFF bridge), `sony_camera_sim` (Bumble fake camera), `sony_shutter` (Python intervalometer), `tests/` (stale Swift test, see §8). |
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
  `iosArm64` + `iosSimulatorArm64` only). Signing team `6T589MK27K` and bundle ID are
  upstream's; change both to run on your own device. Bluetooth does not work in the iOS
  Simulator.
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
  "appeared", so a camera that is already present only reconnects once it is switched off
  and on.

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
    (`autoConnect`, bonded devices only).
  - iOS: `IosBluetoothController` (facade/policy), `IosCentralShell` (CBCentralManager with
    state restoration, created in `AppDelegate` via `ensureInitialized()`),
    `IosBleTransport` (pairing gate), AccessorySetupKit (`IosAccessoryShell`/`Coordinator`)
    for adding, migrating, renaming and removing cameras.
- Persistence: shared Room DB `LogDatabase` v6 (`camera_devices`, `log_entries`), schemas
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
  `app/src/main/res`). Translations come from Weblate (upstream project `alpha-gps`);
  don't hand-edit them. Changing English source text invalidates existing translations.
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
| `privacy.md` (policy of the published Alpha GPS app; provider/contact stay Saschl) | every data flow: location use, on-device data, Sentry opt-in and payload, Google Play services / Apple services. Bump "effective as of" on content changes |
| `website/src/pages/index.astro` | features, FAQ (also emitted as JSON-LD), min OS versions, confirmed cameras (keep in sync with README), links |
| `website/public/seo/og-image.svg` | brand text in link previews |
| `fastlane/metadata/android/en-US/*` | F-Droid listing of the `foss` build (Alpha GPS name) |
| `tools/*/README.md` | tool CLIs; the simulator README mirrors the GATT table |
| `.claude/skills/accessorysetupkit.md` | iOS AccessorySetupKit flow |
| In-app help (strings `faq_*`, `guide_*`, `ios_troubleshooting_*`, `HelpScreen.kt`, `IosHelpScreen.kt`) | user-facing docs inside the app; see §8 for open issues |

Website deploy config (`website/astro.config.mjs` `site`, root `CNAME`,
`website/public/robots.txt`) still targets `alphagps.app`; change them before deploying the
fork's site. GitHub Pages is not enabled on the fork.

## 7. User preferences and decisions

- Ask (with choices) before design decisions: branding, legal texts, repo settings,
  anything user-visible in the app.
- 2026-09-28: rebrand repository docs to GeoShutter; keep describing the shipped app as
  Alpha GPS; privacy provider/contact remain Saschl; repo/issue/privacy links point to
  `sarmadjari/GeoShutter`; store buttons stay on the Alpha GPS listings; GitHub Issues
  were enabled on the fork; in-app text changes were deferred (see §8).
- 2026-09-28: the Android app gets its own permanent app ID `com.sarmadjari.geoshutter`
  and launcher label "GeoShutter" in all flavors and build types, so it runs next to the
  Play Store Alpha GPS on the maintainer's phone. The iOS bundle ID and in-app texts are
  unchanged.
- 2026-09-28: every camera, Sony included, connects directly (fast) when Android reports
  it nearby; the camera list shows each camera's own name with its model underneath (Sony
  model codes as marketing names, e.g. "ILCE-1M2" → "α1 II").

## 8. Known issues and follow-ups (not fixed yet)

- In-app "documentation" link on both platforms points to
  `https://github.com/Saschl/camera-gps/blob/main/README.md` (old upstream repo name;
  `app/.../ui/HelpScreen.kt`, `sharednew/src/iosMain/.../IosHelpScreen.kt`).
- Shared FAQ strings: `faq_permissions_answer` (also shown on iOS) lists Android-only items
  (battery optimization, Nearby devices); `faq_connect_camera_answer` describes an old flow
  ("click on the camera on the main screen") instead of *Add camera*;
  `how_about_privacy_answer` doesn't mention optional error reporting.
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
  (`app-*-release.apk`) doesn't match. `deploy-pages.yml` fails while Pages is disabled.
- `Info.plist` has both `NSAccessorySetupKitSupports` and `NSAccessorySetupSupports`; the
  second looks redundant.
- Fujifilm: see "Known gaps" in `docs/fujifilm-protocol.md`. Geotagging works on an X100VI
  (firmware 01.32, 2026-09-28); open: UTC vs local time, registration right after pairing,
  iOS and legacy firmware unsupported, remote/camera settings Sony-only. The camera stays
  connected while switched off when its CONNECT WHILE POWER OFF setting is on (X100VI
  manual) and gives no Bluetooth sign of on/off (tested with every readable and notifying
  characteristic), so the app can't show it; the phone keeps its location updates running
  meanwhile (possible follow-up: slower location updates for
  Fujifilm-only sessions, needs a user decision).
- iOS: the camera-name/model line and the direct connects are Android-only so far.
- iOS crash reports are not symbolicated automatically: no dSYM upload is set up (options:
  a sentry-cli build phase using an auth token, or Sentry's App Store Connect
  integration).

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
- Repository metadata: description says "Sony and FijiFilm cameras" (typo; Fujifilm support
  is experimental); homepage is upstream's https://alphagps.app; no topics;
  `.github/FUNDING.yml` shows Saschl's Buy Me a Coffee.
- Upstream identity baked into the code (matters as soon as the fork publishes its own
  app): iOS bundle ID `com.saschl.cameragps` (the Android app ID is GeoShutter's own since
  2026-09-28); iOS team `6T589MK27K`; StoreKit tip
  IDs; in-app contact e-mail, docs and donation links in `HelpScreen.kt`,
  `IosHelpScreen.kt`, `CameraDeviceManager.kt`; `privacy.md` names Saschl as provider.
  (The upstream Sentry DSNs/org were removed on 2026-09-28; Sentry is now configured per
  build.)
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

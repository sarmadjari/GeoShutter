# GeoShutter architecture

Technical reference for the code base: modules, runtime flows on Android and iOS,
the Sony Bluetooth protocol as implemented, persistence and release plumbing.
The short, always-loaded project context lives in [`../AGENTS.md`](../AGENTS.md);
this document is the deep dive. Verified against the code at app version 1.6.3
(Android `versionCode` 163 / iOS build 163).

GeoShutter is a fork of [Alpha GPS](https://github.com/Saschl/alpha-gps). The code
still uses the upstream identifiers (app name "Alpha GPS", code namespace and iOS bundle
ID `com.saschl.cameragps`), so those names appear throughout this document. Only the
Android app ID (`com.sarmadjari.geoshutter`) and launcher label ("GeoShutter") are
GeoShutter's own.

## 1. Modules

| Module / folder | Kind | Identity | Role |
|---|---|---|---|
| `:app` (`app/`) | Android application | namespace `com.saschl.cameragps`, applicationId `com.sarmadjari.geoshutter`, launcher label `GeoShutter` | Android shell: Compose host activity, CompanionDeviceManager (CDM) integration, foreground service, Android BLE transport, location sources, notifications, Android-only settings screens. Flavors `gplay` / `foss`. |
| `:sharednew` (`sharednew/`) | Kotlin Multiplatform library (Android, `iosArm64`, `iosSimulatorArm64`) | Kotlin package / Android namespace `com.sasch.cameragps.sharednew` (note: `sasch`, not `saschl`), iOS framework `sharedKit`, resources class `cameragps.sharednew.generated.resources.Res` | Everything platform-neutral: BLE protocol and session orchestration, location transmission, Room database, most Compose UI and all shared strings. Its `iosMain` source set **is the iOS app logic** (CoreBluetooth, AccessorySetupKit, Core Location, StoreKit, Sentry). |
| `iosApp/` | Xcode project `alphagps.xcodeproj`, target and scheme `alphagps` | display name "Alpha GPS", bundle ID `com.saschl.cameragps`, iPhone only, deployment target iOS 18.0 | Thin SwiftUI shell: `AppDelegate` and `ContentView` embed the Compose `MainViewController` from `sharedKit`. |
| `website/` | Astro static site | — | Landing page (upstream deploys it to alphagps.app). |
| `tools/` | Scripts | — | Screenshot generator, iOS localization bridge, Sony camera simulator, Python intervalometer, a standalone Swift test. |

Root Gradle project name: `CameraGps`. Dependency versions: `gradle/libs.versions.toml`.

## 2. Layering

```
 Android shell (app/)                          iOS shell (sharednew/src/iosMain + iosApp/)
 ─────────────────────                         ────────────────────────────────────────────
 MainActivity, Compose screens                 CameraGpsIosApp (Compose), IosAppDialogHost
 CameraDeviceCompanionService (CDM presence)   IosAccessoryShell / IosAccessoryCoordinator (AccessorySetupKit)
 LocationSenderService (foreground service)    IosBluetoothController (policy + facade)
 AppServices (app-scoped graph)                IosCentralShell (CBCentralManager, state restoration)
 StatusPublisher (notification, tile, widget)
 AndroidBleTransport (BluetoothGatt)           IosBleTransport (CBPeripheral, pairing gate)
 Fused/PlatformLocationSource                  IosLocationSource (CLLocationManager)
            │                                               │
            └──────────────────┬────────────────────────────┘
                               ▼
      CameraSessionOrchestrator  (sharednew/commonMain, bluetooth/session)
        ├─ CameraSessionRegistry      StateFlow<Map<id, CameraSession>>  ← both UIs observe this
        ├─ BleOperationQueue          one sequential lane per device
        ├─ QueuedBleGattPort          the only BleGattPort implementation
        ├─ BleSessionCoordinator      handshake state machine
        ├─ RemoteControlCoordinator   remote probe loop + shutter sequence
        ├─ CameraAutoCorrectionController   camera time/area settings
        └─ LocationTransmissionManager      LocationSource → location packets
                               ▼
      BlePeripheralTransport (interface; Android and iOS implementations above)
```

Rules the code relies on:

- The orchestrator and everything under it run on **one confined dispatcher**
  (`Dispatchers.Main.immediate` on both platforms). Platform callbacks only push
  `BleTransportEvent`s into a channel; they never touch shared state.
- Device identifiers are **uppercase** strings: the MAC address on Android, the
  `CBPeripheral.identifier` UUID on iOS. Both are stored in `CameraDevice.mac`.
- Every GATT operation (handshake, location packets, remote probes, shutter,
  camera settings) goes through `BleOperationQueue`: one outstanding operation per
  device, 15 s operation timeout, 30 s service-discovery timeout (the iOS pairing
  gate runs inside discovery). Queued location writes are dropped at execution time
  unless the session is `Transmitting`.
- Continuous state lives in `CameraSessionRegistry`; one-shot side effects are
  `OrchestratorEvent`s (`DeviceConnected`, `DeviceDisconnected`,
  `HandshakeCompleted`, `PairingFailed`, `FirstLocationAcquired`,
  `LocationUnavailable`) on a `SharedFlow`.

## 3. Sony Bluetooth LE protocol (as implemented)

Fujifilm cameras (experimental, Android) use a different protocol, described with its
sources in [`fujifilm-protocol.md`](fujifilm-protocol.md).

Source of truth: `sharednew/.../bluetooth/SonyBluetoothConstants.kt`,
`coordinator/LocationPacketBuilder.kt`, `coordinator/LocationDataConfig.kt` and
`coordinator/RemoteControlCoordinator.kt`. Mirrored by `tools/sony_camera_sim`.

Cameras advertise manufacturer data with Sony's Bluetooth company ID **`0x012D`**;
both platforms discover cameras by that ID (Android CDM scan filter, iOS
AccessorySetupKit descriptor and `NSAccessorySetupBluetoothCompanyIdentifiers`).

| Service | Characteristic (16-bit, Bluetooth base UUID) | Use in the app |
|---|---|---|
| `8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF` (location) | `DD01` | notify: location-linking status. `03 01 02 00` = disabled by the camera, `03 01 03 01` = available. **Advisory only** (UI warning); it never gates setup or sending. |
| | `DD11` | write: location packet (below) |
| | `DD21` | read: capabilities. `value[4] & 0x02` → the camera accepts time zone and DST → 95-byte packets |
| | `DD30` | write `01`: enable/unlock GPS (a `00` "release" write is recognized and never advances the handshake) |
| | `DD31` | write `01`: lock GPS |
| | `DD32` | read/write one byte `0`/`1`: the camera's *Automatic time correction* setting |
| | `DD33` | read/write one byte `0`/`1`: the camera's *Automatic area adjustment* setting |
| `8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF` (control) | `CC13` | write: 13-byte date/time sync |
| | `CC09` | defined but **not used** (it reports Sony-app remote availability, not the Bluetooth remote setting) |
| `8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF` (remote) | `FF01` | write: button commands |
| | `FF02` | notify: remote status |

On iOS a characteristic is only usable if it is listed in
`IosBleTransport.knownCharacteristicUuids`.

### Location packet (`DD11`)

`LocationDataConfig` + `LocationPacketBuilder.buildLocationDataPacket`:

| Bytes | Content |
|---|---|
| 0–10 | fixed header `00 59 08 02 FC 00 00 00 10 10 10` (91-byte form) or `00 5D 08 02 FC 03 00 00 10 10 10` (95-byte form); byte 1 = packet length − 2 |
| 11–14 | latitude × 10⁷, signed 32-bit big-endian |
| 15–18 | longitude × 10⁷, signed 32-bit big-endian |
| 19–25 | **UTC** date/time: year (16-bit BE), month, day, hour, minute, second |
| 26–90 | 65 zero bytes |
| 91–94 | 95-byte form only: standard UTC offset in minutes, DST offset in minutes (each signed 16-bit BE) |

### Time sync packet (`CC13`)

`buildTimeSyncPacket`: `0C 00 00`, year (16-bit BE), month, day, hour, minute,
second (**local** time), DST flag (`1` while DST is active), signed standard-offset
hours, absolute remaining offset minutes.

### Remote control

Commands on `FF01` (`RemoteCommand`): half press `01 07` / release `01 06`,
full press `01 09` / release `01 08`, AF-ON `01 15` / release `01 14`.
`01 06` doubles as the status **probe**.

Status notifications on `FF02`: `02 3F 20` focus acquired, `02 A0 20` shutter
active (exposure running), `02 A0 00` ready, `02 C3 00` remote control **off**
(the only "inactive" value; anything else counts as active).

## 4. Connection lifecycle (shared)

1. **Connect**: the shell calls `orchestrator.onConnectRequested(id)` and opens the
   link; the transport emits `Connected`. The registry entry is reset (phase
   `Connected`, retry counters, camera-setting state).
2. **Optional delay**: `CameraDevice.handshakeDelayMs` (UI: *Delay connection
   setup*, 0–10 s). Workaround for cameras that stall their own boot under BLE
   traffic (reported on the A7R IV).
3. **Service discovery** through the queue (on iOS this includes the pairing gate).
   Then the camera is identified (`FujifilmSessionController.detect`): the Sony location
   characteristic `DD11` means Sony (checked first, so Sony behavior never changes); the
   Fujifilm status characteristic means a Fujifilm camera with the secure protocol, whose
   handshake runs as one sequence of queued operations (`FujifilmSessionController.runHandshake`),
   sets the camera's date, time and time zone (`syncTime`, unless the camera's
   `timeSyncEnabled` option is off; the camera applies it only when it asked, on the
   first connection after it is switched on or wakes), reads the camera's SMARTPHONE
   LOCATION SYNC. setting (`CameraAutoCorrectionController.readDuringSetup`) and its power
   switch (`readPowerSwitch` → `CameraSession.inStandby`) before it counts as ready, and then
   continues with step 5; the legacy Fujifilm pairing characteristic ends the session
   with an error; anything else takes the Sony path, as before. The detected protocol is
   stored in `CameraSession.protocol` on every connect; the Sony event handlers and remote
   monitoring ignore `FujifilmSecure` sessions. A Fujifilm camera's clock is set again
   when it notifies NOT1 (at most every 10 s), and its power switch is read again after
   every geotag request. `CameraSession.cameraResponding` records whether it has sent
   anything since connecting: a silent camera ignores the phone, so a watchdog repeats
   the setup after 15 s of silence and reconnects (`BlePeripheralTransport.reconnect`)
   after another 15 s. See [`fujifilm-protocol.md`](fujifilm-protocol.md).
4. **Handshake** (`BleSessionCoordinator.beginHandshake`, Sony); each step is skipped
   when its characteristic is missing: subscribe `DD01` → read `DD21` (one
   automatic retry on failure, for Android's intermittent GATT 133) → write
   `DD30=01` → write `DD31=01` → write the `CC13` time sync (a failed time sync
   still continues) → `HandshakeComplete`.
5. **Ready** (`handleHandshakeComplete`): iOS first checks that the camera is still
   enabled (`shouldRemainConnected`); then the phase becomes `Transmitting`, the
   location manager starts (or immediately sends a cached fix), `DD32`/`DD33` are
   read, remote monitoring starts if *Enable remote control* is on, and
   `HandshakeCompleted` is emitted (Android then drops the connection priority
   back to balanced).
6. **Auth errors** (ATT 5 / 15) on any step are retried per `PairingRetryPolicy`
   (3 retries, 3 s apart; Android retries the first one immediately). When they
   are exhausted, `PairingFailed` makes both UIs show the *Pairing Failed*
   troubleshooting dialog; iOS also cancels the connection and suspends
   auto-reconnect for that camera until the dialog is closed.
   **Services changed** (Android `onServiceChanged`, `BleTransportEvent.ServicesChanged`):
   the characteristics found so far are stale. A Sony α1 II sends it right after the
   link is encrypted on nearly every connection; without handling, the camera rejected
   the `DD30` unlock with status 159 and the Sony setup stalled. The orchestrator
   cancels the device's queue lane, ignores late results of the cancelled attempt, and
   restarts discovery and the handshake (`handleServicesChanged`). Android re-reads the
   services itself first and silently drops discovery requests meanwhile, so
   `rediscover` retries (1 s pause + 2.5 s wait, up to 8 times). On the α1 II setup now
   completes 3–5 s after the camera appears.
7. **Disconnect** clears the session, cancels its queue lane and re-evaluates
   location tracking.

### Location transmission (`LocationTransmissionManager`)

- Delivery depends on the protocol: Sony cameras get the location pushed (below);
  Fujifilm cameras ask for it with a geotag-request notification and get one answer per
  request (`onLocationRequested`). A request that comes before a fix is answered as soon
  as one exists. Remote control is only set up for Sony cameras.

- Location updates start when the **first** session that wants locations becomes
  ready and stop when none is left (or, on iOS, when the app is disabled). Nothing
  reads location while no camera is connected, also in Android's Always On mode. A
  Fujifilm camera whose location sync is off wants no location
  (`CameraSession.wantsLocation`); a geotag request still starts updates. While every
  camera that wants locations is switched off or asleep in standby (`CameraSession.inStandby`),
  the source is asked for one fix about every 60 s (`LocationSource.setSlowUpdates`,
  `STANDBY_LOCATION_UPDATE_INTERVAL_MS`); a camera that is on brings it back to the
  full rate. The orchestrator calls `updateTracking` whenever the set of cameras with
  location sync off or in standby changes.
- Stale fixes (older than 30 s) are ignored: iOS checks every fix, Android checks
  the initial/last-known seed fix, and a fix cached from a previous session is
  discarded when it is older than 30 s. A new fix replaces the current one unless
  it is more than 200 m less accurate *and* not more than 30 s newer.
- The first usable fix is sent immediately; afterwards the latest fix is re-sent
  to every ready camera every **5 s** (`LOCATION_UPDATE_INTERVAL_MS`). A tick
  without any fix emits `LocationUnavailable` (Android: "location invalid" sound).
- Sources: Android `gplay` = `SwitchingLocationSource` (Play Services fused
  provider with high accuracy, 5 s interval and 2 m minimum distance, or the
  platform provider, chosen in Settings); Android `foss` = `PlatformLocationSource`
  only (`LocationManager`: FUSED provider on Android 12+, else GPS, else network);
  iOS = `IosLocationSource` (`CLLocationManager`, best accuracy, 2 m distance
  filter, background updates allowed, no automatic pausing). Both Android sources
  switch to a 60 s interval with `setSlowUpdates(true)` (standby, above); iOS ignores
  it (no Fujifilm support there).

### Remote control and shutter

- With *Enable remote control* on, `RemoteControlCoordinator` subscribes to `FF02`
  and, while the remote is inactive, writes the probe every 3 s (first after
  0.5 s). A successful `FF01` write or any status other than `02 C3 00` marks the
  remote active; the shutter button then appears on the camera card.
- The shutter button (both platforms) runs `startShutterSequence`: half press →
  wait for focus (2 s fallback) → full press → wait for shutter active (2 s
  fallback) → full release → half release → wait for ready (20 s fallback).
  Pressing again during the final ready-wait starts a new cycle. Haptic feedback on
  press and when the cycle ends (setting *Haptic feedback*, default on).
- A single-press path also exists (`ShutterFullPress`, released automatically when
  the camera acknowledges with `02 A0 00`); on Android it is reachable through the
  service intents `ACTION_TRIGGER_REMOTE_SHUTTER` / `ACTION_SEND_REMOTE_COMMAND`,
  but the UI does not use it.

### Camera settings (`CameraAutoCorrectionController`)

Only while the camera is `Transmitting`, and only the settings of its protocol
(`CameraAutoCorrectionSetting.forProtocol`): Sony's `DD32`/`DD33` (one byte) and
Fujifilm's SMARTPHONE LOCATION SYNC. on NOT7 (little-endian uint16, read and written in
its service). Reads each if the characteristic supports write-with-response (otherwise
the UI shows *unsupported*), writes `0`/`1` on toggle and reports pending/failed states
(*Refresh camera settings*); Fujifilm's change notifications update it too
(`onNotified`). These change settings **on the camera**; an unknown value is never
assumed to be "off". The per-camera *Set date, time and time zone* option (Fujifilm) is
stored by the app instead (`CameraDevice.timeSyncEnabled`).

## 5. Android shell (`app/`)

| Piece | Responsibility |
|---|---|
| `CameraGpsApplication` | Timber `FileTree` (logs into Room), KmLogging → Timber bridge, Sentry init only after consent and only in `gplay` builds configured with a DSN, global exception handler. |
| `AppServices` | App-scoped graph without DI: DAO, `AndroidBleTransport`, `CameraSessionOrchestrator` (`PairingRetryPolicy(firstRetryDelayMs = 0)`), `pairingFailedDevice` flow for the UI, `StatusPublisher` (started by `CameraGpsApplication`). |
| `MainActivity` | Navigation3 destinations Welcome → Devices, Settings, Help, Troubleshooting, Logs. On every resume it (re)starts the service for enabled Always-On cameras. |
| Pairing | `DeviceAssociationUtils.requestDeviceAssociation`: CDM `AssociationRequest` with BLE scan filters on the manufacturer IDs `0x012D` (Sony) and `0x04D8` (Fujifilm) → system chooser. `ScanForDevicesMenu` handles the result; `PairingManager`/`PairingDialog` call `createBond()` when the camera is not bonded yet, then `startDevicePresenceObservation`. Android confirms the pairing code automatically for companion-associated devices (no code on the phone); a Fujifilm camera shows a code and must be confirmed with MENU/OK on the camera within 30 s. For a Fujifilm camera (Fujifilm company ID in the chooser's scan result, Android 14+) the service connects right after bonding so the handshake runs while the camera is still in pairing registration. |
| `CameraDeviceCompanionService` | `CompanionDeviceService` bound by the system. Presence callbacks by API level: < 33 `onDeviceAppeared(String)`, 33–35 `onDeviceAppeared(AssociationInfo)`, 36+ `onDevicePresenceEvent`. Appeared → `startForegroundService(LocationSenderService)` with the address if the app is enabled and location is granted (a refused background start is logged, not thrown), asking for a direct (fast) connection; disappeared → `ACTION_REQUEST_SHUTDOWN`. On Android 16+ (`onDevicePresenceEvent`) "BLE disappeared" is ignored while the camera is still connected (Fujifilm cameras stop advertising once connected); the session then ends on `EVENT_BT_DISCONNECTED`, matching the older callbacks, which count a connected device as present. Android reports a camera only when it starts advertising: a camera that was already present while the app was disabled, or when the app was updated, is not reported again, so turning the app on and `RebootReceiver` (after an update) send `ConnectSaved`. Presence observation needs **Android 12+**; Android 8–11 rely on Always On. |
| `LocationSenderService` | `LifecycleService`, foreground service type `location|connectedDevice`. `ServiceCommandRouter` maps intents to `ServiceCommand`s: `Connect`, `ReconnectAlwaysOn` (no address: connect all Always-On cameras), `ConnectSaved` (`ACTION_CONNECT_SAVED`: direct connections to every enabled saved camera, i.e. companion association, so cameras that are already on connect; after 40 s the service stops again unless a camera connected or one is Always On), `Shutdown`, `TriggerShutterSequence`, `TriggerRemoteShutter`, `SendRemoteCommand`, `SetRemoteControlMonitoring`. Shuts down when Bluetooth turns off (for direct connects and Always-On reconnects alike; GATT handles don't survive a Bluetooth restart). A connect that can't start (camera no longer bonded) marks the session as failed. `startForeground` failures (`SecurityException`, `IllegalStateException`) stop the service instead of crashing. Plays event sounds. Starts in the foreground with the status notification (`StatusNotifier.quiet`), which `StatusPublisher` then keeps up to date; `running` (StateFlow) tells it whether the service owns the notification. On destroy it detaches the notification (`STOP_FOREGROUND_DETACH`) so it can turn into the "waiting" notification. |
| `ServiceShutdownCoordinator` | On "disappeared": pause the camera unless it is Always On; stop the service when no camera is connected and none is Always On. `stopIfIdle(startId)` ends a `ConnectSaved` round the same way (`stopSelf(startId)`, so a newer start command keeps the service). |
| `AndroidBleTransport` | `connectGatt(autoConnect = true)`, **bonded devices only**; API 37+ uses `BluetoothGattConnectionSettings` (automatic MTU). `connect(direct = true)` makes a direct attempt instead (aggressive scan for about 30 s), used whenever a camera appears (Fujifilm cameras advertise only briefly after switching on; Sony cameras connect within about a second); it replaces a waiting background connection. A failed or dropped direct connection is retried directly three times (a drop after a connection that lasted 30 s starts a fresh round), then the device falls back to `autoConnect`. Late callbacks of a replaced GATT handle are ignored. Operations can name a service to look the characteristic up in (needed for Fujifilm, which reuses a UUID across services); subscriptions write the indication bit for indication-only characteristics. `onServiceChanged` is forwarded as `ServicesChanged`. The GATT handle survives disconnects so autoConnect can resume; `disconnectAll()` is the only close path. High connection priority during setup, balanced afterwards. |
| `RebootReceiver` | Unless the app is disabled (*Enable App*) or location isn't granted: `BOOT_COMPLETED` (only with *Start App on Device boot*) → start the service without an address (Always-On reconnect); `MY_PACKAGE_REPLACED` → `ConnectSaved` (the update ended every connection). |
| Status outside the app (`status/`) | `StatusPublisher` (app-scoped, single source): combines *Enable App* (`PreferencesManager.appEnabledFlow`), the saved cameras (companion associations with brand), the `camera_devices` rows (names), the orchestrator's sessions and whether location updates run into the shared `GeoShutterStatus`, and drives the three views below. Camera states (`CameraState`): *Sending*, *Connecting* (also a Fujifilm camera that stays silent), *Standby* (Fujifilm switched off or asleep, connected in standby, still receiving the location), *LocationSyncOff* (Fujifilm with SMARTPHONE LOCATION SYNC. off), *Away*. `StatusNotifier`: one notification (ID `locationTransmissionNotificationId`) — "Sending location to …" / "Connecting to …" / "… is in standby – Location kept up to date for your next photo" / "…: location sync is off on the camera" / "GeoShutter is on – Waiting for …" with brand and model (counts are plurals), filled or outline location icon, *Turn off* action (`StatusActionReceiver`); foreground while `LocationSenderService` runs, a quiet ongoing notification otherwise, none while the app is off. It alerts on `transmission_notification_channel` when a camera starts receiving the location, on `disconnect_notification_channel` when a camera that was receiving it goes away (only if that channel is enabled), otherwise it updates silently (`general_notification_channel`). `StatusTileService`: Quick Settings tile (active = on; subtitle "Off"/"Waiting"/"Connecting"/"Standby"/"Location sync off"/camera name/"N sending"; tap toggles). `StatusWidget` (Glance): monitoring only — header with state, up to four cameras with name, brand and model and a green/blue/amber/red/grey dot (blue: standby; amber: connecting or location sync off, with a note line); a tap opens the app. The camera card in the app shows a blue dot and a note for a camera in standby, and a note for location sync off. `GeoShutterSwitch.setEnabled`: the settings switch, tile and notification action all go through it; off stops the service, on starts it with `ConnectSaved`. |
| Permission safety | The permission screen can be skipped ("Continue anyway"), so reading bonds and device names goes through `bondedAddressesOrNull()` / `nameOrNull()` (`AssociatedDeviceCompat.kt`), which treat a missing Nearby devices permission as "unknown" instead of crashing. |
| Preferences | `SharedPreferences` file `camera_gps_prefs` (`PreferencesManager`). |

Flavors (`app/build.gradle.kts`, dimension `distribution`):

| | `gplay` (default) | `foss` |
|---|---|---|
| Location | Play Services fused provider or platform provider (user-selectable) | platform provider only |
| In-app review | Google Play review API | none |
| Crash reporting | Sentry (opt-in; offered only when the build has a DSN, `BuildConfig.SENTRY_DSN`) | none; the SDK is not in the APK |
| Flavor sources | `app/src/gplay/...` | `app/src/foss/...` |

The `foss` guarantee also depends on `:sharednew`: Sentry KMP is declared only in
`iosMain`, and the Sentry KMP Gradle plugin's auto-install is disabled
(`sharednew/build.gradle.kts`). Never add Sentry or Google libraries to
`commonMain`/`androidMain`.

## 6. iOS shell (`iosApp/` + `sharednew/src/iosMain`)

| Piece | Responsibility |
|---|---|
| `AppDelegate.swift` | Records the launch reason (`IosLaunchContext`: user, Bluetooth restoration or location event) and calls `IosBluetoothController.shared.ensureInitialized()` inside `didFinishLaunchingWithOptions`, which Core Bluetooth state restoration requires on background relaunches. |
| `ContentView.swift` | Embeds `MainViewController(reviewTestMode:requestReview:)` and supplies the StoreKit review callback. Debug simulator builds honor `ALPHA_GPS_SCREENSHOT` (store screenshots) and `ALPHA_GPS_REVIEW_TEST=1`. |
| `AccessoryDiscoveryNaming.swift`, `AccessoryDiscoveryItems.swift` | iOS 26.1+ picker naming customizer. **Currently disabled** (the install call is commented out in `AppDelegate`). |
| `IosBluetoothController` | Singleton facade used by the Compose UI and owner of the policy: auto-reconnect decisions (`AutoReconnectPolicy`), app/device enable sweeps, pairing-failure state, device-list assembly, forwarding of the AccessorySetupKit APIs. |
| `IosCentralShell` | The `CBCentralManager` (restore identifier `com.saschl.cameragps.central`), state restoration (restored peripherals are parked until the central is powered on, see `RestorePolicy`), `retrievePeripheralsWithIdentifiers` plus pending connects with `CBConnectPeripheralOptionEnableAutoReconnect`. It does **not** scan for new cameras. |
| `IosBleTransport` | `CBPeripheral` delegate, two-phase discovery and the **pairing gate**: subscribing to the first notifiable characteristic forces iOS pairing before the handshake (auth errors are retried, then `PairingFailed`). |
| `IosAccessoryShell` / `IosAccessoryCoordinator` | AccessorySetupKit: `ASAccessorySession`, discovery picker (company ID `0x012D`, BLE pairing), migration picker for cameras saved before AccessorySetupKit (app versions before 1.6.2), system rename sheet, removal events. A picker is first requested with the central alive; only if iOS refuses it with `ASErrorCodePickerRestricted` (a live `CBCentralManager` from the legacy global Bluetooth grant) is the central released and the picker retried (up to 10 attempts, 10 s after a release, 5 s otherwise). Central creation stays blocked (`centralCreationBlocked`) until the picker operation ends. |
| `IosDeviceRepository` | Room DAO access, the legacy `NSUserDefaults` auto-reconnect store (read only for migration), enabled-state caches. |
| `IosLocationSource` | `CLLocationManager`; requests When-In-Use, then escalates to Always. |
| `IosTransmissionNotifications` | Local notification "Location transmission active" while sending (setting *Transmission notification*, default on). |
| `IosTipJarController` | StoreKit products `com.saschl.cameragps.tip.small/medium/large`. |
| `IosCrashReporting` | Sentry KMP (Cocoa SDK via the SPM package `sentry-cocoa` 8.58.2), started only after consent. The DSN comes from the `SentryDSN` Info.plist key (build setting `SENTRY_DSN`); without it error reporting is hidden. MAC addresses are redacted from messages, breadcrumbs and logs. |
| `CameraGpsIosApp` | Screen state machine (Welcome, Devices, PairingPreparation, DeviceDetails, Settings, Help, Troubleshooting, Logs). Dialogs are queued through `IosAppDialogState` and the shared `DialogQueue`: error-reporting consent, migration explainer/error, pairing failed, "Always" location, precise location, what's new, donation (opens the Tip Jar). |
| Preferences | `NSUserDefaults`, keys prefixed `ios.` (`IosAppPreferences`). |

Build settings: the target's base configuration is `iosApp/Config/GeoShutter.xcconfig`,
which optionally includes the untracked `iosApp/Config/Local.xcconfig` for
machine-specific values such as `SENTRY_DSN`.

`Info.plist` declares `NSAccessorySetupKitSupports` = Bluetooth, the Sony company
ID and the three service UUIDs for AccessorySetupKit, and the background modes
`location` and `bluetooth-central`. Permission texts are `INFOPLIST_KEY_*` build
settings, localized in `iosApp/alphagps/InfoPlist.xcstrings` (en, de); see
`tools/ios_localization`.

## 7. Platform differences

| Topic | Android | iOS |
|---|---|---|
| Adding a camera | CDM chooser + Bluetooth bonding | AccessorySetupKit picker (iOS pairs) |
| Fujifilm cameras | experimental (secure protocol) | not supported (the picker lists Sony only) |
| Background reconnect | CDM presence (Android 12+) starts the foreground service; optional Always On keeps it running with `autoConnect` | pending connections with auto-reconnect + Core Bluetooth state restoration relaunches |
| Always On / start on boot | yes | not applicable |
| Status notification | status notification (foreground while the service runs), Quick Settings tile, home-screen widget | optional local notification |
| Event sounds | yes (connected, disconnected, location acquired, location invalid; custom sounds) | no |
| Location provider choice | `gplay` only | no |
| Battery-optimization helpers | yes | no |
| Rename | in-app name only (CDM keeps its own) | system rename sheet for AccessorySetupKit cameras, in-app otherwise |
| Removing a camera | removes the CDM association and the app's data | also removes the AccessorySetupKit authorization (and the bond) |
| Crash reporting | `gplay` only, opt-in | opt-in |
| Donations | Buy Me a Coffee link | Tip Jar (in-app purchase) |
| Review prompt | Play in-app review (`gplay`) | StoreKit request (one day after setup, then at least 30 days apart) |

## 8. Persistence

- Room database shared by both platforms (`sharednew/.../database/LogDatabase.kt`,
  bundled SQLite driver), file `log_database` (Android database directory) /
  `Documents/log_database.db` (iOS). Version 7, auto-migrations 1→7, schemas
  exported to `sharednew/schemas/` (commit the new schema JSON with every change).
  - `camera_devices`: `mac` (PK), `deviceEnabled`, `alwaysOnEnabled`,
    `deviceName`, `deviceNameIsCustom`, `remoteControlEnabled`,
    `handshakeDelayMs`, `timeSyncEnabled` (Fujifilm, default on; version 7).
  - `log_entries`: the in-app log, written by `LogRepository`: one write at a time in
    call order, database failures dropped (logging must never crash the app, e.g. while
    the iOS file is still protected before the first unlock), and every 50 inserts the
    table is trimmed to the newest 500 rows once it exceeds 1000.
- Name resolution (`AccessoryCameraName`): a name chosen by a person (in-app or in
  the iOS rename sheet) is never overwritten; derived names upgrade to the hardware
  name when it becomes available. On Android a Fujifilm camera's own name is read from
  NOT4 after each handshake (`CameraSessionOrchestrator.storeCameraName`, stored without
  the "FUJIFILM-" prefix unless renamed). The camera list shows the stored or custom name
  with brand and model underneath (`BluetoothDeviceInfo.model`, built by
  `cameraModelLine`, e.g. "Sony α1 II", "Fujifilm X100VI"). The brand comes from the
  manufacturer ID in the advertisement Android keeps with the association (Android 14+),
  else from a Sony model code or the live session; Sony model codes become marketing
  names ("ILCE-1M2" → "α1 II"), Fujifilm shows the pairing name.

## 9. Logging and crash reporting

- Shared code logs through KmLogging (`com.diamondedge.logging`). Android routes it
  to Timber (`FileTree` → Room; Sentry Timber integration in `gplay`); iOS installs
  `IosLogging` (database logger, plus `SentryCrashLogger` when enabled).
- `CrashReportPolicy` keeps both platforms' routing identical: errors → Sentry
  events, info/warn → breadcrumbs, info and above → Sentry Logs. Sentry only starts
  when it is enabled **and** the consent dialog was answered. Coordinates are never
  logged.
- MAC-address redaction (`CrashReportPolicy.redact`, replaces addresses with
  `XX:XX:XX:XX:XX:XX`): Android `gplay` applies it to Sentry Logs, event messages and
  parameters, exception messages and breadcrumbs (`SentryRedaction` in
  `app/src/gplay/.../CrashReporting.kt`). iOS applies it to messages, breadcrumbs and
  logs; exception messages are not reachable through the KMP `beforeSend` on Apple, and
  iOS identifies cameras by UUID rather than MAC address.
- Both platforms store warning/error stack traces in the in-app log (`FileTree` on
  Android, `DatabaseLogger` on iOS).
- Log level: Settings → Log Settings (default Info on both platforms).

## 10. UI, strings and languages

- Compose Multiplatform + Material 3 (`CameraGpsTheme`). Shared screens live in
  `sharednew/.../ui/` (device list, device details, settings building blocks,
  welcome, pairing preparation, troubleshooting guide, log viewer, what's new,
  dialog queue). Android hosts them from `app/.../ui/`, iOS from `iosMain`.
- Strings: shared `sharednew/src/commonMain/composeResources/values*/strings.xml`
  (translated on Weblate), iOS-only `sharednew/src/iosMain/composeResources`,
  Android framework strings (notification channels) in `app/src/main/res`.
- The in-app language picker is generated at build time from the
  `composeResources/values-*` folders (`:sharednew:generateSupportedLanguages`).
  Folders today: en (source), de, es, fa, id, ja, ro, ru, ta, vi, zh-CN; coverage
  varies (fa is empty, ro and ja are partial).
- Release notes dialog: add an entry to `ReleaseNotesCatalog`
  (`whatsnew/ReleaseNotes.kt`) plus strings for every release with user-facing
  changes.

## 11. Build, CI and release

- Versions: Android `versionCode`/`versionName` in `app/build.gradle.kts`; iOS
  `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` in `project.pbxproj`. Keep them in
  sync.
- Android release signing reads `app/keystore.jks` (or `SIGNING_KEYSTORE_PATH`)
  plus `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` and `SIGNING_STORE_PASSWORD`;
  without them (blank values and an empty keystore file count as missing) release
  APKs are unsigned (F-Droid builds from source).
- Sentry configuration, all optional and never committed: Android reads `sentry.dsn`,
  `sentry.org`, `sentry.project` and `sentry.authToken` from a Gradle property, the
  matching `SENTRY_*` environment variable or the untracked `local.properties`; the DSN
  goes into `BuildConfig.SENTRY_DSN` of the `gplay` flavor. iOS takes the build setting
  `SENTRY_DSN` from the untracked `iosApp/Config/Local.xcconfig` (included by the
  target's base configuration `iosApp/Config/GeoShutter.xcconfig`) into Info.plist
  `SentryDSN`. Both platforms accept only a valid DSN (`CrashReportPolicy.isValidDsn`;
  the Android build repeats the pattern and warns). `gplay` release builds upload
  ProGuard mappings and source context only when an auth token or a
  `sentry.properties` file is present, never with `-PdisableSentryUpload=true`. Sentry
  Gradle plugin telemetry is off.
- Builds strip native libraries with NDK `29.0.14206865` (pinned for reproducible
  F-Droid builds); without that NDK, builds still succeed but package the
  libraries unstripped.
- iOS: the Xcode build phase *Compile Kotlin* runs
  `./gradlew :sharednew:embedAndSignAppleFrameworkForXcode`. The Sentry KMP Gradle
  plugin links the `Sentry-Dynamic` xcframework that Xcode's Swift Package Manager
  checked out (override: `-Psentry.cocoa.frameworkPath=...`); `sentryCocoa` in the
  version catalog must equal the SPM pin in the Xcode project.
- `.github/workflows/build-and-release.yml`: on `v*` tags builds `assembleRelease`
  (both flavors) and publishes `app-gplay-release.apk` and `app-foss-release.apk`
  as a GitHub release. Uses the secrets `KEYSTORE_BASE64`, `SIGNING_KEY_ALIAS`,
  `SIGNING_KEY_PASSWORD`, `SIGNING_STORE_PASSWORD` and, optionally, `SENTRY_DSN`,
  `SENTRY_AUTH_TOKEN` plus the variables `SENTRY_ORG`/`SENTRY_PROJECT`.
- `.github/workflows/deploy-pages.yml`: builds `website/` and deploys it to GitHub
  Pages on changes under `website/**`.
- Renovate (`renovate.json`) keeps dependencies and pinned action digests current.

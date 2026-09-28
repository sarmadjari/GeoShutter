---
name: accessorysetupkit
description: How the iOS app uses Apple's AccessorySetupKit (camera discovery, pairing, migration, rename, removal) together with CoreBluetooth. Read before changing IosAccessoryShell, IosAccessoryCoordinator, IosBluetoothController, IosCentralShell or Info.plist accessory keys.
---

# AccessorySetupKit in GeoShutter (iOS)

Project context: [`AGENTS.md`](../../AGENTS.md); architecture:
[`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) (section 6).

## Where it lives

All in `sharednew/src/iosMain/kotlin/com/sasch/cameragps/sharednew/bluetooth/`
(Kotlin/Native `platform.AccessorySetupKit` bindings, not Swift):

- `IosAccessoryShell`: `ASAccessorySession` mechanics, the authorized-accessory snapshot,
  discovery and migration pickers, rename sheet, removal.
- `IosAccessoryCoordinator`: policy (migration state, picker ownership and retries,
  `centralCreationBlocked`).
- `IosAccessoryPickerItems`: picker items; `ASDiscoveryDescriptor` with Bluetooth company ID
  `0x012D` and `ASAccessorySupportBluetoothPairingLE`; item name `"Camera"`.
- Pure, tested policy in `commonMain/.../bluetooth/accessory/`: `AccessoryMigrationPlanner`,
  `AccessoryPickerCompletion`, `AccessoryPickerRunner`, `AutoReconnectPolicy`,
  `AccessoryCameraName`.
- Swift `iosApp/alphagps/AccessoryDiscoveryNaming.swift` + `AccessoryDiscoveryItems.swift`:
  iOS 26.1+ picker naming customizer, currently **disabled** in `AppDelegate`.
- `iosApp/alphagps/Info.plist`: `NSAccessorySetupKitSupports` = Bluetooth,
  `NSAccessorySetupBluetoothCompanyIdentifiers` = `012D`, `NSAccessorySetupBluetoothServices`
  = the three Sony service UUIDs.

## Rules

- The session is activated in `IosBluetoothController.ensureInitialized()` (called from
  `AppDelegate`); the CBCentralManager is also created there unconditionally, because
  Core Bluetooth state restoration requires it. Never make central creation wait for
  AccessorySetupKit.
- With AccessorySetupKit declared, CoreBluetooth scans only return already-authorized
  accessories, so `startScan()` is a no-op and new cameras come only from the picker.
- A picker is first requested with the central alive. Only on
  `ASErrorCodePickerRestricted` is the central released (`stopCentral` + `GC.collect()`)
  and the picker retried (up to 10 attempts). While a picker operation runs,
  `centralCreationBlocked` prevents callbacks from recreating the central.
- `accessoryAdded` arrives before `pickerDidDismiss`; do not start the Sony handshake
  while the picker is still on screen.
- Only `pickerDidDismiss` ends a visible picker. A migration may finish without showing
  any UI; `AccessoryPickerCompletion` handles callback/dismissal ordering and timeouts.
- Keep "removed" and "not in the authorized set" distinct (`AccessoryAuthorization`):
  the set is empty before activation and for unmigrated cameras, so only an explicit
  removal may stop auto-reconnect.
- Migration covers cameras saved before AccessorySetupKit (app versions before 1.6.2):
  `CameraDevice.mac` holds the uppercased `CBPeripheral` UUID, which becomes
  `ASMigrationDisplayItem.peripheralIdentifier`; rows that are not UUID-shaped are skipped.
  Completion is stored in `ios.accessoryMigrationDone`; the debug card in Settings (tap the
  title 5 times) can reset it.
- Renaming an authorized camera uses the system sheet; the new name arrives as an
  accessory-changed event and is stored as a custom name. Removing a camera in the app also
  removes the accessory authorization (and the bond).

## Tests

`./gradlew :sharednew:testAndroidHostTest` (pure policy) and
`./gradlew :sharednew:iosSimulatorArm64Test` (`IosAccessoryCoordinatorTest`,
`IosAccessoryPickerItemsTest`, `IosAccessoryCameraNamePersistenceTest`). Picker behavior
itself needs a real iPhone and camera.

# Fujifilm Bluetooth protocol

Status: **experimental, Android only, not yet verified on a real camera.** GeoShutter's
Fujifilm support targets the X100VI and other Fujifilm cameras that geotag through
FUJIFILM XApp. Fujifilm publishes no protocol documentation. Everything below comes from
[furble](https://github.com/gkoh/furble), which reverse-engineered the protocol from
Android HCI snoop logs of Fujifilm's app. furble lists the X100V as tested; the X100VI is
assumed to use the same protocol because it belongs to the same XApp generation.

Rules for changing this protocol code: never guess bytes. Back every UUID, byte and
ordering with a capture, furble source or an observed camera response, and record the
evidence in this file (capture file name, frame number, hex).

## Sources

All furble references are to commit
[`0cac22a`](https://github.com/gkoh/furble/tree/0cac22aacc3d9ef250be40849118af78a5f39f27)
(`lib/furble/`, `src/`).

| Topic | Evidence |
|---|---|
| Company ID `0x04D8`, all service/characteristic UUIDs | `Fujifilm.h:35-99`, `FujifilmSecure.h:71-100`, `FujifilmSecure.cpp:13-14`, `FujifilmBasic.cpp:12-13` |
| Secure handshake (firmware from about July 2025) | `FujifilmSecure.cpp:73-213` (`_connect`) |
| Advertisement matching | `Fujifilm.cpp:57-66`, `FujifilmSecure.cpp:19-23` and `55-66`, `FujifilmBasic.cpp` `matches()` |
| Notification handling (`01 00`, `02 00`) | `Fujifilm.cpp:17-35` |
| 23-byte geotag packet | `Fujifilm.h:72-92` (packed struct), `Fujifilm.cpp:93-130` |
| Time is UTC | `FurbleGPS.cpp:160-186`: the time comes from the GPS receiver's NMEA date/time |
| Shutter commands | `Fujifilm.h:101-104`, `Fujifilm.cpp:68-91` |
| Legacy token protocol (detection only) | `FujifilmBasic.h`, `FujifilmBasic.cpp` |
| No accuracy/satellite fields in the geotag payload | [MaxRink/furble#47](https://github.com/MaxRink/furble/pull/47) |
| Camera ignores locations older than 3 hours | [FUJIFILM Camera Remote guide](https://app.fujifilm-dsc.com/en/camera_remote/guide02.html) |

The UUID constants were checked by parsing every `NimBLEUUID{…}` constructor in the furble
files above and comparing the canonical strings with
`FujifilmBluetoothConstants.kt`.

## Protocol (as implemented, from furble)

### Advertisement

- Manufacturer data starts with company ID `0x04D8`.
- Secure firmware: 8 bytes of manufacturer data (company ID, 1 type byte, 5-byte camera
  serial). furble treats a camera advertising service `a9d2b304-e8d6-4902-8336-352b772d7597`
  as a *new* camera to pair (`FujifilmSecure.cpp:19-23`), and recognizes an already paired
  camera by service `123d8f06-62a1-4935-9322-833c531ee225` plus a matching serial
  (`FujifilmSecure.cpp:55-66`). The original design note had these two the other way
  round; Phase 1 must confirm which one the X100VI advertises when.
- Legacy firmware: 7 bytes (type `0x02` plus a 4-byte pairing token) and service
  `af854c2e-b214-458e-97e2-912c4ecf2cb8` or `117c4142-edd4-4c77-8696-dd18eebb770a`.
- GeoShutter's Android companion-device chooser matches the company ID only
  (`DeviceAssociationUtils.kt`), so both kinds of camera are listed.

### GATT (from furble)

| Service | Characteristic | Use |
|---|---|---|
| `123d8f06-62a1-4935-9322-833c531ee225` pairing | `f557d96b-8284-4667-8793-b971c1deca2a` status | read 4 bytes, write back with byte 3 = `0x20` |
| | `85b9163e-62d1-49ff-a6f5-054b4630d4a1` identifier | UTF-8 client name (the same UUID also exists in the legacy pairing service `91f1de68-dff6-466e-8b65-ff13b0f16fb8`, so GeoShutter addresses it through its service) |
| `4c0020fe-f3b6-40de-acc9-77d129067b14` configuration | `a68e3f66-0fcc-4395-8d4c-aa980b5877fa` IND1 | indication |
| | `bd17ba04-b76b-4892-a545-b73ba1f74dae` IND2 | indication |
| | `f9150137-5d40-4801-a8dc-f7fc5b01da50` NOT1 | notification; `02 00` = configured |
| | `ad06c7b7-f41a-46f4-a29a-712055319122` geotag request | notification; `01 00` = "send a location" |
| | `e6692c5c-b7cd-44f4-95fc-eda07ce32560` NOT6 | notification (optional) |
| `4e941240-d01d-46b9-a5ea-67636806830b` notifications | `bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4` NOT4, `75823784-fbb7-4b71-abae-cd9a34072e3c` NOT5 | notifications (required) |
| | `aab609c4-94dd-4d89-bc60-665d5090b828` NOT7, `2a125640-706d-4dd1-b420-c0f4ab93c361` NOT8, `82a9f452-c5ce-4ef5-8203-3fc9a47f8171` NOT9, `deef7187-3f43-4364-9e22-11a8c8a15951` NOT10 | notifications (optional) |
| | `c95d91ae-b247-4d6d-8661-7dd5d6a0f85b` geotag sync interval | subscribe (required), then write the interval in seconds, uint16 little-endian |
| `3b46ec2b-48ba-41fd-b1b8-ed860b60d22b` geotag | `0f36ec14-29e5-411a-a1b6-64ee8383f090` geotag | 23-byte packet, write with response |
| `6514eb81-4e8f-458d-aa2a-e691336cdfac` shutter | `7fcf49c6-4ff0-4777-a03d-1a79166af7a8` shutter | `01 00` then `02 00` press, `03 00` focus, `00 00` release (not used yet) |
| `91f1de68-dff6-466e-8b65-ff13b0f16fb8` legacy pairing | `aba356eb-9633-4e60-b73f-f52516dbd671` legacy pair | legacy firmware; GeoShutter only uses it to detect such a camera |

### Secure handshake

furble's `FujifilmSecure::_connect`, and what GeoShutter does:

1. Connect and secure (bond) the link. GeoShutter bonds when the camera is added
   (companion device association, then `createBond`), and Android only connects to bonded
   cameras.
2. Read the 4-byte status; write it back with byte 3 = `0x20` (example from furble:
   `07 96 00 00` → `07 96 00 20`).
3. Write the client name to the identifier characteristic. furble writes its device ID
   (`furble-xxxxx`); GeoShutter writes `GeoShutter`.
4. Subscribe, in this order, all required: IND1 and IND2 (indications), NOT1, geotag
   request, NOT4, NOT5.
5. Subscribe NOT6 to NOT10 (failures tolerated) and the sync-interval characteristic
   (required).
6. Write the sync interval: 10 seconds, `0A 00`.
7. furble then requires the shutter service/characteristic. GeoShutter only logs if it is
   missing, because geotagging doesn't need it.

Every write is a write with response. Authentication errors are retried like on Sony
cameras (three retries); if they persist, the app shows its "Pairing Failed" dialog.

### Geotag flow

- The camera notifies `01 00` on the geotag request characteristic; the phone answers by
  writing the geotag packet. Nothing is pushed without a request.
- GeoShutter answers each request with the latest location fix. A request that arrives
  before the first fix (or before the handshake finished) is answered as soon as possible.

Geotag packet, 23 bytes, little-endian:

| Offset | Size | Content |
|---|---|---|
| 0 | 4 | latitude × 10⁷, signed |
| 4 | 4 | longitude × 10⁷, signed |
| 8 | 4 | altitude in whole metres, signed (GeoShutter: mean sea level where Android provides it, otherwise WGS84; 0 if unknown) |
| 12 | 4 | `00 00 00 00` |
| 16 | 2 | year |
| 18 | 5 | month, day, hour, minute, second |

Values are truncated toward zero, like furble's C casts. The date/time is **UTC** (furble
takes it from a GPS receiver) and is the time of sending: a stationary phone may not
produce new fixes, and Fujifilm cameras ignore locations older than three hours.

## Implementation in GeoShutter

| File | Role |
|---|---|
| `sharednew/.../bluetooth/fujifilm/FujifilmBluetoothConstants.kt` | UUIDs, byte values, subscription lists |
| `sharednew/.../bluetooth/fujifilm/FujifilmPacketBuilder.kt` | geotag packet, status ack, sync interval |
| `sharednew/.../bluetooth/fujifilm/FujifilmSessionController.kt` | camera detection, the handshake as one sequence of queued operations, notification parsing |
| `CameraSessionOrchestrator.kt` | detection after service discovery (Sony first; Fujifilm secure; legacy Fujifilm → error), Fujifilm events kept away from the Sony handlers |
| `LocationTransmissionManager.kt` | Sony: pushed every 5 s; Fujifilm: answers requests (`onLocationRequested`) |
| `AndroidBleTransport.kt` | service-scoped characteristic lookup; enables indications for indication-only characteristics |
| `DeviceAssociationUtils.kt` | companion-device filter for `0x04D8` |
| `FujifilmPacketBuilderTest.kt`, `FujifilmSessionTest.kt` | packets checked against Python `struct.pack('<iii4sHBBBBB', …)`, handshake order, pull-only delivery, failure paths, no remote monitoring for Fujifilm, protocol re-detected on every connect, Sony and Fujifilm side by side |

Not implemented: iOS (the AccessorySetupKit picker only lists Sony cameras), the legacy
protocol, the remote shutter, date/time sync. Remote control and the camera time/area
settings are Sony features: the camera details hide them while a Fujifilm camera is
connected (the app only learns the brand when the camera connects), and the camera card
shows a plain "Connected".

## Verification status

| Item | Source | Verified on X100VI |
|---|---|---|
| Company ID and advertisement | furble | no |
| GATT table and properties | furble | no |
| Handshake order and values | furble | no |
| Geotag request `01 00` and sync interval `0A 00` | furble | no |
| Geotag packet layout | furble, Python cross-check | no |
| UTC time in the packet | furble (GPS time) | no |
| Whole app built and unit-tested | this repository | n/a (no camera) |

## Known gaps

- The X100VI protocol has not been confirmed on a real camera.
- It is unknown whether the camera sends geotag requests reliably after this handshake.
- UTC versus local time for the X100VI's EXIF GPS timestamp is unconfirmed.
- Behavior when the camera is switched off with Bluetooth left on is unknown, as is whether
  Android's companion-device presence detection sees the camera's advertisements.
- The date/time sync characteristic of XApp is unknown (furble doesn't implement it).

## Research plan

Work through these phases on real hardware and record the results in this file.

1. **Baseline.** `./gradlew :sharednew:testAndroidHostTest :app:assembleFossDebug`.
2. **Advertisement and GATT table.** With nRF Connect for Android, record the manufacturer
   data and advertised services in pairing mode and when paired, then connect, bond and
   export the full GATT table (properties and descriptors). Compare with the table above;
   record the firmware version and whether the camera is secure or legacy. Add a section
   "GATT table (X100VI, firmware x.xx)".
3. **Ground truth from XApp.** Enable the Bluetooth HCI snoop log, pair the X100VI with
   FUJIFILM XApp, enable location sync, shoot 5 to 10 photos, power-cycle the camera, then
   collect `btsnoop_hci.log` with `adb bugreport`. In Wireshark (`btatt`; opcodes `0x0a`/`0x0b`
   read, `0x12` write request, `0x52` write command, `0x1b` notification, `0x1d`
   indication) build a timeline and answer: Is the handshake order identical? Which name
   does XApp write? Which sync interval, how often? How often does the camera send `01 00`?
   Does XApp ever push without a request? Decode three geotag writes (layout, endianness,
   UTC or local). Is there a date/time sync write? What happens on power-off/on? Store the
   capture in `docs/captures/` (captures contain real coordinates: use a location you are
   fine publishing).
4. **Reconcile.** Update the constants, the handshake and the packet builder to match the
   capture; add tests with the captured bytes as fixtures; decide on a push fallback if
   XApp pushes unrequested.
5. **Field test.** Pair with GeoShutter, check the log for the status ack, handshake
   complete, requests every interval and successful writes. Shoot while walking, with the
   phone locked for 10 minutes, after a camera power cycle and with phone Bluetooth off;
   check `exiftool -gps:all -a -G1 *.JPG *.RAF` against the phone's track. Target: at least
   95% of photos within 30 m.
6. **Robustness.** Samsung battery settings (unrestricted and default), Always On, an idle
   hour, a Sony and a Fujifilm camera at the same time. Add a "Robustness" section here.
7. **Optional.** Remote shutter (behind the remote-control toggle), iOS support
   (AccessorySetupKit descriptor and Info.plist for `04D8`, indications, bonding), legacy
   firmware.

## Credits and license

The Fujifilm protocol knowledge and the ported logic come from furble:

```
MIT License

Copyright (c) 2020 Guo-Rong Koh

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

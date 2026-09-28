# Sony BLE intervalometer

A small command-line intervalometer that triggers a Sony camera's shutter over Bluetooth
LE with [bleak](https://github.com/hbldh/bleak). It is independent of the app, but it is
the reference for the remote-control bytes the app uses (see
`SonyBluetoothConstants.kt`): half press `01 07` → full press `01 09` → full release
`01 08` → half release `01 06`, optionally with an AF-ON pulse (`01 15` / `01 14`). After
each shot it waits for the camera's ready status on `FF02` (or for `--wait-time` seconds
if the camera offers no status notifications).

## Requirements

- Python 3 with `pip install -r requirements.txt` (`bleak`).
- Bluetooth remote control enabled on the camera (*Bluetooth Rmt Ctrl* → On). The script
  does not pair by itself; pairing is left to the operating system.
- The scanner only lists cameras whose Bluetooth name starts with `ILCE`.

## Usage

```bash
python3 intervalometer.py --count 100 --interval 5
python3 intervalometer.py --minutes 30 --interval 10 --camera-name 6700 --af-on
```

| Option | Meaning |
| --- | --- |
| `-c/--count N` or `-m/--minutes N` | number of shots, or total runtime (one is required) |
| `-i/--interval SECONDS` | minimum time between shot starts (required) |
| `-n/--camera-name TEXT` | pick the camera whose name contains this text (otherwise you are asked) |
| `-w/--wait-time SECONDS` | how long to wait for the ready status (default 20) |
| `--prefocus-ms`, `--fullpress-ms`, `--settle-ms` | button timings (defaults 80, 120, 60 ms) |
| `--af-on`, `--af-on-ms` | pulse AF-ON before every shot (default 120 ms) |

Non-bulb exposures only.

## Tests

```bash
python3 -m unittest test_intervalometer -v
```

Run from this directory; the tests use a fake client and need no Bluetooth hardware or
`bleak`.

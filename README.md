# OmniWallet

An Android app that acts as remote control and credential manager for external
emulation hardware. The phone holds the library — access cards, 125 kHz fobs,
sub-GHz remotes, iButton keys, IR remotes — and on one tap tells a connected
device over BLE to emulate the chosen one. The phone is the UI; the external
device is the radio.

Two backends behind one abstraction: **Flipper Zero** (official, Unleashed or
Momentum firmware) and **Chameleon Ultra**.

## What this is not

The phone does **not** emulate cards itself. Android HCE cannot present an
arbitrary UID or speak Mifare Classic, and that limitation is the entire reason
the external device exists. There is no phone-native card emulation anywhere in
this app, by design.

v1 is local-first: no account, no cloud, no telemetry. The app holds a map of
the user's physical-access credentials, so it is built like a password manager.

## Status

| Milestone | State |
|---|---|
| M0 — skeleton, domain model, empty DB | done |
| M1 — BLE core: scan, connect, reconnect, bonding | done, **awaiting hardware verification** |
| M2 — Flipper RPC: framing, flow control, storage_list, app start/stop | done, **awaiting hardware verification** |
| M3–M9 | not started |

"Awaiting hardware verification" is literal and important — see below.

## Verification split

This project is developed where there is **no Bluetooth radio, no emulator and
no Flipper**. That shapes the architecture rather than merely inconveniencing
it:

- What *can* be verified here — compilation and JVM unit tests — covers the
  protocol layers, so `:core:domain` and both `:protocol:*` modules are plain
  JVM with no Android dependency, and the framing, flow-control and
  correlation logic is exhaustively tested against fakes. 50 tests.
- What *cannot* be verified here is anything touching a radio. That is what
  `docs/HARDWARE-TEST.md` and the in-app Diagnostics screen are for: every
  state transition, GATT status and bond change is logged and exportable, so a
  failure comes back as evidence rather than a description.

Two real bugs were caught by the JVM tests before reaching hardware: the
Chameleon decoder stalled instead of resynchronising after a bad checksum, and
the flow-control gate rejected legitimate writes by mistaking the largest
credit yet seen for the device's true buffer size.

## Architecture

The device abstraction is the spine. Everything above it — wallet UI,
geofencing, widget, tile — is backend-agnostic; everything device-specific sits
behind it.

```
:core:domain        plain JVM. EmulatorDevice, Credential, Protocol,
                    ConnectionState. No Android imports, deliberately: it makes
                    leaking a device-shaped detail into the abstraction
                    impossible.
:core:data          Room. Library metadata.
:protocol:flipper   plain JVM. Wire-generated protobuf, varint framing, frame
                    reassembly, flow-control credit, has_next assembly.
:protocol:chameleon plain JVM. Frame codec + LRC.
:transport:ble      Nordic BleManager, scanning, permissions, bond monitoring,
                    reconnect policy. Knows about no particular device.
:device:flipper     FlipperDevice: binds protocol to transport.
:device:chameleon   ChameleonDevice: stub until M5.
:app                Compose UI, Hilt, navigation, diagnostics.
```

`ChameleonDevice` exists now, unfinished, on purpose. An interface designed
against a single backend invariably grows that backend's shape, and the
Chameleon is genuinely different: NUS rather than a custom serial service,
checksummed frames rather than length-delimited protobuf, eight fixed slots
rather than a filesystem. Nothing in `EmulatorDevice` presumes files, apps or
slots, and the stub is how that claim stays honest.

## Protocol

`docs/PROTOCOL-NOTES.md` records every wire-format fact with a citation to
firmware or a vendor client, and lists what is *not* confirmed. Nothing there is
inferred. Highlights worth knowing before touching this code:

- The Flipper's TX characteristic is **INDICATE, not notify**. Subscribing with
  notifications succeeds and then delivers nothing.
- Flow control reports an **absolute** free-buffer figure, not a delta.
  Accumulating it overruns the device.
- `AppStartRequest` names are the apps' **display names**, case-sensitively:
  `NFC`, `125 kHz RFID`, `Sub-GHz`, `Infrared`, `iButton`.
- Vendored protobuf is official firmware 0.25 (`1c84fa4`), verified to be a
  genuine common subset with Unleashed and Momentum.

## Building

Requires JDK 17 and an Android SDK with platform 37 and build-tools 37.

```sh
./gradlew :app:assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # JVM protocol + policy tests
```

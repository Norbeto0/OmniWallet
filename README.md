# OmniWallet

A vault for the credentials you carry, that happens to be able to drive the
hardware which emits them.

Your access cards, 125 kHz fobs, sub-GHz remotes and iButton keys, under *your*
names for them, encrypted on the phone and locked behind your fingerprint. Tap
one and a connected Flipper Zero emits it.

## Why this exists when the official Flipper app is right there

It is a fair question, and the honest answer narrows what this project is for.

The official app is a **device companion**: a file browser over the Flipper's
own storage, plus firmware updates, key editing, sharing and an app catalogue.
It is well staffed, hardware-verified throughout, and for "emulate a saved card
from my phone" it is simply better. It also has a home-screen widget that works
— which is why this project no longer has one.

What it is not is a **vault**. Reading its source (1,912 Kotlin files) for the
properties this project cares about:

| | Official app | OmniWallet |
|---|---|---|
| App lock / biometric | no | yes, on by default |
| Database encrypted at rest | no (plaintext) | SQLCipher, Keystore-wrapped key |
| Your own name for a card | renames the file **on the device** | local alias; device untouched |
| Search across your library | folder browsing | names and tagged places |
| Cards suggested by where you are | no | yes, foreground-only |
| Automation hook for other apps | no | yes, opt-in and off by default |
| Chameleon Ultra | no | abstraction ready, backend stubbed |

Its key list is named the way the files are named. This one holds "Office
door", not `Opatov_karta.nfc`, and will not let a stranger who picks up your
unlocked phone read the map of every door you can open.

If you want a Flipper companion, use theirs. This is a password manager that
speaks to a Flipper.

## What this is not

The phone does **not** emulate cards itself. Android HCE cannot present an
arbitrary UID or speak Mifare Classic, and that limitation is the entire reason
the external device exists. There is no phone-native card emulation anywhere in
this app, by design.

Local-first and local-only: no account, no cloud, no telemetry. Location, when
used, is read in the foreground only and never leaves the device.

## Status

| Milestone | State |
|---|---|
| M0 — skeleton, domain model | done |
| M1 — BLE core: scan, connect, reconnect, bonding | done, **hardware-verified** |
| M2 — Flipper RPC: framing, flow control, listing, app start/stop | done, **hardware-verified** |
| M3 — wallet UI | done, **hardware-verified** |
| M4 — SQLCipher at rest, biometric lock | done, lock untested on hardware |
| M6 — NFC, sub-GHz, iButton, 125 kHz RFID | done, **hardware-verified** |
| M7 — place tagging, nearby ranking | done, **untested** |
| M8 — automation intent, foreground service | done, **untested** |
| M9 — onboarding, firmware reporting, device switcher | done, **untested** |
| M5 — Chameleon Ultra | stub only, never connected |
| M6 — Infrared | not done; needs a different three-step flow |

Removed rather than finished: the home-screen widget and Quick Settings tile.
See above.

"Untested" is literal. `docs/HARDWARE-TEST.md` is the script that settles it.

## Verification split

This project is developed where there is **no Bluetooth radio, no emulator, no
Flipper and no GPS**. That shapes the architecture rather than merely
inconveniencing it:

- What *can* be verified here — compilation, lint and JVM unit tests — covers
  the protocol and policy layers, so `:core:domain` and both `:protocol:*`
  modules are plain JVM with no Android dependency, and everything risky is
  a pure function tested against fixtures. **164 tests.**
- What *cannot* be verified here is anything touching a radio, a widget host
  or a satellite. That is what `docs/HARDWARE-TEST.md` and the in-app
  Diagnostics screen are for: every state transition, GATT status and bond
  change is logged and exportable, so a failure comes back as evidence rather
  than a description.

Bugs the JVM tests caught before hardware: a Chameleon decoder that stalled
instead of resynchronising after a bad checksum; a flow-control gate that
rejected legitimate writes by mistaking the largest credit yet seen for the
device's true buffer size; an app-lock policy that failed *open* on a backwards
clock. Lint caught two more, including `Location.getElapsedRealtimeMillis()`
being API 33 rather than the 29 assumed, which would have crashed below that.

Two bugs it did *not* catch, both found on hardware and both instructive: the
RPC status characteristic is one byte, not four; and a service that watched a
`StateFlow` for "nothing is emulating" stopped itself before the work that
started it had begun.

## Architecture

The device abstraction is the spine. Everything above it is backend-agnostic;
everything device-specific sits behind it.

```
:core:domain        plain JVM. EmulatorDevice, Credential, Protocol,
                    ConnectionState, and the pure policies: CredentialMerge,
                    WalletOrdering, CredentialSearch, ProximityRanking,
                    QuickActionPolicy, ServiceLifecyclePolicy. No Android
                    imports, deliberately.
:core:data          Room + SQLCipher. Library metadata, encrypted at rest.
:protocol:flipper   plain JVM. Wire-generated protobuf, varint framing, frame
                    reassembly, flow-control credit, firmware compatibility.
:protocol:chameleon plain JVM. Frame codec + LRC.
:transport:ble      Nordic BleManager, scanning, permissions, bond monitoring,
                    reconnect policy. Knows about no particular device.
:device:flipper     FlipperDevice: binds protocol to transport.
:device:chameleon   ChameleonDevice: stub until M5.
:app                Compose UI, Hilt, navigation, diagnostics, the vault lock.
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
- A Flipper advertises a 16-bit UUID of `0x3080 | hw_color`, **not** its serial
  service. Filtering on the serial service matches nothing at all.
- The RPC status characteristic is **one byte**, read as `01-14-6E-0B` on
  hardware. Widening it to a `uint32` looks right from the firmware source and
  is wrong.
- `AppStartRequest` names are the apps' **display names**, case-sensitively:
  `NFC`, `125 kHz RFID`, `Sub-GHz`, `Infrared`, `iButton`.
- `AppExitRequest` only works for apps started in RPC mode. Stopping an
  emulation needs BACK input events plus `app_lock_status` as the termination
  condition.
- Vendored protobuf is official firmware 0.25 (`1c84fa4`), verified to be a
  genuine common subset with Unleashed and Momentum.

## Building

Requires JDK 17 and an Android SDK with platform 37 and build-tools 37.

```sh
./gradlew :app:assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew build                # compile + 164 tests + lint, both variants
```

`./gradlew build` is the gate, not `assembleDebug`. Lint has caught two real
defects on this project that compilation did not.

# Protocol notes

Every fact here was read from firmware source or a vendor client and is cited.
Nothing in this file is inferred. Where something is *not* confirmed, it says so
explicitly — those are listed at the end.

This matters because the app is developed in an environment with no Bluetooth
radio, no emulator and no Flipper, so a wrong guess about the protocol is not
caught until it reaches real hardware.

## Flipper Zero

### BLE serial profile

From `targets/f7/ble_glue/services/serial_service_uuid.inc`. The ST BLE stack
stores 128-bit UUIDs least-significant byte first, so the byte arrays in that
file appear reversed relative to these strings.

| Role | UUID |
|---|---|
| Serial service | `8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000` |
| TX (device → phone) | `19ed82ae-ed21-4c9d-4145-228e61fe0000` |
| RX (phone → device) | `19ed82ae-ed21-4c9d-4145-228e62fe0000` |
| Flow control | `19ed82ae-ed21-4c9d-4145-228e63fe0000` |
| RPC status | `19ed82ae-ed21-4c9d-4145-228e64fe0000` |

### Characteristic properties — the detail that bites

From the characteristic table in `serial_service.c`:

| Characteristic | Properties | Consequence |
|---|---|---|
| RX | `WRITE_WITHOUT_RESP \| WRITE \| READ`, `AUTHEN_READ \| AUTHEN_WRITE` | writable both ways; needs an authenticated link |
| TX | `READ \| INDICATE`, `AUTHEN_READ` | **indications, not notifications** |
| Flow control | `READ \| NOTIFY` | read once on connect, then subscribe |
| RPC status | `READ \| WRITE \| NOTIFY` | optional confirmation that RPC is live |

Two things follow that are easy to get wrong:

- **TX is `CHAR_PROP_INDICATE`.** Subscribing with notifications succeeds and
  then delivers nothing at all, which is indistinguishable from a device that
  never answers. `FlipperBleManager` uses `enableIndications`.
- **RX and TX carry `ATTR_PERMISSION_AUTHEN_*`.** The link must be
  authenticated, so **bonding is mandatory**. The first access triggers pairing;
  until it completes, operations fail with an authentication error.

### Flow control

From `serial_service.c` (`ble_svc_serial_set_callbacks`,
`ble_svc_serial_notify_buffer_is_empty`):

- The characteristic holds a **big-endian uint32** (`REVERSE_BYTES_U32` applied
  on a little-endian MCU): the bytes the device can accept right now.
- Each RX write decrements the device's counter by the write length. Exceeding
  it makes the firmware log `"Can lead to buffer overflow!"` and data is lost.
- When the device's buffer drains, the counter is reset to the **full buffer
  size** and notified.

**The value is absolute, not a delta.** A client that added each notified value
to a running total would steadily overestimate its allowance and overrun the
device. `FlowControlGate.onCreditReported` assigns; a test pins that behaviour.

### Framing

`varint32(main.byteSize) + main.bytes`, matching
`_VarintBytes(...) + SerializeToString()` in `flipperzero_protobuf_py`.

- `command_id` increments from 1; a reply carries its request's id. Replies can
  interleave, so calls are tracked in a map.
- Multi-part replies chain with `has_next`. `storage_list` chunks large
  directories; `device_info` sends **one key/value pair per message**.
- `command_id == 0` marks a device-initiated message, e.g. `AppStateResponse`
  when an app starts or exits.
- The firmware chunks TX (`ble_svc_serial_update_tx`), so messages straddle
  indications and must be reassembled.

There is **no `start_rpc_session` command over BLE** — that is a USB-serial
handshake. On BLE the firmware brings RPC up with the serial service.

### AppStartRequest app names

`rpc_app.c:91` passes the name to `loader_start`. `loader.c` resolves it two
ways:

- internal apps, line 364: `strcmp(name, list[i].name) == 0 || strcmp(name, list[i].appid) == 0`
- external apps, line 21: `strcmp(FLIPPER_EXTERNAL_APPS[i].name, app_name) == 0`

All five protocol apps are `FlipperAppType.MENUEXTERNAL`, so **only the display
name works**, case-sensitively. Names are from each app's `application.fam`:

| Protocol | `name` (use this) | `appid` | Directory | Extension |
|---|---|---|---|---|
| NFC | `NFC` | `nfc` | `/ext/nfc` | `.nfc` |
| 125 kHz RFID | `125 kHz RFID` | `lfrfid` | `/ext/lfrfid` | `.rfid` |
| Sub-GHz | `Sub-GHz` | `subghz` | `/ext/subghz` | `.sub` |
| Infrared | `Infrared` | `infrared` | `/ext/infrared` | `.ir` |
| iButton | `iButton` | `ibutton` | `/ext/ibutton` | `.ibtn` |

Plausible guesses — `Nfc`, `LfRfid`, `IButton` — all fail as
`ERROR_APP_CANT_START`. The client retries with `appId` on that error, which
costs one round trip and covers a custom firmware that renames an app.

### Firmware compatibility

Verified by diffing the forks rather than assumed:

| Surface | Momentum (`Next-Flip`) | Unleashed (`DarkFlippers`) |
|---|---|---|
| Serial service + characteristic UUIDs | byte-identical | byte-identical |
| Flow control | identical | identical |
| App names (all five) | identical | identical |
| `application/storage/system.proto` | byte-identical | byte-identical |
| `flipper.proto` | adds only `gui_send_ascii_event_request = 100` | adds only fields 76–90 + GPS/network errors |
| Pairing | same `gap.c`, 6-digit code via `bt.c` | same |

Every custom-firmware delta is additive and in messages this app does not use,
so protobuf's unknown-field handling covers it. Vendoring official 0.25
(`1c84fa4`) is therefore a genuine common subset.

**Scan filtering keys on the service UUID, never the device name** — Momentum
lets users rename their Flipper; the UUID is invariant.

### Pairing

`gap.c` shows the Flipper initiates a security request and *terminates the
connection* when pairing fails. `bt.c` renders a six-digit code
(`"Pairing code\n%06lu"`) the user types into Android.

Two consequences:

- Bonding can legitimately occupy **tens of seconds** while the user reads and
  types the code. `BOND_BONDING` must not be treated as a stall.
- A **stale bond is terminal, not retryable**. Changing firmware regenerates
  the device keys, so a bond Android still holds no longer matches and presents
  as an immediate drop after connecting. Only forgetting the device and pairing
  again fixes it, so `BONDING → NONE` maps to `FailureReason.PAIRING_REQUIRED`
  and retries stop.

## Chameleon Ultra

Transport and codec confirmed; the slot workflow is M5 work.

- **Transport is standard Nordic UART Service.** `ble_main.c` uses `BLE_NUS_DEF`
  and `BLE_UUID_NUS_SERVICE`, i.e. `6e400001-b5a3-f393-e0a9-e50e24dcca9e`, plus
  the Battery Service.
- **Frame layout** from `software/script/chameleon_com.py`
  (`make_data_frame_bytes`, struct `!BBHHHB{len}sB`, big-endian):

  | Offset | Size | Field |
  |---|---|---|
  | 0 | 1 | SOF `0x11` |
  | 1 | 1 | LRC1 over bytes `[0,1)` |
  | 2 | 2 | command (uint16 BE) |
  | 4 | 2 | status (uint16 BE) |
  | 6 | 2 | payload length (uint16 BE) |
  | 8 | 1 | LRC2 over bytes `[0,8)` |
  | 9 | N | payload |
  | 9+N | 1 | LRC3 over bytes `[0,9+N)` |

- **LRC** is `(0x100 - (sum & 0xFF)) & 0xFF`, so any span summed with its own
  check byte is 0 mod 256.
- **Command codes** from `chameleon_enum.py`: `GET_APP_VERSION=1000`,
  `GET_DEVICE_MODE=1002`, `SET_ACTIVE_SLOT=1003`, `SET_SLOT_TAG_TYPE=1004`,
  `SET_SLOT_ENABLE=1006`, `SLOT_DATA_CONFIG_SAVE=1009`, `GET_ACTIVE_SLOT=1018`,
  `GET_SLOT_INFO=1019`, `GET_ENABLED_SLOTS=1023`, `GET_DEVICE_MODEL=1033`,
  `MF1_WRITE_EMU_BLOCK_DATA=4000`, `HF14A_SET_ANTI_COLL_DATA=4001`.

## Not confirmed — do not assume

1. **Whether a Flipper always advertises its serial service UUID**, or only
   when RPC is available. If it does not, filtered scanning will miss it. The
   diagnostics screen has an unfiltered scan mode precisely so this can be
   settled from a real device.
2. **Whether each app begins emitting on launch, or waits for a button press.**
   `AppStartRequest` launching the app with the file as argument is confirmed;
   what the app does next is not. If it waits, `AppButtonPressRequest` (field
   49) is the follow-up, and `startEmulation` absorbs it per protocol.
3. **The Chameleon slot workflow** beyond the codec — deliberately unresearched
   until M5.

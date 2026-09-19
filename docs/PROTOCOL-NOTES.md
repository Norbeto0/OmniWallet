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

### RPC status characteristic

Declared `sizeof(uint32_t)` in the characteristic table, and
`ble_svc_serial_update_rpc_char` writes the `SerialServiceRpcStatus` enum
straight through with no byte-order conversion.

That invites reading it as a little-endian uint32. **Do not.** A live Momentum
session returns:

```
01-14-6E-0B
```

Byte 0 is the status (`01` = active); bytes 1-3 are whatever sat beside the enum
in memory, because the fixed four-byte length over-reads a smaller value. As a
little-endian uint32 those bytes are `0x0B6E1401`, and a perfectly healthy
session reports itself inactive — which is exactly what an earlier build did.

Read byte 0 only. `FlipperBleProfile.RpcStatus.isActive` does this and a test
pins the observed bytes.

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

### Advertising — what discovery must filter on

A Flipper does **not** advertise its serial service. From
`targets/f7/ble_glue/profiles/serial_profile.c`:

```c
.Service_UUID_16 = 0x3080,
...
config->adv_service.Service_UUID_16 |= furi_hal_version_get_hw_color();
```

It advertises a **16-bit** UUID of `0x3080 | hw_color`, expanded into the
Bluetooth base UUID. `FuriHalVersionColor` runs `0x00`–`0x03` (unknown, black,
white, transparent), so the advertised value varies per device. A unit with
`hardware_color = 2` advertises:

```
00003082-0000-1000-8000-00805f9b34fb
```

Confirmed on hardware. The 128-bit serial service appears only after connecting,
so **filtering a scan on it matches nothing at all** — which is precisely what an
earlier build did, silently.

Filter instead on `00003080-0000-1000-8000-00805f9b34fb` with mask
`fffffff0-ffff-ffff-ffff-ffffffffffff`. Momentum's `serial_profile.c` uses the
identical formula, so one filter covers every firmware and colour.

### Application lifecycle — starting and stopping

Three rules, all confirmed on hardware:

1. **`AppStartRequest` with a file path starts emulation immediately.** The app
   opens with the card loaded and begins emitting; no button press is needed.
2. **Only one app runs at a time.** Starting another while one is running
   returns `ERROR_APP_SYSTEM_LOCKED` (`LoaderStatusErrorAppStarted`). Switching
   cards therefore requires closing the running app first.
3. **`AppExitRequest` cannot close a file-launched app.** Its handler only acts
   when `rpc_app->callback` is set, which happens solely for apps started in RPC
   mode (`args == "RPC"`). Anything else answers `ERROR_APP_NOT_RUNNING` **and
   keeps running** — observed directly: the Flipper stayed in the NFC app while
   the call "succeeded".

So closing an app means backing out of it: `PB_Gui.SendInputEventRequest` with
`BACK`, sent as `PRESS` → `SHORT` → `RELEASE`. Use `app_lock_status_request` as
the termination condition; its handler returns `loader_is_locked(loader)`, the
very lock that produces `ERROR_APP_SYSTEM_LOCKED`, so it is a real check rather
than a guess at how many screens deep the app is.

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

## Confirmed on hardware

First hardware session: Pixel 10 Pro (API 37) and a Flipper Zero on Momentum
`mntm-012`, protobuf 0.25.

| | Result |
|---|---|
| Bonding, connection, service discovery | works |
| MTU | 414 negotiated from a 517 request |
| TX indications | works — notifications would have delivered nothing |
| Flow control | initial read `00-00-04-00` = 1024, big-endian decode confirmed |
| Ping round trip | 54–71 ms |
| `device_info` | 60 entries |
| `storage_list` | all five directories |
| App names | `NFC` resolved verbatim |
| Emulation | starts immediately on `AppStartRequest` |

Two questions this file previously listed as open are now settled, above:
a Flipper does **not** advertise its serial service UUID, and emulation does
**not** wait for a button press.

`device_info` key names are worth noting: there is no `firmware_origin`.
Momentum reports `firmware_origin_fork` (`Momentum`) and `firmware_origin_git`.

## Not confirmed — do not assume

1. **The Chameleon Ultra end to end.** Its transport and frame codec are read
   from firmware and unit-tested, but no Chameleon has been connected. The
   scan target for it is a reasonable inference, not an observation.
2. **How many BACK presses various apps need.** Bounded and checked against
   `app_lock_status` rather than assumed, so a deeper app costs round trips
   rather than correctness.
3. **`storage_list` is not recursive.** Sub-GHz files inside `Tesla/`,
   `remote/` and `playlist/` are not listed. Recursive listing belongs with the
   wallet UI in M3.

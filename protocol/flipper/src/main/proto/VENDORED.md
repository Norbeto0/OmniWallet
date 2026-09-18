# Vendored Flipper Zero protobuf definitions

Source: https://github.com/flipperdevices/flipperzero-protobuf
Tag:    0.25
Commit: 1c84fa48919cbb71d1cc65236fc0ee36740e24c6

**Do not hand-edit these files.** They are the protocol. To move to a newer
protobuf release, re-copy from the upstream repo at a specific tag and update
the commit above.

## Why the official firmware's copy, not a custom-firmware fork

This app targets the RPC surface common to official firmware, Unleashed and
Momentum. That common surface was verified, not assumed:

| Fork | `application/storage/system.proto` | `flipper.proto` |
|---|---|---|
| `DarkFlippers` (Unleashed) | byte-identical to this copy | adds only fields 76-90 + GPS/network error codes |
| `Next-Flip` (Momentum) | byte-identical to this copy | adds only `gui_send_ascii_event_request = 100` |

Every custom-firmware delta is additive and lands in messages this app does not
use, so protobuf's unknown-field handling covers it. Pinning the official 0.25
definitions therefore yields a genuine common subset rather than a compromise.

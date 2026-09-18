# Hardware test script — M1 and M2

This app is developed in an environment with **no Bluetooth radio, no emulator
and no Flipper**. Compilation and the JVM protocol tests are verified there;
everything involving an actual radio can only be verified by you, on your phone,
with your Flipper. This script is written so one session settles it.

Please run it in order and **export the log at the end** (button at the bottom
of the Diagnostics screen), even if everything passes. The log carries GATT
status codes and bond transitions that turn "it didn't connect" into a
diagnosis.

## Setup

1. Install the debug APK.
2. Flipper: **Settings → Bluetooth → ON**. Note which firmware you are running
   (official / Unleashed / Momentum) — all three are expected to work, and
   knowing which one you used matters if something fails.
3. Open OmniWallet → **Device diagnostics**.

## 1. Permissions

- Tap **Grant permissions**. On Android 12+ expect a "Nearby devices" prompt;
  below 12 expect a location prompt.
- The card should read *All required permissions granted* and *Bluetooth: on*.

> Below Android 12 the location permission is not optional: without it the
> platform returns **zero** scan results and reports no error at all.

## 2. Scan — the first real unknown

- Leave the mode on **Known devices** and tap **Scan**.
- **Expected:** your Flipper appears within a few seconds, named, with an RSSI
  and a service list including `8fe5b3d5-...`.

**If it does not appear**, this is the open question flagged in the protocol
notes: it is unconfirmed whether a Flipper always advertises its serial service
UUID. Do this:

1. Switch to **Everything** and scan again.
2. Find your Flipper in the list.
3. **Export the log and send it.** The advertised service list in that log
   settles whether filtered scanning can work, and what to filter on instead.

## 3. Connect and pair

- Tap **Connect** on your Flipper.
- **Expected on first connection:** the Flipper shows a six-digit code and
  Android prompts for it. Type it in.
- State should progress `connecting → discovering services → ready`.
- The Connection card should then show a non-zero **MTU** (usually 512-ish) and
  a non-zero **credit**.

> Bonding is mandatory here — the RX/TX characteristics require an
> authenticated link — and it can take tens of seconds while you type the code.
> That is expected, not a stall.

**If it connects and immediately drops**, and the screen says *Pairing needs to
be redone*: that is the stale-bond case, and the app is telling you so
deliberately. Forget the Flipper in Android Settings → Bluetooth, then connect
again. This is common after changing firmware, which regenerates the device
keys. Please confirm the message appeared — that path cannot be tested without
hardware.

## 4. Reliability — the actual point of M1

Do all three, checking the state line and the log each time:

| Test | How | Expected |
|---|---|---|
| **Sleep** | Lock the Flipper, wait ~30 s | link drops, state goes to `reconnecting, attempt N in …ms`, then recovers when it wakes |
| **Out of range** | Walk ~20 m away, then back | same: backoff while away, reconnect on return, delays growing roughly 1s, 2s, 4s, 8s (jittered, so not exact) |
| **User disconnect** | Tap **Disconnect** | goes to `disconnected` and **stays there** — no reconnect attempts in the log |

That last one is the one to watch. A deliberate disconnect that silently
reconnects means the app is fighting you, and it is a different code path from
the other two.

## 5. RPC

With the state at `ready`:

1. **Ping** → a round-trip time appears (single-digit to low tens of ms).
2. **Device info** → key/value pairs including `firmware_origin` and
   `hardware_name`. Please note what `firmware_origin` says.
3. **List files** → counts the saved items across `/ext/nfc`, `/ext/lfrfid`,
   `/ext/subghz`, `/ext/infrared`, `/ext/ibutton`.

## 6. Emulation — the one that proves the whole chain

1. In the file list, tap **Emulate** on a saved NFC or RFID item.
2. **Expected:** the Flipper switches to that app with your file loaded.
3. **Please report exactly what the Flipper does**: does it start emitting on
   its own, or does it open the file and wait for you to press a button?

That question is genuinely unresolved. `AppStartRequest` launching the app with
the file argument is confirmed from firmware; what the app does *next* is not.
If it waits, the fix is an `AppButtonPressRequest` follow-up, and knowing which
saves a guess.

4. Tap **Stop emulation** → the Flipper should return to its menu.

## 7. Manual app start — only if step 6 failed

If **Emulate** returned an error, use the manual card to isolate whether it is
the app name or the path:

- App name `NFC`, path `/ext/nfc/<your file>.nfc` → **Start app**

The five correct names are `NFC`, `125 kHz RFID`, `Sub-GHz`, `Infrared`,
`iButton` — case-sensitive, spaces included. If `NFC` fails but `nfc` works,
your firmware builds that app as internal rather than external, which is worth
knowing.

## Finally

Tap **Export log** and send the file. Useful either way: if everything passed
it confirms the MTU, credit behaviour and firmware on real hardware; if
something failed it contains the reason.

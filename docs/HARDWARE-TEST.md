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

## 2. Scan

- Leave the mode on **Known devices** and tap **Scan**.
- **Expected:** your Flipper appears within a few seconds, named, with an RSSI
  and a service list containing `0000308X-0000-1000-8000-00805f9b34fb`, where
  the last digit is your unit's colour code.

> The first hardware run proved that a Flipper advertises a 16-bit UUID of
> `0x3080 | hw_color`, not its serial service. Filtered scanning used to match
> nothing at all; it now filters on that pattern with the colour masked out. If
> your Flipper still does not appear here, switch to **Everything**, find it,
> and send the log — the advertised service list is what settles it.

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
2. **Device info** → key/value pairs including `firmware_origin_fork`,
   `hardware_name` and `hardware_color`. The header line should now name your
   firmware rather than printing `firmware=?`.
3. **List files** → counts the saved items across `/ext/nfc`, `/ext/lfrfid`,
   `/ext/subghz`, `/ext/infrared`, `/ext/ibutton`.

## 6. Emulation — including the two fixes

1. In the file list, tap **Emulate** on a saved NFC or RFID item.
   **Expected:** the Flipper switches to that app with your card loaded and
   starts emitting straight away.
2. **Now tap Emulate on a *different* card, without disconnecting.**
   **Expected:** it switches cleanly to the second card.
   This used to fail every time with `ERROR_APP_SYSTEM_LOCKED`, because the
   Flipper runs one app at a time and nothing closed the first one. The app now
   closes the running app before starting the next.
3. Tap **Stop emulation**, and **watch the Flipper's screen**.
   **Expected:** it actually leaves the app and returns to its menu.
   This is the other fix worth your attention. `AppExitRequest` cannot close an
   app that was launched with a file path — it answers `ERROR_APP_NOT_RUNNING`
   and the app keeps running — so the app previously reported "stopped" while
   the Flipper carried on emulating. It now backs out with BACK presses and
   checks the loader lock, and will report an error rather than claim success if
   the app will not close.

## 7. Manual app start — only if step 6 failed

If **Emulate** returned an error, use the manual card to isolate whether it is
the app name or the path:

- App name `NFC`, path `/ext/nfc/<your file>.nfc` → **Start app**

The five correct names are `NFC`, `125 kHz RFID`, `Sub-GHz`, `Infrared`,
`iButton` — case-sensitive, spaces included. If `NFC` fails but `nfc` works,
your firmware builds that app as internal rather than external, which is worth
knowing.

## What changed since the last build

Five fixes, four of them driven by your log:

| Fix | Symptom before |
|---|---|
| Scan filter uses the advertised 16-bit UUID | filtered scan found nothing |
| Close the running app before starting another | second card never worked |
| Stop backs out with BACK and verifies the lock | claimed "stopped" while still emulating |
| RPC status read from byte 0 | `rpcActive` always false |
| `firmware_origin_fork` key | header printed `firmware=?` |

## Finally

Tap **Export log** and send the file. Useful either way: if everything passed
it confirms the MTU, credit behaviour and firmware on real hardware; if
something failed it contains the reason.

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

## 6. The wallet

Everything below happens on the **Wallet** tab, which is now the main screen.
Section 7 is only for when something here fails.

1. Connect on the **Device** tab, then switch to **Wallet**. It reads the
   library automatically on connect.
2. Tap a saved NFC card. The Flipper should switch to that card and start
   emitting straight away, and a bar should appear above the navigation bar
   showing what is running and for how long.
3. **Tap the same card again.** It should *stop*. The card's icon turns into a
   stop square while it runs, so the second tap is advertised rather than
   something you have to guess at.
4. Tap a **different** card without disconnecting. It should switch cleanly.
5. Long-press a card, rename it, and save. Then hit refresh. **The rename must
   survive.** Kill the app and reopen it; it must still survive.

## 6a. Protocols nobody has tested yet

This is the point of this build. Only NFC has ever been confirmed on hardware.
Sub-GHz, iButton and 125 kHz RFID go through exactly the same code path and
*should* already work, but "should" is doing a lot of work in that sentence.

**First, before tapping anything: how many cards are in the list?**

The previous build found three files, because listing was not recursive and
most of the library sat one folder down. Listing now descends, so files inside
`Tesla/`, `remote/` and similar should appear.

- More cards than before, including sub-GHz ones → recursion works.
- **Still three** → recursion is not working, and that is the finding. Stop
  here and send the log; the rest of this section will not tell us much.

Then, one at a time:

| Protocol | What to tap | Expected |
|---|---|---|
| **Sub-GHz** | a card from `Tesla/` or `remote/` | the Flipper transmits; tap again to stop |
| **iButton** | any saved key, if you have one | behaves exactly like NFC |
| **125 kHz RFID** | any saved fob, if you have one | behaves exactly like NFC |

For each: did the Flipper actually *do* the thing, or did it just open the app?
That distinction is the whole result — the app reporting success only means the
device accepted the command.

**Infrared — where a negative result is the useful one.**

Tap `Samsung.ir` and watch the Flipper. I expect the remote to open and
**nothing to be transmitted**, because a `.ir` file is a whole remote of named
buttons rather than a single transmission, and nothing yet presses a button.

If that is what happens, it confirms the design for the IR work: the Infrared
app has to be started in RPC mode, the file loaded separately, and a named
button pressed — a different flow from every other protocol, forced by the same
firmware constraint that broke the stop button earlier.

If it *does* transmit something, my reading of the firmware is wrong, and
knowing that early saves building the wrong thing.

## 7. Manual app start — only if something in 6 or 6a failed

If tapping a card returned an error, use the manual control on the
**Diagnostics** screen to isolate whether it is the app name or the path:

- App name `NFC`, path `/ext/nfc/<your file>.nfc` → **Start app**

The five correct names are `NFC`, `125 kHz RFID`, `Sub-GHz`, `Infrared`,
`iButton` — case-sensitive, spaces included. If `NFC` fails but `nfc` works,
your firmware builds that app as internal rather than external, which is worth
knowing.

## What changed since the last build

| Change | Why it matters here |
|---|---|
| The wallet screen exists | cards, grouped by protocol, tap to emulate |
| Tap a running card again to stop | it used to restart, which was the wrong gesture |
| Recursive listing | sub-GHz files in sub-folders should now appear — step 6a |
| Bottom navigation | Wallet / Device / Settings; tapping between them should always work |
| Library encrypted at rest | your renames migrate from the old database on first launch |
| App lock | Settings → Security, off by default |

If you are upgrading, **install over the top rather than uninstalling** — a
clean install loses the renames and favourites the migration is there to
preserve.

## Finally

Go to **Device → Diagnostics**, tap **Export log**, and send the file.

Useful either way. If everything passed it records the MTU, credit behaviour
and firmware on real hardware. If something failed it carries the actual
`CommandStatus` the Flipper returned, which is worth considerably more than a
description of what went wrong — every protocol bug found in this project so
far was identified from a status code in one of these logs.

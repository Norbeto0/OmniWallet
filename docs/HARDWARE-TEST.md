# Hardware test script

This app is developed in an environment with **no Bluetooth radio, no emulator,
no Flipper and no GPS**. Compilation, lint and 156 JVM tests are verified there;
everything involving actual hardware can only be verified by you, on your phone.
This script is written so one session settles it.

Sections 1 to 7 cover what already works and are worth a quick re-run.
**Sections 8 to 12 are the new ones and none of it has run on a phone** — that
is where a negative result is worth the most.

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

## 8. Onboarding and the launcher icon — M9

Only visible on a **clean install**, so do this one first if you are going to
do it at all.

1. Uninstall, then install. Three panes should appear.
2. The first pane says the phone does not emulate cards itself. That sentence
   is there because it is the single most likely misunderstanding this app can
   produce; tell me if it reads as confusing rather than clarifying.
3. **Grant and continue** should ask for Nearby devices and, on Android 13+,
   notifications in the same prompt. It should then land on the **Device** tab.
4. **Skip** should also work, and should not ask again next launch.
5. The launcher icon should be an orange card rather than the Android default.

> If you are upgrading rather than reinstalling, onboarding will not appear:
> the flag defaults to unset, and an existing install has never written it.
> That is expected. Uninstall first if you want to see it.

## 9. Places — M7

1. Long-press any card → **Place** → **Tag this place**. Android should ask for
   location the first time. Accept.
2. It should tag within a few seconds. **If it says it could not get a
   location, that is the result I need to hear about** — it means the platform
   provider returned nothing, and the log will say which providers were tried.
3. The card should now appear under a **Nearby** section at the top of the
   wallet, showing the place name and a distance.
4. Rename the place in the sheet and save. The name should stick.
5. **Walk away and come back.** Out of range (more than ~150 m) the Nearby
   section should disappear on returning to the app; back at the place it
   should return.
6. **Remove** should untag it and the section should go.

Coarse location instead of precise is fine and supported — the question is
"which building", not "which doorway". If you granted approximate location,
please say so, because the accuracy the platform reports changes how wide the
search is.

## 10. Quick surfaces — M8

**None of this has ever run on a phone.** Everything below is the part I could
not verify, so a negative result here is worth more than everything above it.

### Widget

The previous build's widget did nothing at all when tapped, and said nothing
about why. Both halves of that are fixed, so there are two things to check.

1. Long-press the home screen → Widgets → OmniWallet. Add it.
2. It should list your most-used cards. With nothing loaded yet it should say
   so in a sentence rather than showing an empty box.
3. **Tap a row with the Flipper awake.** Expected: it connects if needed and
   starts emitting within a few seconds, with a notification.
4. **Tap the same row again.** It should stop.
5. **Tap a row with the Flipper switched off.** Expected: a message saying it
   could not reach the device. Not silence.
6. Emulate a card from inside the app, then look at the widget: that row should
   read *"Emulating · tap to stop"*.

**The guarantee to test is that a tap is never silent.** Every tap should end
in either emulation or a visible message — a notification, or a toast if the
service could not start at all. If any tap does nothing whatsoever, that is the
bug again and worth saying so immediately.

If you get *"Android would not let OmniWallet start from the home screen"*,
that is the OS restriction rather than a bug I can fix. Please say whether
opening the app first and then tapping changes it, because that decides whether
the widget is viable on your phone at all.

One case that is worth trying on purpose: **tap Disconnect in the app, then tap
a widget row.** It used to be that one deliberate disconnect permanently broke
every widget tap thereafter. It should now connect.

### Quick Settings tile

1. Edit your Quick Settings and add **OmniWallet**.
2. Tap it with the phone unlocked → it should emulate your most recent card,
   and the tile should light up with that card's name.
3. Tap again → stop.
4. **From the lock screen:** it should ask you to unlock first. If it emulates
   without unlocking, stop and tell me — that is a security bug, not a rough
   edge.

### Does emulation survive backgrounding?

1. Start emulating a card.
2. Press Home. Open two or three other apps.
3. The notification should still be there, and the Flipper should still be
   emitting.
4. Tap **Stop** in the notification. It should actually stop.

This is the one I am least able to predict. Android's rules for foreground
services changed repeatedly and vary by manufacturer; a Samsung or Xiaomi phone
may kill it regardless.

### The automation intent

Off by default. **Check that first:**

1. Without touching Settings, send the broadcast below. Expected: nothing
   happens, and Settings → Quick access shows *"Refused an automation request:
   automation is switched off"*.
2. Now turn **Settings → Quick access → Allow other apps to trigger** on.
3. Send it again. Expected: it emulates.

From a computer with adb, or Tasker's "Send Intent" action:

```
adb shell am broadcast -a dev.omniwallet.action.EMULATE \
  -n dev.omniwallet.app/.quick.AutomationReceiver \
  --es name "Opatov_karta"
```

```
adb shell am broadcast -a dev.omniwallet.action.STOP \
  -n dev.omniwallet.app/.quick.AutomationReceiver
```

**A refusal saying Android blocked it is a real and expected outcome**, not a
bug I can fix: Android 12 and later forbid starting a foreground service from
the background, and a broadcast from another app usually has no exemption. If
that is what you see, try again with OmniWallet recently opened and tell me
whether that changes it. Knowing which case applies on your phone decides
whether this feature is worth keeping.

4. With the **app lock on**, every start request should be refused and the app
   should open instead. Stop requests should still work. Please check both.

## 11. Two devices — M9

Only if you have a second Flipper, or can borrow one.

1. Connect to the first. Disconnect.
2. Connect to the second.
3. The Device tab should now show **Your devices** with both listed, and
   tapping either should connect without a scan first.
4. **Forget** should remove one from the list.

## 12. Firmware reporting — M9

The Device tab should name your firmware and hardware next to the connection.
On Momentum it should read something like *Momentum mntm-XXX · Flipper*.

It should say nothing further. A line about compatibility only appears for a
fork nobody has tested or a protocol version this app was not built against —
and if you see one on Momentum, that is a bug, because Momentum is diffed and
verified in `docs/PROTOCOL-NOTES.md`.

## What changed since the last build

| Change | Why it matters here |
|---|---|
| Auto-connect | reconnects to your last device on launch and on returning to the app |
| Places (M7) | tag a card to a location, see it first when you are there |
| Widget, tile, intent (M8) | emulate without opening the app — sections 10 |
| Foreground service (M8) | emulation should survive you switching apps |
| Onboarding (M9) | clean installs only — section 8 |
| Device switcher (M9) | two Flippers without rescanning |
| Firmware reporting (M9) | what you are running, shown on the Device tab |
| Launcher icon (M9) | no longer the Android default |

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

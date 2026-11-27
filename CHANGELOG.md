# Changelog

What changed in each AutoBridge version since the project went public (November 23, 2026).
The truck app comes from the Play Store test. The phone app is on the [Releases](https://github.com/vlad8110/AutoBridge/releases) page; this link always downloads the newest one: https://github.com/vlad8110/AutoBridge/releases/latest/download/AutoBridge-Phone.apk

## 0.8.14 (in progress, not released)
- Equalizer: while driving, the greyed-out pencil button no longer reacts at all when pressed (no press bounce, no sound).

## 0.8.13 (November 27, 2026, truck app only)
- Equalizer faders redesigned ("Ribbed"): about 15% smaller, truly black caps with fine vertical ribs that catch the light only at their tops, and a short dull red slit in the middle instead of the bright lamp.

## 0.8.12 (November 27, 2026, truck app only)
- Equalizer faders: the caps are true black, and the red lamp is dimmer, like smoked glass with a small glow in the middle (closer to the chosen design).

## 0.8.11 (November 27, 2026, truck app only)
- Equalizer: each band's knob is now a slim black fader cap with a smoked-red lamp, and the line joining the bands is gone. The Equalizer icon (settings page and the equalizer's top row) has matching fader caps. Everything works as before: drag, presets, Custom 1-3, the pencil, hidden while driving.

## 0.8.10 (November 27, 2026, truck app only)
- Truck app: hiding Android Auto's Signal or Battery icon no longer breaks Android Auto's layout (in 0.8.9 the picture shifted left and the app dock disappeared). Back to the 0.8.8 way: the clock hides; on some phones, like the Galaxy Z Fold8, the signal and battery icons stay.

## 0.8.9 (November 27, 2026)
- Equalizer: while driving, the drag knobs, the curve and the dB numbers hide, leaving the lights. A new pencil button (between the EQ switch and Android Auto) hides them in Park too; tap it again to bring them back. The EQ switch and the Android Auto button on that page are now icons, like the settings page.
- Android Auto icons: hiding the phone's signal and battery now also uses the newer way Android Auto asks for it (the head unit lists the icons it draws itself), since newer Android Auto versions ignore the old switch. The clock already worked.
- Two phones on the hotspot: only the phone whose Android Auto the truck started over Bluetooth answers the truck, so the truck no longer connects to a passenger's phone that also runs AutoBridge Phone. Update both apps.

## 0.8.8 (November 27, 2026)
- **Update both apps.** New pairing code: the truck shows a 12-letter code in Settings (under Sound). Type it once into AutoBridge Phone (Pairing code). From then on the phone and truck recognize each other by the code, not the hotspot password that everyone on the hotspot knows, so another device on the hotspot can't pose as either one. Until the code is typed, the hotspot password works as before.
- With the code typed in: logs and screenshots travel encrypted over the hotspot, and Settings > Test only trusts hotspot details from your phone.
- Phone app: a connection to the Android Auto port that waits more than a minute for the truck is dropped, so a stray app can't sit waiting there.
- Only the truck's own apps (media card, source switcher) can control AutoBridge's music.
- Full-screen screenshots: the screen capture ends 10 minutes after the last screenshot, so Android's "screen is being shared" icon doesn't stay up all drive. The next screenshot in Park asks again.
- Settings: Test, Edit, Forget and Force close are as tall as the other buttons, easier to hit. The Voice line reads "Truck assistant" or "Google, through the phone" instead of being cut off. The truck picture is a little smaller and the repeated Android Auto status line is gone, so the card's bottom lines show in full.
- The charge-port glow in the truck bar breathes at 8 frames a second instead of 16, half the work over a long charge.
- From last night's audit: the mic and GPS no longer risk stopping after a screenshot session ends; the equalizer redraws 4 times a second instead of 30 while no music plays; the settings page doesn't animate under the open equalizer; the start-after-reboot part listens only to Android; and the screen-capture question never pops up while driving.
- On a GMC, AutoBridge now uses the truck's own font (GM's GMSUIGMCGlobal, read from the truck; Montserrat elsewhere). The log says which font files it used.
- Screenshots can take the whole truck screen, status bar and climate bar included: the first screenshot asks on the truck to allow screen capture (tap Start, once per run). Tap Cancel, or a truck that doesn't allow it, and screenshots stay AutoBridge-only as before.
- Equalizer Custom 1-3: an empty slot is Flat, and tapping it no longer copies the current curve into it (that's how one save could look like it landed in all three). Saving is press and hold only, and now says so: a buzz, a short chime and the slot's light blinking.
- Phone app: when the truck's log or screenshot is saved, you hear a chime (log) or a camera shutter (screenshot) and feel a double buzz, instead of an "Asked the truck" message. A message only shows if the truck isn't connected.
- Phone app: the Refresh button no longer gets cut off against the hotspot box under it.
- Press and hold the floating gear to go straight to the equalizer (a tap still opens settings, a drag still moves it).
- Truck bar: the speed shows only out of Park, and the charge port only while it's open, plugged in or charging. When they're hidden, the regen/power meter stretches into their space. (Tapping the port, the hidden shortcut to settings, works whenever it shows; the floating gear always does.)
- The settings gear over Android Auto: no more dark circle behind it, and the owner's gear: a thin light-grey outline with eight tapered teeth and a large round centre (with a soft shadow so it still shows over a light map). It starts lower, in the middle of Android Auto's bottom bar.

## 0.8.7 (November 26, 2026)
- The settings gear over Android Auto now starts in the empty part of Android Auto's bottom bar (between the mic and the app icons) instead of over Android Auto's own buttons in the top-right corner. Drag it anywhere; it stays there.
- Android Auto size: the layout named under the size now matches what the truck shows ("Map on top, two cards" where it used to say "Map and one card").
- Android Auto now sees the truck as a GMC Sierra EV Denali (GM reports only its code, BT1CG).
- Phone app: its button lights match the truck's: thinner, a little dimmer, fading at both ends.
- New font: Montserrat, the closest free match to the truck's own wide, round climate-bar font, on the settings, equalizer and dialogs (the truck bar under Android Auto is unchanged). A few labels got shorter to fit: Voice, To the phone, Lit: shown.
- The log now records the Android Auto size and hidden icons, and lists the fonts installed on the truck.
- Send log now sends everything the truck has saved (up to about 4 MB, several days of drives) instead of only the newest 10,000 lines, with the newest app start at the top and the oldest at the bottom.

## 0.8.6 (November 26, 2026, truck app only)
- Settings: Display moved up against the battery card, with a bigger heading. Truck bar, Status bar and HD picture are now one "Screen" row of three buttons, like the Android Auto size row.
- Settings: the truck hotspot moved into the truck card under the phone name, as a quiet set-once setting (name, address, then small Test, Edit and Forget buttons), so the address isn't cut off any more.
- Exit is now "Force close", next to the hotspot buttons. Each force close is written to the log, so a log shows it was done on purpose.
- The Android Auto button shows a round Android Auto badge: blue while it runs, grey when not.
- Button lights are thinner, a little dimmer, and fade at both ends like a curved lens.
- Battery card: while not charging, power never drops below 1 kW (the truck reports whole kW, so under 1 kW read as 0 and the box disappeared).

## 0.8.5 (November 26, 2026)
- Fixed: the battery card on the settings page lost its bottom edge when the truck wasn't charging.
- Equalizer: Truck, Vocal and Highway presets removed. Three saved curves (Custom 1, 2, 3) at the end of the presets, like the truck's radio favorites: tap to load, press and hold to save the curve you have now. An empty one saves on the first tap.
- Equalizer lights now hit on the beat: they wait for the speakers instead of showing the music about a second early.
- Phone app: on home or work Wi-Fi it says "Not on the truck's hotspot" instead of asking for a password in amber.
- Security: the truck and the phone app now check each other before Android Auto starts. Each proves it knows the hotspot password, so another device on the hotspot can't pose as the phone (to hear the cabin mic or see the truck's location) or as the truck (to see your phone's screen). Update both apps together: 0.8.1 won't connect to a 0.8.0 phone app or truck app. The phone app needs the hotspot password saved (Password).
- A see-through gear sits over Android Auto: tap it for AutoBridge's settings without restarting; drag it anywhere and it stays there.
- Settings › Display › Android Auto size: Auto (the truck's own density) or − / + to make Android Auto show more or bigger. Kept separately for each truck bar / status bar combination, with the layout it should give shown under it.
- Settings › Display › Android Auto icons: show or hide Android Auto's clock, signal and battery. All shown by default.
- Android Auto now sees the truck's own make, model and year instead of "AutoBridge AAOS 2025".
- Android Auto keeps the same layout for the same settings. With the status bar hidden it sometimes came up with the map beside the cards instead of on top, until a restart: AutoBridge had measured the screen mid-change (the status bar still showing). It now remembers the size for each truck bar / status bar combination, measured only once the screen has settled.
- Charging: the charge-port glow and battery sweep no longer pile up into a full-speed redraw; AutoBridge's screen work while idle is less than half what it was.
- Fixed: leaving AutoBridge's screen while the picture was starting could crash the app and drop Android Auto. A decoder error now restarts the picture instead of freezing it, and the "use 1080p next time" fallback only kicks in when H.265 really showed nothing.
- Fixed: after one failed start, the microphone (Google Assistant) stayed silent until the phone reconnected.
- Smoother: album art no longer stalls sound and picture at song changes; disconnecting no longer freezes the screen for 200 ms; the phone's Screenshot button no longer freezes touches; switching an EQ preset no longer clicks.
- Truck GPS is used only while Android Auto runs. A new truck with no phone app looks for it less often over time (up to every 2 minutes). The EQ lights stop measuring when AutoBridge leaves the screen.
- AutoBridge no longer opens the permission screen at every start on cars that lack some of the newer car permissions.
- Hotspot passwords are no longer copied into Android's cloud backup (both apps).
- Fixed: AutoBridge no longer crashes at truck start or after an update when its Bluetooth permission hasn't been granted yet.

## 0.8.0
- New look on both apps: black buttons with white text, a red light inside each button that shows what's on, and a spring when pressed. No more blue anywhere; the waiting screen is black.
- Truck bar: grey outline like every other card, all text white; only the power (kW) is coloured, green charging, red driving.
- Settings: Equalizer and Android Auto buttons under the truck name (the Equalizer light shows the EQ is on; the play sign turns red while Android Auto runs), then the phone and quiet system details. The battery card is white with only power coloured, and shows power while driving. Updates live when Android Auto starts or stops.
- Equalizer: research-based presets for the Sierra EV's Bose system, including a new gentle "Truck" preset; the top band is now an "Air" treble shelf (the old 16 kHz band barely worked); a soft limiter replaces hard clipping; LED lights half as wide; a settings gear replaces Done.
- Hotspot details: a bigger dialog you can read while typing, an eye to show the password, and colons added to the BSSID automatically. Warnings clear as you fix them.
- Phone app: the same buttons as the truck, a Recent card with the newest 3 events (tap for the last hour), and a clearer connected status that no longer gets confused after several log sends.
- Failures on the waiting screen are marked FAIL, not OK. The dashboard media card no longer shows the last drive's song at start-up.

## 0.7.4 (truck app only)
- Equalizer: each band is now a column of LED meter lights (green, yellow, red) that move with the music actually playing, like a stereo's level meter. They dim when EQ is off and fall dark when the music stops. Measured only while the EQ page is open.
- Equalizer: an Android Auto button next to Done goes straight back to Android Auto.
- Settings: Equalizer and Android Auto are side-by-side buttons with icons; Close app moved under Send log.
- The truck bar is 5 px shorter, everything in it scaled to match.

## 0.7.3 (truck app only)
- Turn-by-turn on the dashboard comes back by itself if the truck drops it during navigation (seen when the phone's Bluetooth reconnects).
- Truck bar: says each thing once ("1 h 45 min left", limit on the meter, Charging under the port) and lines up with Android Auto's edges.
- The battery card shrinks when plugged in but not charging.
- The waiting screen's Settings button matches the truck's style.

## 0.7.2
- New look for the phone app, matching the truck: black panels with the cut corner, the connection status first in big type (Connected / Waiting / Add the hotspot password), log buttons next, hotspot details last. Plain-word activity list.
- Settings: the status label (Plugged in, Charging) no longer spills out of its box; long phone names are cut with "…"; the battery is no longer shown twice; charging-only details hide when not charging; Forget asks first; buttons in normal case; one green everywhere.
- Equalizer: a Custom chip lights up when you drag a band; EQ on/off looks like a switch; shorter help.
- The phone only treats a connection as the truck once Android Auto runs through it; quiet after the phone app restarts; no crash when Android stops the relay; uploads side by side again.
- Truck log reader can't leak if storage fills; a new phone gets 60 s (was 20 s) for Android Auto's first-time screens.

## 0.7.1
- Fixed: when Bluetooth reconnected during a session, the truck asked the phone to rejoin Wi-Fi, which could knock a working Android Auto off. It now leaves a running session alone.
- A failed Wi-Fi join now says the hotspot password is probably wrong (the phone is near the truck if Bluetooth works), and the Wi-Fi check names which part differs.
- The microphone can no longer get stuck on after a session ends.
- Fixed a rare crash when the video catches up just as the phone disconnects.
- Reverse speed shows without a minus sign.
- Turning a setting on and back off no longer restarts Android Auto.
- The truck log keeps recording if its log reader restarts.
- Fewer leaked connections when searching for the phone; the phone relay no longer piles up retries.
- Less work while charging (charge-port glow) and while Android Auto is on screen (hidden console cursor, one scan per video frame).
- Removed unused code (outside temperature, unused bar fields).

## 0.7.0
Security and reliability fixes from a full code audit.
- Phone app: only hands a truck the hotspot details when it's on that truck's network (same router and frequency), so another device on home or café Wi-Fi can't ask for the password.
- Phone app: accepts logs and screenshots only from the truck it's connected to, one at a time, without reserving memory for what a sender merely claims.
- Truck: the automatic Wi-Fi check no longer changes saved hotspot details by itself; it asks you to tap TEST.
- Truck: no more screen freezes while the Wi-Fi check waits on the network.
- Truck: a connection that drops during the handshake no longer sits at "connected" forever; it gives up after 20 s and retries.
- Truck: a Wi-Fi blip while connecting no longer switches the picture to 1080p for good.
- Truck: GPS stops when you exit AutoBridge.
- Truck: an error inside a phone callback no longer crashes the app when the session ends.

## 0.6.9 (truck app only)
- Driving safety: the hotspot EDIT box (it opens a keyboard) only opens when parked, and closes if the truck starts moving. EQ and the other settings still work while driving.
- If the truck's car service doesn't answer, AutoBridge now tells Android Auto the truck is moving, so its own driving limits stay on (it used to say parked).

## 0.6.8 (truck app only)
- Fixed: music briefly cut out a few minutes in, and whenever the phone's notification shade was pulled down. The truck now holds 0.4 s of music in reserve and rebuilds it if it ever runs dry. Directions and voice still play instantly.

## 0.6.7
- Quieter Wi-Fi check: a truck that already works only reports when it fixed something (TEST still reports everything).
- When the phone can't rejoin the hotspot, the message now says it may just be out of range.
- Phone app: no longer says "isn't on Wi-Fi" when Android hides the hotspot from it; it asks you to open the app instead.

## 0.6.6 (truck app only)
- Screenshots now capture the whole truck screen: Android Auto plus the truck bar, and the settings or EQ page when one is open.

## 0.6.5 (phone app only)
- Fixed: a new truck couldn't get its hotspot details while AutoBridge Phone was in the background. The phone app now remembers the hotspot from the last time it was open.

## 0.6.4
- New **TEST** button in Settings → Truck hotspot: checks the hotspot name, BSSID and password against your phone and fixes them.
- The truck checks its hotspot details against the phone by itself each time it finds the phone.
- First setup: the truck waits for the hotspot details instead of dropping the phone.
- The phone app tells the truck what's missing (Location, password, not on Wi-Fi).
- Clearer messages when the phone can't join the hotspot or Bluetooth isn't connected.
- Typing the hotspot details by hand checks the name and the 8–63 character password.

## 0.6.3
- Screenshot of the truck screen from the phone app, saved on the phone.

## 0.6.2
- Drive logs record every truck value AutoBridge can read, and which car permissions it has.
- The log lists the truck data GM won't share (tire pressure, doors, odometer and more).

## 0.6.1
- Reconnects about 10 seconds sooner after a Wi-Fi drop.
- Smoother audio processing.

## 0.6.0
- 10-band music equalizer in settings, with presets.

## 0.5.3
- Fixed: the phone's Send log button stopped working after a Wi-Fi reconnect.

## 0.5.2
- Reconnects by itself after the phone's Wi-Fi drops.

## 0.5.1
- Fixed: frozen small screen after Exit and reopen.

## 0.5.0
- The truck's log is kept in a file that survives restarts.

## 0.4.9
- Fixed: small, unresponsive picture after reopening AutoBridge.

## 0.4.8
- Live EV battery panel on the settings page.

## 0.4.7
- GM-style settings page with Android Auto and Exit buttons.

## 0.4.6
- Black truck bar with gear, charge door and battery icons.
- Fixed: the volume knob controlled navigation volume.

## 0.4.5
- Full setup and usage guide in the README.
- Settings switch to hide the truck's status bar.
- Truck bar in a dashboard-strip layout, with the charge port's Closed state.

## 0.1.0 – 0.4.4
- Wireless Android Auto on the truck screen through the AutoBridge Phone relay app.
- Every Wi-Fi channel advertised, so any truck hotspot works.
- Truck data: battery, range, speed, gear and parking brake.
- Now playing on the dashboard, turn-by-turn on the cluster, steering wheel keys.
- Voice button goes to Android Auto; truck GPS; sharper H.265 picture with fallback.
- A new truck gets its hotspot details from the phone app.
- Send log from truck button in the phone app.
- Waiting screen styled as a Linux console.

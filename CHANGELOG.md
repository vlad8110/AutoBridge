# Changelog

What changed in each AutoBridge version since the project went public (November 23, 2026).
The truck app comes from the Play Store test; the phone app is on the [Releases](https://github.com/vlad8110/AutoBridge/releases) page.

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

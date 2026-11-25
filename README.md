# AutoBridge

Wireless Android Auto for cars that run **Android Automotive OS** (Google built-in), built and
daily-driven on a **2025 GMC Sierra EV**. Cars like this dropped Android Auto; AutoBridge brings it
back as an ordinary app, with no changes to the car and no root.

Two small apps work together:

| App | Runs on | Where to get it |
|---|---|---|
| **AutoBridge** (`app/`, package `dev.autobridge.car`) | the truck | Google Play (testers) or build it yourself |
| **AutoBridge Phone** (`phone/`, package `dev.autobridge.phone`) | your Android phone | build it yourself, or the APK from the maintainer |

The phone app is a tiny relay. GM's hotspot blocks the phone from connecting to the truck, so
Android Auto talks to the relay on its own phone and the truck connects out to the relay.

## What you get

- **Wireless Android Auto**, full screen above a truck data bar, connecting in about a second.
- **Sharp picture**: 1440p (H.265) on big screens, falling back to 1080p by itself if needed.
- **Truck integration**: turn-by-turn in GM's instrument cluster, now playing on the dashboard
  and Media card, steering-wheel media keys, the truck's GPS for Maps, EV plug types
  (J1772, CCS1, Tesla NACS) for charger search, and the voice button for Google Assistant.
- **Truck bar** under Android Auto, black like the truck's own screens: a gear icon (red brackets
  when the parking brake is on), range, energy, speed, a regen/power meter, the charge door
  (closed, open, plugged in, charging) and the battery ring. While charging it shows charging power
  with the time left to your charge limit, counting down. It uses the truck's own units (miles, °F).
- **Logs** sent to your phone with one tap, for troubleshooting.
- Starts with the truck, and keeps the truck's real driving state, so Android Auto's driving
  restrictions stay on.

## Requirements

- A car with Android Automotive OS and Google Play (tested: GMC Sierra EV, Android 14).
- An Android phone with Android Auto (tested: Galaxy Z Fold8).
- The car's **Wi-Fi hotspot turned on**. It doesn't need a data plan: the phone uses its own data.

## Set up (once per truck)

1. **Turn on the truck's hotspot** and join it from your phone with its password.
2. **Install AutoBridge Phone** on the phone and open it. Allow Location (Android only shows Wi-Fi
   details with it) and Notifications. It starts by itself after reboots from then on.
3. **Install AutoBridge** on the truck and open it. Allow Nearby devices, Microphone, Location, and
   the car data permissions (speed, energy, mileage, tires, steering).
4. **Give the truck its hotspot details**, either way:
   - *Automatic:* in AutoBridge Phone, tap **Password** in the Truck hotspot card and type the
     hotspot password. The truck picks up the name, BSSID and password within about 10 seconds.
   - *By hand:* on the truck, open **Settings** and tap **Edit** under Truck hotspot. AutoBridge
     Phone shows the name, BSSID, channel and frequency to copy.
5. **Pair the phone with the truck over Bluetooth.** In the truck's Bluetooth settings for the
   phone, keep **Calls on** and turn **Media audio off**. Otherwise the truck's own Bluetooth player
   grabs the speakers and Android Auto's music goes silent.
6. That's it. With AutoBridge open, the phone's Android Auto connects by itself.

## Everyday use

- **Settings:** tap the **charge door icon** on the truck bar. It's deliberately unmarked, so only
  you know it's a button. With the bar turned off, use the **Settings** button on AutoBridge's
  waiting screen (shown when Android Auto isn't connected). **Android Auto** (or the back gesture)
  returns to Android Auto; **Exit** stops AutoBridge and closes it until you open it again.
  - *Truck bar*, *Truck status bar* and *Sharper picture* tiles. Android Auto only measures the
    screen when it connects, so when you go back after changing one, AutoBridge closes and
    reopens by itself and the phone reconnects at the new size.
  - An EV battery panel: charge level with your charge limit, energy, range, and while charging
    the power, time to the limit, the time it'll be ready, range added per hour, energy still to
    add and the current limit, all live.
  - Hotspot details (Edit, Forget), the voice button setting, Send log, version.
- **Voice button:** a long press usually reaches the phone's assistant over Bluetooth. For a short
  press, choose *AutoBridge* as the truck's digital assistant (Settings, Voice button). That replaces
  the truck's own Google Assistant while it's selected.
- **Logs:** in AutoBridge Phone, tap **Send log from truck** while Android Auto is running, or use
  Send log in Settings. The truck keeps its log in a file (up to about 4 MB, oldest trimmed), so
  a log sent after a restart or force close still shows what happened before it. Logs are saved to the folder you pick in AutoBridge Phone. A log is also
  sent automatically when Android Auto disconnects with an error.

## Troubleshooting

| What you see | What to do |
|---|---|
| Phone's Wi-Fi dropped | Leave Bluetooth on: once the Wi-Fi is back, AutoBridge asks the phone to rejoin within about 10 seconds. |
| Stuck on "Looking for the AutoBridge Phone app" | Open AutoBridge Phone once; check the phone is on the truck's hotspot. |
| Music plays but no sound | Truck Bluetooth settings for the phone: Media audio **off**. |
| Picture stretched after updating | Disconnect and reconnect once. |
| Black picture with the sharper picture on | It falls back to 1080p by itself on the next connection; or switch Sharper picture off. |
| Anything else | Send a log and open an issue with it. Logs contain your hotspot name and BSSID, never the password. |

## How it works

```
Phone (Android Auto)                           Truck (AutoBridge)
  1. Bluetooth RFCOMM  ◄────────────────────►  "join Wi-Fi <ssid>/<password>/<bssid>; Android Auto at 127.0.0.1:5280"
  2. joins the truck's hotspot
  3. Android Auto → AutoBridge Phone (127.0.0.1:5280)
                         ◄── truck finds the relay on the hotspot and connects out (:5300)
  4. Android Auto session (aasdk: TLS, H.265/H.264 video, audio, touch, mic, sensors,
     now playing, turn-by-turn)
```

| Port on the phone | Used for |
|---|---|
| 5280 (loopback) | Android Auto → relay |
| 5300 | truck → relay (the Android Auto session) |
| 5301 / 5302 | log files / "send log" requests |
| 5303 | a new truck asking for the hotspot details |

| Code | What it does |
|---|---|
| `app/.../Wireless.kt` | Bluetooth handshake and the Wi-Fi details message |
| `app/.../BridgeService.kt` | Service: Bluetooth, finding the phone, session, truck data, logs |
| `app/src/main/cpp/bridge.cpp` | The Android Auto session on [aasdk](https://github.com/opencardev/aasdk) |
| `app/.../Media.kt`, `Display.kt` | Video decoding and sizing, audio, microphone |
| `app/.../TruckBar.kt`, `SettingsPanel.kt` | The truck bar (and its icons) and the settings page |
| `app/.../NowPlaying.kt`, `ClusterNav*.kt`, `VoiceAssistant.kt` | Media source, cluster turn-by-turn, voice button |
| `phone/` | The relay, log saving, and the Wi-Fi details screen |

## Build it yourself

Needs JDK 17, the Android SDK with NDK 28.2 and CMake 3.22.1. The first build compiles OpenSSL,
protobuf and Boost for Android (about 10 minutes).

```bash
git clone --recurse-submodules https://github.com/vlad8110/AutoBridge.git
cd AutoBridge
native/build-deps.sh
./gradlew :app:assembleDebug :phone:assembleDebug
```

- Car app: `app/build/outputs/apk/debug/app-debug.apk`. Most cars only install apps from Google
  Play, so you'll usually publish your build to your own Play Console internal-testing track.
- Phone app: `phone/build/outputs/apk/debug/phone-debug.apk`; install it on the phone directly.

Signed release builds read the key from environment variables, so no secret lives in the repo:

```bash
AB_KEYSTORE=/path/upload.jks AB_KEYSTORE_PASSWORD=... \
  ./gradlew :app:bundleRelease :phone:assembleRelease -PversionCode=38 -PversionName=0.5.3
```

Tests: `./gradlew :app:testDebugUnitTest :phone:testDebugUnitTest`.

## Safety and limits

- Android Auto's own driving restrictions stay on: AutoBridge reports the truck's real driving
  state. Don't change that.
- GM can change what third-party apps may do in a software update; features may stop working.
- Not included: wired (USB) Android Auto, CarPlay, and the map on the driver display.

## License

GPL-3.0, because it uses [aasdk](https://github.com/opencardev/aasdk) (GPL-3.0). Android Auto is a
trademark of Google LLC, and GMC and Sierra are trademarks of General Motors. This project is not
affiliated with or endorsed by Google or General Motors. Use it at your own risk.

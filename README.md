# AutoBridge

Wireless Android Auto on cars that run Android Automotive OS (built and tested for a GMC Sierra EV).
AutoBridge is a small app you install on the car. Your phone's own Android Auto connects to it over
Bluetooth and the car's Wi-Fi hotspot, and the Android Auto screen shows full screen on the car display.

It does one thing: Android Auto, wirelessly. No CarPlay, no USB.

It needs a tiny relay app on the phone (`phone/`), because GM's hotspot blocks connections from
the phone to the car. Android Auto connects to the relay on its own phone, the car connects out to
the relay, and the relay joins the two.

## How it works

```
Phone (Android Auto)                         Car (AutoBridge)
  │  1. Bluetooth RFCOMM  ───────────────►  "I'm at 10.x.x.x:5288, join Wi-Fi <ssid>/<password>/<bssid>"
  │  2. joins the car's Wi-Fi hotspot
  │  3. Android Auto → relay on the phone (127.0.0.1:5280)
  │     car finds the relay on the hotspot and connects to it (:5300); relay joins the two
  │                                          aasdk: version, TLS handshake, service discovery
  │  4. H.264 video, PCM audio  ─────────►  MediaCodec → screen, AudioTrack → car speakers
  │  ◄─────────── touch, microphone, driving status, night mode
```

| File | What it does |
|------|--------------|
| `Wireless.kt` | The Bluetooth handshake: tells the phone our address and the Wi-Fi credentials |
| `BridgeService.kt` | Foreground service: Bluetooth listener, TCP server, session, car driving state |
| `bridge.cpp` | The Android Auto session on top of [aasdk](https://github.com/opencardev/aasdk) |
| `Media.kt` | Video decoder, audio output with car audio focus, microphone |
| `Display.kt` | Picks the stream size and margins so the picture fits the car screen exactly |
| `MainActivity.kt` | Full-screen projection, touch forwarding, Wi-Fi settings |

Driving restrictions stay on: AutoBridge reports the car's real driving state to the phone
(from Android Automotive's UX restrictions), so Android Auto locks the keyboard while moving.

## Build

Needs the Android SDK with NDK 28.2 and CMake 3.22.1, JDK 17, and on the first build about 10 minutes for the native libraries.

```bash
git clone --recurse-submodules https://github.com/vlad8110/AutoBridge.git
cd AutoBridge
native/build-deps.sh                 # OpenSSL, protobuf, abseil, Boost headers for Android arm64
./gradlew :app:assembleDebug         # car app: app/build/outputs/apk/debug/app-debug.apk
./gradlew :phone:assembleDebug       # phone relay: phone/build/outputs/apk/debug/phone-debug.apk
```

Release bundle for Google Play, signed with your upload key:

```bash
AB_KEYSTORE=/path/upload.jks AB_KEYSTORE_PASSWORD=... \
  ./gradlew :app:bundleRelease -PversionCode=2 -PversionName=0.2.0
```

## Set up in the car

1. Turn on the car's Wi-Fi hotspot and note its name, password and BSSID (the access point's MAC address).
2. Open AutoBridge, allow Nearby devices and Microphone, tap **Wi-Fi settings** and enter those three values.
3. Install AutoBridge Phone on the phone and open it once (it then starts by itself after reboots).
4. Pair your phone with the car over Bluetooth (once). With AutoBridge open, the phone's Android Auto connects by itself.

## Not included (on purpose)

USB/wired, CarPlay, instrument-cluster display, phone call audio over Android Auto (calls use the car's
normal Bluetooth hands-free), and starting at car boot (open AutoBridge once; it stays running while the car sleeps).

## License

GPL-3.0, because it links aasdk (GPL-3.0). Android Auto is a trademark of Google LLC; this project is not affiliated with Google.

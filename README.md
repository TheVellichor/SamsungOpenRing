# SamsungOpenRing

Open-source SDK and companion app for the Samsung Galaxy Ring. Enables custom gesture-triggered actions without Samsung's official SDK.

## What it does

SamsungOpenRing connects to your Galaxy Ring over BLE as a second GATT client (alongside Samsung's official app) and lets you:

- **Detect double-pinch gestures** independently of Samsung's camera/alarm integration
- **Fire webhooks** on each gesture (control smart home, open gates, trigger automations)
- **Configure triggers** that automatically enable/disable gesture detection based on context

## How it works

The Samsung Galaxy Ring's BLE protocol was reverse-engineered from the companion app and live traffic captures. SamsungOpenRing sends the same `ENABLE_GESTURE` command that Samsung's camera app sends, allowing the ring to report pinch gestures directly to our app.

**No root required.** No modifications to Samsung's app. No ADB setup for basic functionality.

See [RFC-SGR-001](../RFC-SGR-001-Samsung-Galaxy-Ring-Protocol.md) for the full protocol specification.

## Features

### Core Library

```kotlin
// 3 lines to detect gestures
OpenRing.connect(context) { event ->
    Log.d("MyApp", "Pinch detected: ${event.gestureId}")
}
OpenRing.enableGestures { event ->
    // Handle gesture
}
```

### Companion App

- **Manual control** -- start/stop gesture monitoring with a button
- **Webhook integration** -- HTTP POST to any URL on each gesture
- **7 trigger types:**
  - Bluetooth device connection (e.g., car stereo)
  - Android Auto
  - WiFi network (by SSID)
  - Time schedule (start/end time + days of week)
  - Location geofence (lat/lng/radius)
  - Phone charging
  - App in foreground
- **Event log** with full BLE protocol trace, exportable for debugging
- **Auto-reconnect** with exponential backoff
- **Survives reboots** -- triggers re-arm automatically

## Installation

### From APK

1. Download the latest APK from [Releases](../../releases)
2. Install on your Samsung Galaxy phone (Android 12+)
3. Open the app, grant Bluetooth permission
4. Configure your webhook URL and triggers

### From Source

```bash
git clone https://github.com/TheVellichor/SamsungOpenRing.git
cd SamsungOpenRing
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires:
- Android SDK (compileSdk 34)
- JDK 17
- Galaxy Ring paired with your phone via Samsung's official app

## Requirements

- Samsung Galaxy phone with Android 12+ (API 31+)
- Samsung Galaxy Ring (SM-Q500/Q508/Q509) paired via Galaxy Wearable app
- Samsung's official Ring Manager app must remain installed (handles BLE bonding)

## Battery Impact

SamsungOpenRing does **not** keep gestures enabled 24/7. The trigger system ensures gesture detection is only active when a trigger condition is met (e.g., connected to car Bluetooth). When no trigger is active, the ring operates normally with no additional battery drain.

BLE connection as a second client adds negligible phone battery usage (~1-2% per day when active).

## Architecture

```
SamsungOpenRing/
  core/     -- BLE connection, protocol, gesture detection (library)
  app/      -- Companion app with triggers, webhook, UI
```

### Core Library (`:core`)

- `OpenRing` -- public API entry point
- `RingConnection` -- BLE GATT client with auto-reconnect
- `RingProtocol` -- Samsung SAP message encoding/decoding
- `RingScanner` -- finds bonded Galaxy Ring from paired devices

### Companion App (`:app`)

- `GestureService` -- foreground service for persistent monitoring
- `WebhookSender` -- fires HTTP POST on gesture events
- `EventLog` -- persistent, rotatable event log
- `triggers/` -- pluggable trigger system

## Protocol

The Galaxy Ring uses a proprietary BLE protocol built on Samsung's Accessory Protocol (SAP). Key details:

- **GATT Data Service:** `00001b1b-0000-1000-8000-00805f9b34fb`
- **Gesture Channel:** 22 (0x16)
- **Enable gestures:** Write `0x16 0x16 0x00` to TX characteristic
- **Disable gestures:** Write `0x16 0x16 0x01` to TX characteristic
- **Gesture event:** Notification `0x16 0x16 0x02 [counter] 0x00 0x00 0x00`

Full protocol specification: [RFC-SGR-001](../RFC-SGR-001-Samsung-Galaxy-Ring-Protocol.md)

## Disclaimer

This project is not affiliated with, endorsed by, or sponsored by Samsung Electronics. "Samsung" and "Galaxy Ring" are trademarks of Samsung Electronics Co., Ltd.

This software interacts with the Galaxy Ring using the same BLE commands that Samsung's official companion app uses. However, **use at your own risk**. The authors take no responsibility for any impact on your ring's functionality, battery life, or warranty.

## License

MIT -- see [LICENSE](LICENSE)

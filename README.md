# Battery Share

Android P2P Bluetooth app — two phones show each other's live battery % with one tap. No "host" or "connect" concept; both phones dial each other simultaneously and the first socket to connect wins.

## How It Works

1. Pair the two phones via Android Bluetooth settings (once).
2. Open the app on both phones.
3. Each phone taps the other's name from the paired devices list.
4. Internally both phones simultaneously start a `BluetoothServerSocket` (listening) AND attempt `BluetoothSocket.connect()` to the other.
5. Whichever wins the race becomes the server; the other becomes the client — users never see this distinction.
6. The established RFCOMM socket is fully bidirectional: both sides write their battery % every 2 seconds and read the other's.

## Screenshots

| State | Description |
|---|---|
| Launch | Paired device list shown |
| Connecting | Status: "Connecting…" |
| Connected | Big remote battery % + small "You: X%" |
| Disconnected | "Disconnected – Tap to reconnect" button |

## Architecture

| Concern | Solution |
|---|---|
| Transport | Bluetooth RFCOMM (`BluetoothServerSocket` / `BluetoothSocket`) |
| Role negotiation | Race: server thread + client thread started together; first to connect wins via `AtomicBoolean` |
| Protocol | Plain text line `"75\n"` every 2 s |
| Battery | `BatteryManager.BATTERY_PROPERTY_CAPACITY` |
| Threading | `ExecutorService`: server race, client race, reader, writer |
| UI updates | `runOnUiThread(...)` |

**UUID**: `00001101-0000-1000-8000-00805F9B34FB` (standard serial port UUID, used by both sides).

## Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"
    android:minSdkVersion="31"/>
```

`BLUETOOTH_CONNECT` is requested at runtime on Android 12+.

## Build

```bash
cd BatteryShare
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Verify

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

1. Pair the two phones in Bluetooth Settings.
2. Open the app on both.
3. Each taps the other's name.
4. Both screens show live battery %.
5. Turn Wi-Fi off — still works (Bluetooth only).
6. Close one app — the other shows "Disconnected – Tap to reconnect".
7. Tap reconnect — reconnects cleanly.

## File Layout

```
BatteryShare/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/batteryshare/
│       │   └── MainActivity.java
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/
│               ├── strings.xml
│               └── themes.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradlew
```

## Minimum SDK

API 21 (Android 5.0). Target SDK 34.

## License

No license specified.

# Claude Bridge — a debug control surface for the Galaxy Ring

A **debug-build-only** instrument that turns `SamsungOpenRing` into something an
operator (or an agent) can drive over ADB: connect to the ring, watch its live BLE
stream, read/poll state, and send raw commands.

## Why it exists

The ring connects over BLE to the phone. The bridge runs **inside the app on the
phone**, holds the GATT connection, and exposes a tiny HTTP API on **loopback only**.
You tunnel that to a computer with `adb forward` and drive it with `curl`. This gives
request/response control plus a live notification stream, far cleaner than scraping
logcat.

## Safety model

The user explicitly chose **full raw access** — there is no command deny-list, and
destructive ops (reboot / factory-reset / on-ring shell / FOTA) are reachable;
destructive sends are logged loudly but not blocked. The only two boundaries (neither
limits the operator):

- **Loopback bind** — the server binds `127.0.0.1` only, reachable solely from the
  USB-tethered computer via `adb forward`. Never network-exposed.
- **Debug build only** — the bridge lives in the `app/src/debug/` source set
  (`ClaudeBridgeService`, `ClaudeBridgeApp`, debug `AndroidManifest.xml`). It is
  **absent from release builds** (verified: 0 occurrences in `app-release`).

Robustness hardening applied after review: `Content-Length` capped (anti-OOM), socket
read timeout (anti-stall), `Throwable`-catching accept loop, atomic event buffer.

## Architecture

- `ClaudeBridgeService` (foreground, `connectedDevice` type) runs a hand-rolled HTTP/1.0
  server on `127.0.0.1:8787`, routing to the existing `OpenRing` / `RingConnection`
  core. Maintains a ring buffer of all RX notifications and decodes CH11 battery.
- `ClaudeBridgeApp` (debug `Application`) auto-starts the service when any activity
  resumes (avoids Android's background-FGS-start restriction).
- Core hooks added for the bridge: `OpenRing.writeRaw`, `rawListener` (taps every
  notification), `requestConnectionPriority`, `gattDump`, `readCharacteristic` /
  `getRead`.

## Starting it (phone tethered, USB debugging on, debug APK installed)

```bash
# grant the BLE runtime permission first (connectedDevice FGS requires it)
adb shell pm grant io.github.thevellichor.samsungopenring.app android.permission.BLUETOOTH_CONNECT
# start + forward + status (or just open the app, which auto-starts the bridge)
adb shell am start-foreground-service -n io.github.thevellichor.samsungopenring.app/.ClaudeBridgeService
adb forward tcp:8787 tcp:8787
curl -s 127.0.0.1:8787/status
```

## HTTP API

| Method + path | Purpose |
|---|---|
| `GET /status` | connected?, battery, buffered-notif count |
| `GET /gatt` | full discovered service/characteristic dump |
| `GET /read?uuid=2a29` | safe GATT read by UUID prefix (use full `00002a29` for 16-bit UUIDs) |
| `GET /battery` | latest level + computed drain %/hr + sample history |
| `GET /events?since=N` | RX notification stream (hex + channel decode) from index N |
| `GET /log` | the app's EventLog (full protocol trace) |
| `POST /connect`, `/disconnect` | BLE connection |
| `POST /gestures/enable`, `/gestures/disable` | gesture detection (`16 16 00` / `16 16 01`) |
| `POST /write` `{"hex":"…"}` | **raw passthrough** to the TX characteristic (unguarded) |
| `POST /connpriority` `{"priority":"low\|balanced\|high"}` | BLE connection priority |
| `POST /battery/sync` | ask the ring to push CH11 battery (`0b 0b 03`) |
| `POST /stop` | stop the bridge service |

## Helper tools

- `SamsungOpenRing/tools/ring` — a bash wrapper: `ring up | status | connect | on | off
  | battery | events | gatt | read <uuid> | write "16 16 00" | connpriority low | stop`.
- `SamsungOpenRing/tools/ring_shell.py` — a **stateful Python REPL**. `python3 -i
  ring_shell.py` creates a live `ring` object with a background poller that
  continuously ingests the BLE stream and maintains a model (battery, gestures,
  wearing, last HR, pinch count, decoded event log, device info). Drive it with
  `ring.summary()`, `ring.tail(n)`, `ring.enable()`, `ring.read('00002a29')`,
  `ring.write('16 16 00')`, `ring.battery_sync()`, etc.

## What it produced

Everything in RFC Appendix B was gathered through this bridge: the full GATT map, the
TX/RX role correction, the live gesture path, the battery wire format, the standard
Heart Rate Service, wearing-state validation, the 600 s heartbeat cadence, the
low-power-priority acceptance, and the characterisation of the `eedd5e73` rolling
nonce that led to the full auth crack.

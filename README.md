# WifiP2P 2.0

Fast file transfer between two Android phones over **Wi-Fi Direct** - no internet, no router, no mobile data.
Any file type, any number of files, any size. English and Hindi UI, light and dark theme.

Original idea: [Android file transfer without network](https://juejin.cn/post/6844903565186596872)

## What changed compared to 1.0

| Problem in 1.0 | Fix in 2.0 |
|---|---|
| Only images could be sent, one file at a time | Any file type, many files, and the Android **Share** sheet ("Send with WifiP2P") |
| Only the receiver could be the group owner; the sender had to search and invite | **QR code**: the host shows a code, the other phone scans it and joins at once - no search, no invitation dialog |
| Sending and receiving were two separate modes | **Both phones send and receive** at the same time (two independent connections per link) |
| File copied to the cache with a 1 KB buffer, then sent | Streamed straight from the source: no temp copy, 512 KB chunks |
| Disk and network worked strictly one after the other | **Pipelined I/O**: while the network sends chunk N the disk already reads N+1..N+6 (and the reverse when receiving) |
| A log line for every chunk | Progress is throttled to 10 updates per second; live speed and ETA per direction |
| Wi-Fi Direct group on the default band (usually 2.4 GHz) | Host requests a **5 GHz group** on Android 10+, with a compatibility switch for old phones |
| Phone could throttle the radio / CPU after the screen turned off | Foreground service + CPU wake lock + Wi-Fi lock, notification with a Stop button |
| Receiver had to press buttons for every file, 15 s accept timeout | Fully automatic; each channel heals itself after cancel, error or reconnect |
| `ObjectInputStream.readObject()` on network data, no size, no integrity check | Hand-written binary protocol: bounded lengths, file size announced up-front, **CRC-32 per file**, storage check before the batch starts |
| Received files hidden in the app cache | Saved to **Downloads/WifiP2P** (MediaStore on Android 10+), tap to open; partial files are always deleted |
| Path traversal possible through the received file name | File names are sanitised, duplicates get "(1)", "(2)"... |
| `serverSocket.reuseAddress` set after `bind()` (no effect) | Set before bind, plus bind retry |

## Connection reliability, resume and history

* **Heartbeats**: an idle or busy phone sends a small heartbeat every 2 s. Silence for 10 s means the other phone is gone,
  and the connection is closed at once instead of waiting minutes for the network stack.
* **Automatic reconnect**: both channels reconnect with exponential backoff and jitter (0.4 s up to 4 s). After a QR join
  the app joins the same network again by itself; after a search it looks for the same device again.
* **Resume**: a file that stops early (a call, a Wi-Fi drop, a full disk) keeps its stored bytes on the receiving phone.
  When the phones meet again the sender continues from that byte. Each file is checked end to end with CRC-32 (the stored
  part is verified by CRC combination, so nothing is read twice).
* **Automatic continuation**: a file that was interrupted by a broken link continues by itself once the link is back.
  Files that failed for another reason (storage full, refused) wait for a manual *Resume* in the History screen.
* **Clean**: unfinished files can be removed on either phone with *Clean*.
* **Expiry**: unfinished transfers that were not touched for five days are removed when the app is opened.
  Nothing is cleaned up in the background.
* **History**: every finished file, sent or received, with state, time and size (last 500 entries).

Storage: unfinished receiving data lives in the app's private folder (`Android/data/.../files/incoming`) until it is
complete. The finished file is then copied to Downloads/WifiP2P.

Protocol v2 (`FILE_START` carries the resume offset and the stored part's CRC, `HEARTBEAT` frames) is not compatible with
v1: both phones must run the same version of the app.

## Build

* **Android Studio**: open this folder, *Build > Build Bundle(s) / APK(s) > Build APK(s)*.
* **Command line**: `./gradlew assembleRelease` (signed with `key.jks`, output in `app/build/outputs/apk/release/`)
  or `./gradlew assembleDebug`.
* **Without Android Studio**: push the folder to GitHub, run the *Build APK* workflow (`.github/workflows/build-apk.yml`)
  and download the APK from the run page.

Toolchain is unchanged from 1.0 (AGP 9.0.0, Gradle 9.3.1, Kotlin 2.3.10, minSdk 23, compile/targetSdk 36).
`applicationId` and the signing key are unchanged, so 2.0 installs over 1.0 as an update.
`key.jks` is the sample key of the original project - use your own keystore for a real release.

## How to use

1. **Phone A**: Home > *Show QR code*. The phone creates a fast Wi-Fi Direct network and shows the code.
2. **Phone B**: Home > *Scan QR code*, point the camera at phone A's code. Android asks once to join the network - confirm.
   The phones are connected. (Fallback without a camera: *Search phones* and tap the other phone, accept the invitation.)
3. On **either** phone: *Send files* (any number, any type). Or share files from Gallery / Files with
   *Send with WifiP2P* - they wait on the home screen and are sent as soon as the phones are connected.
4. Both phones see both directions live. Received files are in **Downloads/WifiP2P** and can be tapped to open.

Tips for maximum speed: stay within a few metres, keep both screens on, and use phones with Android 10+ on both sides
(5 GHz). If an old phone cannot connect to a 5 GHz group, switch the receiver to *compatibility mode* on its screen.
Real-world speed is set by the Wi-Fi Direct radio link, not by the app. In loopback tests on a PC the engine reached
about 1 GB/s (memory to memory) and several hundred MB/s (disk to disk) including CRC verification - far above what
Wi-Fi Direct delivers - so on a phone the radio, not the transfer engine, is the bottleneck. Actual phone speeds were
not measured here.

## Structure

```
transfer/   pure Kotlin engine (no Android classes): protocol, sender, receiver, pipelined copy, link runner
p2p/        Wi-Fi Direct controller (5 GHz group, discovery, connect, cleanup)
hub/        process-wide session state (StateFlow) - survives rotation, owns P2P + link
service/    foreground service: notification, wake lock, Wi-Fi lock
storage/    content-Uri file sources, Downloads sink (MediaStore / legacy), file opener
ui/         home, one session screen for both roles, QR scanner (CameraX), adapters
qr/         QR encoder/decoder on top of ZXing (pure JVM, unit-tested)
util/       permissions, system checks, settings
```

### Link and wire protocol (v2)

A link consists of **two TCP connections**: one carries the files this phone sends, the other the files it receives.
The guest opens both and states the direction of each in its HELLO; the host answers with the complementary role.

Control messages are `[type:1][length:4][payload]` frames (big-endian, payload <= 64 KB).
File data is **not** framed: after a `FILE` frame the sender writes exactly `size` raw bytes, then `FILE_END` (CRC-32).

```
HELLO (guest role, name) -> HELLO (host role, name)
sending channel:  BATCH(count,total) -> BATCH_REPLY -> { FILE, raw bytes, FILE_END -> FILE_ACK }* -> BATCH_END
```

The receiver enforces the announced batch (count and total size), so a peer cannot bypass the storage check.
A second phone cannot take over a link while the first one is connected (connections from another address are refused).

### QR code payload

```
wifip2p://c?v=1&s=<Wi-Fi Direct network name>&p=<passphrase>&a=<host IP>&o=<port>&n=<phone name>
```

Only private (local-network) addresses are accepted, so a scanned code can never steer the app to the internet.

## Tests

`app/src/test` contains about 85 JVM tests (`./gradlew test`) that run the real engine over TCP sockets on localhost:
bit-exact transfer in both directions and simultaneously, cancel in one direction while the other keeps running,
corruption detection (a flipped bit is caught and only that file fails), automatic reconnect, partial-file cleanup,
refused batches (no storage), unreadable files, stalled receiver watchdog, a second phone being refused,
hostile peers (protocol violations, path traversal), 300 small files. The QR payload is tested end to end with the real
ZXing library (rotation, low resolution, camera row padding) and with hostile input.

## Known limits

* Wi-Fi Direct behaviour differs between phone makers; the 5 GHz request falls back to the default group automatically.
* The progress notification needs the notification permission on Android 13+ (the transfer works without it).
* Android 15+ limits data-sync foreground services to about 6 hours per session.
* No resume of an interrupted file (it is deleted and can be sent again).

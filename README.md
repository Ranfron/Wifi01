# WifiP2P 2.0

Fast file transfer between two Android phones over **Wi-Fi Direct** - no internet, no router, no mobile data.
Any file type, any number of files, any size. English and Hindi UI, light and dark theme.

Original idea: [Android file transfer without network](https://juejin.cn/post/6844903565186596872)

## What changed compared to 1.0

| Problem in 1.0 | Fix in 2.0 |
|---|---|
| Only images could be sent (`image/*`), one file at a time | Any file type, multi-select, and the Android **Share** sheet ("Send with WifiP2P") |
| The file was first copied to the cache with a **1 KB** buffer, then sent | Streamed straight from the source: no temp copy, 512 KB chunks |
| Disk and network worked strictly one after the other | **Pipelined I/O**: while the network sends chunk N the disk already reads N+1..N+6 (and the reverse when receiving) |
| A log line was emitted for every chunk (slowed the transfer, grew the UI without bound) | Progress is throttled to 10 updates per second; live speed and ETA are shown |
| Wi-Fi Direct group on the default band (usually 2.4 GHz) | Receiver requests a **5 GHz group** on Android 10+ (often several times faster than 2.4 GHz), with a compatibility switch for old phones |
| Phone could throttle the radio / CPU after the screen turned off | Foreground service + CPU wake lock + Wi-Fi lock, progress notification with a Stop button |
| Receiver had to press "Create group" and "Start listening" for every file, 15 s accept timeout | Fully automatic; the link heals itself after cancel, error or reconnect |
| Only worked if the *receiver* became group owner | Either phone can be group owner - the TCP role and the transfer role are independent |
| `serverSocket.reuseAddress` was set after `bind()` (no effect) | Set before bind, plus bind retry |
| `ObjectInputStream.readObject()` on network data (unsafe), no size, no integrity check | Hand-written binary protocol: bounded lengths, file size announced up-front, **CRC-32 per file**, storage check before the batch starts |
| Received files were hidden in the app cache | Saved to **Downloads/WifiP2P** (MediaStore on Android 10+), tap to open; partial files are always deleted |
| Path traversal possible through the received file name | File names are sanitised, duplicates get "(1)", "(2)"... |
| Runtime request for normal permissions, no Wi-Fi/Location checks, fixed portrait | Correct permission flow (`NEARBY_WIFI_DEVICES` / Location / notifications), Wi-Fi & Location banners, rotation safe |

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

1. **Receiver**: Home > *Receive files*. Nothing else to press - the phone creates a fast Wi-Fi Direct group and shows its name.
2. **Sender**: Home > *Send files* > *Search devices* > tap the receiver. Accept the invitation on the receiver.
3. *Choose files to send* (any number, any type) - or share files from Gallery / Files with *Send with WifiP2P*
   (they are sent as soon as the connection is up).
4. Received files are in **Downloads/WifiP2P**.

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
ui/ util/   screens (View system + ViewBinding + Material 3), adapters, permissions
```

### Wire protocol (v1)

Control messages are `[type:1][length:4][payload]` frames (big-endian, payload <= 64 KB).
File data is **not** framed: after a `FILE` frame the sender writes exactly `size` raw bytes, then `FILE_END` (CRC-32).

```
HELLO (both)  ->  BATCH(count,total) -> BATCH_REPLY -> { FILE, raw bytes, FILE_END -> FILE_ACK }* -> BATCH_END
```

The receiver enforces the announced batch (count and total size), so a peer cannot bypass the storage check.

## Tests

`app/src/test` contains 37 JVM tests plus the template example (`./gradlew test`) that run the real engine over TCP sockets on localhost:
bit-exact transfer of many sizes in both group-owner topologies, corruption detection (a flipped bit is caught and only
that file fails), cancel + automatic reconnect, partial-file cleanup, refused batches (no storage), unreadable files,
role mismatch, stalled receiver watchdog, hostile peers (protocol violations, path traversal), 300 small files, etc.

## Known limits

* Wi-Fi Direct behaviour differs between phone makers; the 5 GHz request falls back to the default group automatically.
* The progress notification needs the notification permission on Android 13+ (the transfer works without it).
* Android 15+ limits data-sync foreground services to about 6 hours per session.
* No resume of an interrupted file (it is deleted and can be sent again).

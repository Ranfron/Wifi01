# Wi-Fi Direct connection diagnostics

The connection code writes diagnostic events with the Android Logcat tag `WifiP2P`.

To capture both devices:

1. Connect each phone to a computer with USB debugging enabled.
2. On each phone run `adb logcat -c` to clear old logs.
3. Open the receiver screen on one phone. On the other, search and tap the receiver.
4. Wait until the sender reports a connection timeout (or the connection succeeds).
5. Run this separately for each phone:

   ```sh
   adb logcat -d -s WifiP2P:V > WifiP2P-log.txt
   ```

The sender log should show `STOP_DISCOVERY`, `CONNECT requested`, and either `CONNECT action=SUCCESS` or `CONNECT action=FAILURE`.
The receiver log should show `CREATE_GROUP`, `GROUP_INFO`, `PEERS_CHANGED`, and `CONNECTION_CHANGED` events.

Keep both files from the same test attempt. They show whether Android accepted the sender request, whether the receiver group remained available, and whether the receiver observed a connection-state change.

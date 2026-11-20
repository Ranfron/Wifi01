# Advanced Wi-Fi P2P — Implementation Roadmap

Theory source: Complete System Theory document.

## Phase 1 — Foundation (DONE in this pass)

- [x] `DeviceCaps` measurement + compact serialization
- [x] `BandSelector` (6 → 5 → 2.4 → AUTO)
- [x] `ConnectCode` v2 (caps + nonce echo fields)
- [x] `P2pController` multi-band cascade (`BandChoice`)
- [x] 6 GHz support path (API 36+)
- [x] `Diagnostics` snapshot helpers
- [x] TransferHub integration (measure caps, select band, advertise in QR, capture peer caps)
- [x] Actual frequency reporting from `WifiP2pGroup`
- [x] `Transport` interface + factory skeleton (TCP today, SMUFF later)
- [x] ConnectCode unit test updated for v2

## Phase 2 — Handshake hardening (next)

- [ ] NonceManager with 120 s expiry + reject stale QR
- [ ] Guest → Host capability exchange over BLE advertisement (optional path)
- [ ] Optional 4-digit confirm code to prevent shoulder-surfing of QR-2
- [ ] ECDH (P-256) session key material in QR / first socket frames
- [ ] AES-256-GCM per-chunk encryption (Kotlin `javax.crypto`)

## Phase 3 — Transfer reliability & honesty (MOSTLY DONE)

- [x] SoftAP / LocalOnlyHotspot fallback when P2P fails (after MAX_GROUP_ATTEMPTS)
- [x] Adaptive Tuning.forPair (1 MB chunks + deeper queue on high-band / high-score pairs)
- [x] Existing ChunkPipe already is a solid 2-stage network↔disk pipeline
- [x] Live Diagnostics sampling every 2s (band, frequency, Tx/Rx link, RSSI, standard)
- [x] Diagnostics shown on Session status line when CONNECTED
- [x] Honest bottleneck report after send batch (wifi / storage / cpu)
- [x] Mode labels for 6 GHz and SoftAP
- [~] Resume: protocol + IncomingStore already support offset resume; periodic 5s flush is optional polish
- [ ] Optional third crypto pipeline (deferred with Phase 2 security)

## Phase 4 — Native SMUFF engine (major effort)

- [ ] JNI bridge + Netlink / SO_MEMINFO probes
- [ ] buffer_monitor, rate_controller, ack_manager, loss_recovery
- [ ] packet_aggregator (adaptive size, never default 64 KB)
- [ ] TransportFactory runtime switch TCP ↔ SMUFF
- [ ] Full device matrix testing

## Rules that must never be broken

1. Measure first — never hard-code a speed target.
2. Speed = min(A, B). Always negotiate.
3. Never force 6 GHz when the peer cannot join it.
4. TCP must remain a fully working fallback forever.
5. No homemade crypto.
6. Never claim 100 MB/s on phones that cannot do it.
7. Every failure path has a user-visible message + automatic fallback where safe.

## Expected speeds after Phase 1–3 (TCP path)

| Pair              | Band   | Realistic MB/s |
|-------------------|--------|----------------|
| Wi-Fi 6/7 + same  | 5/6 GHz| 60–90          |
| Wi-Fi 5 + Wi-Fi 6 | 5 GHz  | 40–60          |
| Wi-Fi 4 / 2.4 only| 2.4 GHz| 10–25          |

SMUFF (Phase 4) can add another 15–30 % on clean high-end links.

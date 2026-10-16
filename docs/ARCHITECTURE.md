# 🏛️ Architecture Documentation: School SMS & OTP Gateway

## 1. High-Level System Topology

```
[ Parents Mobile ]
       ▲
       │ Cellular Radio (SMS / GSM)
       │
[ Android Smartphone ] (SmsGatewayService)
       │
       │ TLS 1.3 / HTTPS (Encrypted Handshake & Heartbeat)
       ▼
[ Cloudflare Edge Network ]
       │
       │ Cloudflare Tunnel (protocol: http2)
       ▼
[ cloudflared Daemon ] (Server: root@100.89.137.102)
       │
       │ Reverse Proxy -> localhost:8080
       ▼
[ Modular Python REST Server ] (SchoolSMSGateway/server)
       │
       ├── auth.py          -> Token comparison (hmac.compare_digest)
       ├── otp_service.py   -> Crypto OTP generation & 60s rate limit
       ├── sms_queue.py     -> FIFO Atomic state machine
       └── database.py      -> SQLite WAL mode thread-safe engine
       ▲
       │
[ School Web / Mobile App ] (Sends OTP verification requests)
```

---

## 2. Security Architecture

1. **Cloudflare Tunnel Routing (`sms.success0.com.np`):**
   - Server does not need open firewall ports (zero open inbound ports).
   - Traffic is end-to-end TLS 1.3 encrypted.
   - Origin server connects outbound to Cloudflare edge.

2. **Mutual Secret Token Handshake:**
   - Both phone and server share `GATEWAY_SECRET_TOKEN`.
   - All comparisons use constant-time `hmac.compare_digest` to prevent timing attacks.
   - Devices must complete `POST /api/v1/gateway/handshake` before claiming jobs.

3. **Rate Limiting & Cost Protection:**
   - 60-second cooldown per phone number prevents SMS flooding and billing waste.
   - Single-use OTP invalidation (`is_verified = 1`) prevents replay attacks.

4. **Reliability & Background Persistence:**
   - Android foreground service with persistent notification.
   - `PARTIAL_WAKE_LOCK` prevents CPU sleep.
   - `WIFI_MODE_FULL_HIGH_PERF` prevents Wi-Fi throttling.
   - `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prevents OEM task killers.
   - `BOOT_COMPLETED` receiver auto-starts service on device reboot.

---

## 3. Queue State Machine

```
[ PENDING ] ──(Atomic Batch Claim)──> [ DISPATCHING ] ──(Cellular OK)──> [ SENT ]
                                              │
                                              └──(Radio Error)───> [ FAILED ]
```

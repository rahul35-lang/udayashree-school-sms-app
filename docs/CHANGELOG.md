# 📜 Changelog & Architecture Overhaul Log

## [2.1.0] - 2026-10-16 - Live Cellular Verification & Telephony Hardening

### Added
- **Persistent Disk Logger (`Logger.java`):** Real-time diagnostic logging stored at `/sdcard/Android/data/com.school.smsgateway/files/gateway.log` with automatic 2MB size rotation and logcat mirroring.
- **Autonomous Auto-Start Receivers:**
  - `MY_PACKAGE_REPLACED`: Automatically resumes 24/7 background foreground daemon immediately upon APK install or update without manual intervention.
  - `com.school.smsgateway.START_SERVICE`: Direct programmatic control via ADB broadcast.
  - `com.school.smsgateway.TEST_HANDSHAKE`: Instant CLI diagnostic handshake triggering.
- **Dynamic Pre-Shared Key Migration:** Seamlessly auto-migrates stale preferences from prototype key (`school_gateway_secret_2026`) to modern master key (`school_gateway_secret_2026_super_secure_token_np`).

### Fixed
- **Android 14+ Telephony Delivery Callback:** Registered `smsSentReceiver` with `Context.RECEIVER_EXPORTED` allowing `com.android.phone` system baseband radio process to deliver `RESULT_OK` callbacks back to the app.
- **SQLite Concurrency & Lock Contention:** Configured `autocommit` mode (`isolation_level = None`), `PRAGMA busy_timeout = 30000`, and `PRAGMA synchronous = NORMAL` in `server/database.py` to eliminate `OperationalError: database is locked`.
- **Legacy Schema Dual-Column Support:** Made `server/otp_service.py` dynamically compatible with both legacy `otp` and modern `otp_code` columns.

### Verified
- Live cellular SMS delivery from Vivo V2333 phone to cellular baseband via Cloudflare Tunnel `https://sms.success0.com.np`.
- Live OTP generation, SMS queue dispatch, and end-to-end verification with single-use replay protection.

---

## [2.0.0] - 2026-10-16 - Major Architecture Overhaul

### Added
- **Cloudflare Tunnel Routing:** Direct HTTPS routing via `https://sms.success0.com.np` through tunnel ID `b780baa3-b66e-489b-be22-36e8b9b740a4`.
- **Modular Server Architecture:**
  - `server/config.py`: Centralized configuration, environment variables, security tokens.
  - `server/database.py`: Thread-safe SQLite engine with WAL mode and idempotent schema setup.
  - `server/auth.py`: Constant-time token verification, cryptographic handshake protocol, timing-attack prevention.
  - `server/otp_service.py`: Cryptographic numeric OTP generator, 60s cooldown rate limiter, single-use replay protection.
  - `server/sms_queue.py`: Atomic FIFO queue state machine (`PENDING` -> `DISPATCHING` -> `SENT`/`FAILED`).
  - `server/server.py`: Multithreaded REST server with dual-port binding (8080 and 8000).
- **Comprehensive Automated Test Suite (`tests/`):**
  - `test_auth.py`: Token verification, constant-time compare, handshake registration (8 tests).
  - `test_database.py`: Schema idempotency, WAL mode, multithreaded concurrent writes (3 tests).
  - `test_otp.py`: Phone sanitization, entropy, 60s rate limit, replay prevention, expiry (8 tests).
  - `test_queue.py`: FIFO ordering, atomic status transitions, reporting, metric counts (5 tests).
  - `test_endpoints.py`: Dynamic-port integration testing of all HTTP routes, 401, 429, 400, 200 (6 tests).
  - `run_all_tests.py`: Master test runner executing all 30 tests with 100% pass rate.
- **Android Gateway App Overhaul:**
  - `BatteryOptimizationHelper.java`: Prompts for `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to guarantee 24/7 background survival.
  - `ApiClient.java`: Updated to communicate over Cloudflare Tunnel HTTPS (`https://sms.success0.com.np`).
  - Preset quick-select chips in UI for 1-tap configuration.
  - Adaptive low-bandwidth heartbeat keepalive pings.
- **Documentation Tree:**
  - `README.md`: Central repository index.
  - `docs/ARCHITECTURE.md`: System topology, security model, state machine.
  - `docs/API_SPECIFICATION.md`: REST endpoints, handshake formats, curl examples.
  - `docs/ANDROID_MODULE.md`: Foreground service, wake locks, battery optimization, SMS chunking.
  - `docs/DEPLOYMENT_GUIDE.md`: Cloudflare tunnel setup and systemd instructions.
  - `docs/CHANGELOG.md`: Detailed iteration record.

### Changed
- Replaced monolithic `server.py` with cleanly separated single-responsibility modules.
- Upgraded authentication from plain string equality to constant-time HMAC digest comparison.
- Added horror comments explaining every line, parameter, and edge case across all files.

---

## [1.0.0] - 2026-10-16 - Initial Prototype
- Initial prototype and hardware testing of USB modem dongle (ZTE MF190).
- Identified modern USIM incompatibility (`+CME ERROR: 13`).
- Built initial Android SMS gateway APK and prototype Python server.

# 📜 Changelog & Architecture Overhaul Log

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

# 📱 School SMS & OTP Gateway (v2.0)

A self-hosted, 24/7 modular cellular SMS and Parent Verification Gateway for school applications. Uses an Android smartphone with any active cellular SIM card to dispatch OTPs and SMS alerts autonomously over a secure Cloudflare Tunnel.

---

## 📚 Complete Documentation Index

For detailed documentation, please navigate to the [`docs/`](file:///home/rahul/SchoolSMSGateway/docs/) folder:

| Document | Link | Description |
| :--- | :--- | :--- |
| **System Architecture** | [`docs/ARCHITECTURE.md`](file:///home/rahul/SchoolSMSGateway/docs/ARCHITECTURE.md) | System design, Cloudflare Tunnel topology, state machine, and security. |
| **REST API Specification** | [`docs/API_SPECIFICATION.md`](file:///home/rahul/SchoolSMSGateway/docs/API_SPECIFICATION.md) | Exhaustive API endpoint reference, handshake protocol, and payload examples. |
| **Android Gateway Module** | [`docs/ANDROID_MODULE.md`](file:///home/rahul/SchoolSMSGateway/docs/ANDROID_MODULE.md) | Foreground service lifecycle, battery optimization bypass, wakelocks, SMS chunking. |
| **24/7 Deployment Guide** | [`docs/DEPLOYMENT_GUIDE.md`](file:///home/rahul/SchoolSMSGateway/docs/DEPLOYMENT_GUIDE.md) | Cloudflare tunnel routing on `root@100.89.137.102` and systemd 24/7 service. |
| **Changelog & History** | [`docs/CHANGELOG.md`](file:///home/rahul/SchoolSMSGateway/docs/CHANGELOG.md) | Commit history, modular overhaul records, and feature additions. |

---

## ⚡ Quick Start

### 1. Run Automated Unit Tests (100% Passing)
```bash
python3 /home/rahul/SchoolSMSGateway/tests/run_all_tests.py
```
*Executes all 30 unit tests across authentication, database, queue, OTP engine, and HTTP endpoints in under 1.5 seconds.*

### 2. Start the Server
```bash
/home/rahul/SchoolSMSGateway/server/run_server.sh
```
- **Local Dashboard:** `http://localhost:8080` (or `http://localhost:8000`)
- **Cloudflare Public Tunnel:** `https://sms.success0.com.np`

### 3. Install the Android Gateway App
The pre-compiled, signed APK is located at:
- [`/home/rahul/Desktop/SchoolSMSGateway.apk`](file:///home/rahul/Desktop/SchoolSMSGateway.apk)

Connect phone via USB and run:
```bash
/home/rahul/SchoolSMSGateway/android/install_phone.sh
```
Or transfer `SchoolSMSGateway.apk` to the phone and tap to install.

---

## 🔌 API Integration Example

### 1. Request OTP (Parent Mobile Number Verification)
```bash
curl -X POST https://sms.success0.com.np/api/v1/otp/send \
  -H "Content-Type: application/json" \
  -d '{"phone": "9841234567"}'
```

### 2. Verify OTP (Validate Code Submitted by Parent)
```bash
curl -X POST https://sms.success0.com.np/api/v1/otp/verify \
  -H "Content-Type: application/json" \
  -d '{"phone": "9841234567", "otp": "482910"}'
```

### 3. Send School Announcement SMS
```bash
curl -X POST https://sms.success0.com.np/api/v1/sms/send \
  -H "Content-Type: application/json" \
  -d '{"phone": "9841234567", "message": "Dear Parent, your child was marked present today."}'
```

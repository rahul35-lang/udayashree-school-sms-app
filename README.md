# 📱 School SMS & OTP Gateway

A self-hosted, 24/7 cellular SMS and Parent Verification Gateway for school apps and systems. Uses an Android smartphone with any active cellular SIM card to dispatch OTPs and SMS alerts autonomously.

---

## 🏗️ Architecture Overview

```
                      ┌──────────────────────┐
                      │    School App / UI   │
                      │  (Parent enters No.) │
                      └──────────┬───────────┘
                                 │
                   1. POST /api/otp/send
                                 ▼
                      ┌──────────────────────┐
                      │    24/7 Server       │
                      │  (server/server.py)  │
                      └──────────┬───────────┘
                                 │
           2. Android Phone polls /api/gateway/poll
                                 ▼
                      ┌──────────────────────┐
                      │  Android Gateway App │
                      │ (Foreground Service) │
                      └──────────┬───────────┘
                                 │
                     3. Cellular SMS Dispatch
                                 ▼
                      ┌──────────────────────┐
                      │    Parent's Phone    │
                      │   (Receives OTP SMS) │
                      └──────────────────────┘
```

---

## 🚀 Quick Start Guide

### 1. Launch the Server on your 24/7 Machine

The server is standalone with **zero external dependencies** (uses standard Python 3 + SQLite).

```bash
cd /home/rahul/SchoolSMSGateway/server
./run_server.sh
```

- **Web Dashboard**: Open `http://localhost:8000` in your browser.
- **Default Gateway Key**: `school_gateway_secret_2026`

---

### 2. Install the Android Gateway App on Phone

The compiled, signed APK is located at:
- `/home/rahul/Desktop/SchoolSMSGateway.apk`
- `/home/rahul/SchoolSMSGateway/android/SchoolSMSGateway.apk`

#### Option A: 1-Click ADB Auto-Installer (Recommended)
Connect your Android phone to this PC via USB cable (with USB Debugging enabled in Developer Options):

```bash
/home/rahul/SchoolSMSGateway/android/install_phone.sh
```

#### Option B: Manual Install
1. Copy `SchoolSMSGateway.apk` to your phone (via USB file transfer, Google Drive, WhatsApp, or Bluetooth).
2. Tap the APK on your phone and choose **Install**.
3. Allow the SMS and Notification permissions when prompted.

---

### 3. Connect the Phone to Server

1. Open **School SMS Gateway** on your Android phone.
2. Enter your Server's IP address (e.g. `http://192.168.1.100:8000` or your domain/VPS URL).
3. Enter the Gateway Secret Key: `school_gateway_secret_2026`.
4. Tap **"Test Connection"** — it will confirm the phone can reach the server.
5. Tap **"START SERVICE"**.
   - The status badge will switch to green **ACTIVE**.
   - A persistent foreground notification will keep the app running 24/7 even with the screen locked.

---

## 🔌 School App API Integration Guide

### 1. Send OTP to Parent
When the parent types their phone number in the school app:

**Endpoint:** `POST http://<server-ip>:8000/api/otp/send`

**Request Body (JSON):**
```json
{
  "phone": "9841234567",
  "length": 6,
  "expiry_seconds": 300
}
```

**Success Response (200):**
```json
{
  "success": true,
  "message_id": 14,
  "phone": "9841234567",
  "expires_in": 300
}
```

*Note: Rate limiting automatically protects against spam by requiring 60 seconds between requests for the same number.*

---

### 2. Verify OTP
When the parent submits the 6-digit OTP code received on their phone:

**Endpoint:** `POST http://<server-ip>:8000/api/otp/verify`

**Request Body (JSON):**
```json
{
  "phone": "9841234567",
  "otp": "724166"
}
```

**Success Response (200):**
```json
{
  "success": true,
  "message": "Phone number verified successfully!"
}
```

**Error Response (400):**
```json
{
  "success": false,
  "error": "Incorrect OTP code. Please try again."
}
```

---

### 3. Send General School Notification SMS
For sending attendance alerts, exam results, or fee reminders:

**Endpoint:** `POST http://<server-ip>:8000/api/sms/send`

**Request Body (JSON):**
```json
{
  "phone": "9841234567",
  "message": "Dear Parent, your child Rahul was marked present today at 09:15 AM."
}
```

---

## 🛠️ Running the Server as a 24/7 System Service (systemd)

To ensure the server restarts automatically if the computer reboots:

Create `/etc/systemd/system/school-sms.service`:
```ini
[Unit]
Description=School SMS & OTP Gateway Server
After=network.target

[Service]
Type=simple
User=rahul
WorkingDirectory=/home/rahul/SchoolSMSGateway/server
ExecStart=/usr/bin/python3 /home/rahul/SchoolSMSGateway/server/server.py
Restart=always
RestartSec=5
Environment=PORT=8000
Environment=GATEWAY_API_KEY=school_gateway_secret_2026

[Install]
WantedBy=multi-user.target
```

Enable and start it:
```bash
sudo systemctl daemon-reload
sudo systemctl enable school-sms
sudo systemctl start school-sms
```

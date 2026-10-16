# 📡 API Specification: School SMS & OTP Gateway

Base URL: `https://sms.success0.com.np` (or `http://localhost:8080`)

All gateway endpoints require the shared secret token via header:
`X-Gateway-Token: school_gateway_secret_2026_super_secure_token_np`
or query parameter:
`?token=school_gateway_secret_2026_super_secure_token_np`

---

## 1. Gateway Handshake
Establishes mutual session and registers Android device hardware.

- **URL:** `POST /api/v1/gateway/handshake`
- **Headers:** `Content-Type: application/json`
- **Request Body:**
```json
{
  "token": "school_gateway_secret_2026_super_secure_token_np",
  "device_id": "vivo_v30_01",
  "device_model": "V2333",
  "battery_level": 75,
  "is_charging": true,
  "app_version": "2.0.0"
}
```
- **Response (200 OK):**
```json
{
  "success": true,
  "message": "Handshake successful. Gateway session active.",
  "data": {
    "status": "HANDSHAKE_ESTABLISHED",
    "server_time": 1792143000,
    "poll_interval_seconds": 3,
    "max_batch_size": 5
  }
}
```

---

## 2. Gateway Polling (Fetch Pending Jobs)
Atomic FIFO queue acquisition by the mobile gateway.

- **URL:** `GET /api/v1/gateway/jobs`
- **Headers:** `X-Gateway-Token: <token>`
- **Query Params:** `?device_id=vivo_v30_01&battery=75`
- **Response (200 OK):**
```json
{
  "success": true,
  "jobs_count": 1,
  "jobs": [
    {
      "id": 105,
      "phone": "+9779841234567",
      "message": "Your School App verification code is: 482910. Valid for 5 minutes.",
      "sim_slot": 0
    }
  ]
}
```

---

## 3. Gateway Report (Delivery Acknowledgment)
Reports final cell tower delivery state.

- **URL:** `POST /api/v1/gateway/report`
- **Headers:** `X-Gateway-Token: <token>`
- **Request Body:**
```json
{
  "job_id": 105,
  "status": "SENT"
}
```
- **Response (200 OK):**
```json
{
  "success": true,
  "job_id": 105,
  "status": "SENT"
}
```

---

## 4. Send Verification OTP (School App)
Called by School App backend when a parent requests verification.

- **URL:** `POST /api/v1/otp/send`
- **Headers:** `Content-Type: application/json`
- **Request Body:**
```json
{
  "phone": "9841234567",
  "length": 6,
  "expiry_seconds": 300
}
```
- **Response (200 OK):**
```json
{
  "success": true,
  "message": "OTP generated and cellular SMS queued successfully.",
  "otp_record_id": 42,
  "sms_job_id": 105,
  "phone": "9841234567",
  "expires_in": 300,
  "expires_at": 1792143300
}
```
- **Response (429 Too Many Requests - Rate Limit):**
```json
{
  "success": false,
  "message": "Rate limit reached. Please wait 48s before requesting a new OTP.",
  "retry_after_seconds": 48
}
```

---

## 5. Verify OTP Code (School App)
Validates parent-submitted OTP code. Single-use guaranteed.

- **URL:** `POST /api/v1/otp/verify`
- **Headers:** `Content-Type: application/json`
- **Request Body:**
```json
{
  "phone": "9841234567",
  "otp": "482910"
}
```
- **Response (200 OK):**
```json
{
  "success": true,
  "message": "Phone number verified successfully!"
}
```
- **Response (400 Bad Request):**
```json
{
  "success": false,
  "message": "Incorrect verification code. Please check and try again."
}
```

---

## 6. Send Custom Notification SMS
For general attendance, exam, or emergency notices.

- **URL:** `POST /api/v1/sms/send`
- **Headers:** `Content-Type: application/json`
- **Request Body:**
```json
{
  "phone": "9841234567",
  "message": "Dear Parent, your child was marked present today."
}
```
- **Response (200 OK):**
```json
{
  "success": true,
  "job_id": 106,
  "phone": "9841234567",
  "status": "PENDING"
}
```

---

## 7. System & Gateway Health Metrics
- **URL:** `GET /api/v1/status`
- **Response (200 OK):**
```json
{
  "status": "ONLINE",
  "server_time": 1792143000,
  "gateway_online": true,
  "device_model": "V2333",
  "last_seen_seconds": 2,
  "battery": 75,
  "metrics": {
    "total_messages": 106,
    "pending": 0,
    "dispatching": 0,
    "sent": 105,
    "failed": 1
  }
}
```

# 🚀 School SMS & OTP Gateway: AI Agent Integration Guide

> **TARGET AUDIENCE:** AI Agents & Developers engineering the School Android Application, Mobile Clients, and Backend API Services.  
> **GATEWAY BASE URL:** `https://sms.success0.com.np`  
> **PROTOCOL:** HTTPS (Cloudflare Edge SSL -> Linux Host Gateway `100.89.137.102:8090` -> Cellular Baseband Android Gateway)  
> **MANDATORY MASTER TOKEN:** `Udaya@momphone`  
> **ACTIVE CELLULAR DISPATCHER:** SIM 2 (Namaste 4G LTE Baseband)

---

## 📑 Table of Contents
1. [Architecture Overview & Pipeline](#1-architecture-overview--pipeline)
2. [Security & Authentication Specification](#2-security--authentication-specification)
3. [Multi-Step Message Delivery Verification Lifecycle](#3-multi-step-message-delivery-verification-lifecycle)
4. [API Endpoints Reference](#4-api-endpoints-reference)
   - [A. Request OTP Code (`POST /api/v1/otp/send`)](#a-request-otp-code-post-apiv1otpsend)
   - [B. Check SMS / OTP Job Delivery Status (`GET /api/v1/sms/status`)](#b-check-sms--otp-job-delivery-status-get-apiv1smsstatus)
   - [C. Verify User Submitted OTP (`POST /api/v1/otp/verify`)](#c-verify-user-submitted-otp-post-apiv1otpverify)
   - [D. Send Custom Announcement SMS (`POST /api/v1/sms/send`)](#d-send-custom-announcement-sms-post-apiv1smssend)
   - [E. Gateway Infrastructure Health (`GET /api/v1/status`)](#e-gateway-infrastructure-health-get-apiv1status)
5. [Error Codes & Rejection Matrix](#5-error-codes--rejection-matrix)
6. [Ready-to-Use Client Implementation Snippets](#6-ready-to-use-client-implementation-snippets)
   - [Kotlin / Android (Retrofit & Coroutines)](#kotlin--android-retrofit--coroutines)
   - [Flutter / Dart (`http` Package)](#flutter--dart-http-package)
   - [Python / Backend (`requests`)](#python--backend-requests)
   - [cURL Command Line](#curl-command-line)
7. [Production Hardening & Best Practices](#7-production-hardening--best-practices)

---

## 1. Architecture Overview & Pipeline

The School SMS Gateway operates as a zero-trust, high-performance bridge between software applications and physical cellular telecom networks:

```
┌─────────────────────────────────┐
│     School Android App /        │
│      Backend Microservice       │
└────────────────┬────────────────┘
                 │ (1) HTTPS Request with token `Udaya@momphone`
                 ▼
┌─────────────────────────────────┐
│   Cloudflare Tunnel Ingress     │
│  (https://sms.success0.com.np)  │
└────────────────┬────────────────┘
                 │ (2) Reverse Proxy to Port 8090
                 ▼
┌─────────────────────────────────┐
│     Central Gateway Server      │
│ (SQLite WAL Queue + OTP Engine) │
└────────────────┬────────────────┘
                 ▲
                 │ (3) Continuous Long-Poll (every 3s)
                 │     with token `Udaya@momphone`
┌────────────────┴────────────────┐
│   Vivo Hardware Gateway Phone   │
│   (24/7 Foreground Service)     │
└────────────────┬────────────────┘
                 │ (4) Baseband Radio Dispatch via SIM 2
                 ▼
┌─────────────────────────────────┐
│      Telecom Carrier (LTE)      │
│      Parent / Student Phone     │
└─────────────────────────────────┘
```

---

## 2. Security & Authentication Specification

### Zero-Trust Policy
- **EVERY SINGLE REQUEST** to sending, checking, or verifying endpoints **MUST** present the master pre-shared token.
- Requests without a token or with an invalid token are immediately rejected with **`HTTP 401 Unauthorized`**.
- Constant-time string comparison (`hmac.compare_digest`) is enforced on the server to prevent timing attack vulnerabilities.

### Supported Authentication Headers & Parameters
Clients can authenticate using **any** of the following three standard methods:

1. **Custom HTTP Header (Recommended):**
   ```http
   X-Gateway-Token: Udaya@momphone
   ```

2. **Standard Authorization Bearer Header:**
   ```http
   Authorization: Bearer Udaya@momphone
   ```

3. **URL Query Parameter:**
   ```http
   https://sms.success0.com.np/api/v1/sms/status?job_id=42&token=Udaya@momphone
   ```

4. **JSON Body Attribute (POST requests):**
   ```json
   {
     "token": "Udaya@momphone",
     "phone": "9809248510"
   }
   ```

---

## 3. Multi-Step Message Delivery Verification Lifecycle

To ensure complete delivery reliability, the server tracks each message across **multiple verification checkpoints**:

```
[1. ENQUEUE]         Client submits OTP/SMS with token
      │              └── Server validates format, enforces 60s rate limit, creates SQLite row.
      ▼              └── Status: PENDING (returns `sms_job_id`)
[2. CLAIM]           Hardware gateway phone polls server (`GET /api/v1/gateway/jobs`)
      │              └── Server atomically marks record as DISPATCHING in single transaction.
      ▼              └── Prevents duplicate transmissions by concurrent workers.
[3. RADIO DISPATCH]  Phone resolves `SubscriptionManager` slot 1 (SIM 2).
      │              └── Invokes `SmsManager.sendTextMessage()` with `PendingIntent`.
      ▼
[4. OS TELEPHONY]    Android OS baseband (`com.android.phone`) fires `SMS_SENT_ACTION`.
      │              └── `SmsSentReceiver` catches OS Radio result code (`Activity.RESULT_OK`).
      ▼
[5. ACKNOWLEDGMENT]  Phone reports result back to server (`POST /api/v1/gateway/report`).
      │              └── Server updates status to `SENT` (or `FAILED` with error reason).
      ▼
[6. CLIENT VERIFY]   School App checks `GET /api/v1/sms/status?job_id=<id>` to confirm delivery.
```

### Verification States Explained:
| Status | Meaning | What the Client Should Do |
|---|---|---|
| `PENDING` | Message safely stored in server queue; awaiting phone pickup. | Wait 1–3 seconds and poll `/api/v1/sms/status`. |
| `DISPATCHING` | Phone has claimed the message and sent command to baseband modem. | Wait 1–2 seconds. |
| `SENT` | **Confirmed Delivery.** Android telephony radio confirmed transmission. | Show user "OTP Sent" or "Message Delivered". |
| `FAILED` | Baseband rejected dispatch (e.g. no carrier signal, SIM out of credits). | Alert user or trigger fallback retry. |

---

## 4. API Endpoints Reference

### A. Request OTP Code (`POST /api/v1/otp/send`)
Generates a cryptographically secure 6-digit numeric OTP, stores an expiring single-use hash, and queues an outbound SMS dispatch job via SIM 2.

- **URL:** `POST https://sms.success0.com.np/api/v1/otp/send`
- **Headers:**
  ```http
  Content-Type: application/json
  X-Gateway-Token: Udaya@momphone
  ```
- **Request Body Parameters:**
  | Field | Type | Required | Default | Description |
  |---|---|---|---|---|
  | `phone` | `string` | **Yes** | — | Recipient phone number (e.g. `"9809248510"`, `"9841234567"`, `"+9779841234567"`). Automatically sanitized by server. |
  | `token` | `string` | Optional | — | Master token if not passed in headers. |
  | `length` | `int` | Optional | `6` | Length of OTP code (4–8 digits). |
  | `expiry_seconds` | `int` | Optional | `300` | Expiration lifetime in seconds (5 minutes). |
  | `template` | `string` | Optional | — | Custom SMS body. Must contain `{otp}` placeholder (e.g. `"Your School login code is {otp}"`). |

- **Example Request:**
  ```json
  {
    "phone": "9809248510",
    "template": "Your School App login code is: {otp}. Valid for 5 minutes."
  }
  ```

- **Example Response (`HTTP 200 OK`):**
  ```json
  {
    "success": true,
    "message": "OTP generated and queued for cellular dispatch.",
    "phone": "9809248510",
    "otp_id": 14,
    "sms_job_id": 28,
    "expires_at": 1792152600,
    "expires_in_seconds": 300
  }
  ```

- **Rate Limit Response (`HTTP 429 Too Many Requests`):**
  *Occurs if another OTP was requested for the same phone number within 60 seconds.*
  ```json
  {
    "success": false,
    "message": "Rate limit exceeded. Please wait 48s before requesting another OTP.",
    "retry_after_seconds": 48
  }
  ```

---

### B. Check SMS / OTP Job Delivery Status (`GET /api/v1/sms/status`)
Enables the calling application to verify that the message has completed all steps and was transmitted by the phone's cellular baseband.

- **URL:** `GET https://sms.success0.com.np/api/v1/sms/status?job_id=28`
- **Headers:**
  ```http
  X-Gateway-Token: Udaya@momphone
  ```
- **Query Parameters:**
  - `job_id` (*required*): The `sms_job_id` returned when the OTP or SMS was enqueued.
  - `token` (*optional*): Can be passed here if headers cannot be set.

- **Example Response (`HTTP 200 OK`):**
  ```json
  {
    "success": true,
    "job": {
      "id": 28,
      "phone": "9809248510",
      "message": "Your School App login code is: 245510. Valid for 5 minutes.",
      "status": "SENT",
      "sim_slot": 1,
      "retry_count": 0,
      "error_reason": null,
      "created_at": 1792152300,
      "updated_at": 1792152303
    }
  }
  ```

---

### C. Verify User Submitted OTP (`POST /api/v1/otp/verify`)
Validates the code entered by the parent or student. Enforces single-use consumption and replay attack prevention.

- **URL:** `POST https://sms.success0.com.np/api/v1/otp/verify`
- **Headers:**
  ```http
  Content-Type: application/json
  X-Gateway-Token: Udaya@momphone
  ```
- **Request Body:**
  ```json
  {
    "phone": "9809248510",
    "otp": "245510"
  }
  ```

- **Example Response (`HTTP 200 OK` - Success):**
  ```json
  {
    "success": true,
    "message": "OTP verified successfully."
  }
  ```

- **Example Response (`HTTP 400 Bad Request` - Wrong Code or Expired):**
  ```json
  {
    "success": false,
    "message": "Invalid verification code."
  }
  ```

- **Example Response (`HTTP 400 Bad Request` - Already Used / Replay Attack):**
  ```json
  {
    "success": false,
    "message": "This verification code has already been used or expired."
  }
  ```

---

### D. Send Custom Announcement SMS (`POST /api/v1/sms/send`)
Queues an arbitrary administrative or transactional SMS (e.g. attendance alerts, fee notices, event announcements).

- **URL:** `POST https://sms.success0.com.np/api/v1/sms/send`
- **Headers:**
  ```http
  Content-Type: application/json
  X-Gateway-Token: Udaya@momphone
  ```
- **Request Body:**
  ```json
  {
    "phone": "9809248510",
    "message": "Notice: School will remain closed tomorrow due to heavy rainfall."
  }
  ```

- **Example Response (`HTTP 200 OK`):**
  ```json
  {
    "success": true,
    "job_id": 29,
    "phone": "9809248510",
    "status": "PENDING"
  }
  ```

---

### E. Gateway Infrastructure Health (`GET /api/v1/status`)
Fetches live gateway health, cellular phone connection status, and battery percentage.

- **URL:** `GET https://sms.success0.com.np/api/v1/status`
- **Example Response (`HTTP 200 OK`):**
  ```json
  {
    "status": "ONLINE",
    "server_time": 1792152350,
    "gateway_online": true,
    "device_model": "vivo V2333",
    "last_seen_seconds": 2,
    "battery": 76,
    "metrics": {
      "total_messages": 35,
      "pending": 0,
      "dispatching": 0,
      "sent": 34,
      "failed": 1
    }
  }
  ```

---

## 5. Error Codes & Rejection Matrix

| HTTP Code | Error Reason | Cause | Solution |
|---|---|---|---|
| `401 Unauthorized` | `"Unauthorized: Invalid or missing token"` | Missing or incorrect `X-Gateway-Token`. | Pass header `X-Gateway-Token: Udaya@momphone`. |
| `429 Too Many Requests` | `"Rate limit exceeded. Please wait..."` | User tapped "Resend OTP" within 60s. | Display countdown timer to user in mobile UI. |
| `400 Bad Request` | `"Invalid phone number format"` | Empty or non-numeric phone number. | Ensure phone has at least 10 valid digits. |
| `400 Bad Request` | `"Invalid verification code"` | Wrong code entered by user. | Prompt user to re-enter code. |
| `400 Bad Request` | `"This code has already been used"` | Replay attack attempt. | Request new OTP code. |
| `404 Not Found` | `"Job #X not found"` | Invalid `job_id` passed to status query. | Check the ID returned from `/api/v1/otp/send`. |

---

## 6. Ready-to-Use Client Implementation Snippets

### Kotlin / Android (Retrofit & Coroutines)

```kotlin
package com.school.app.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// 1. Data Models
data class OtpSendRequest(
    val phone: String,
    val template: String? = null
)

data class OtpSendResponse(
    val success: Boolean,
    val message: String?,
    val phone: String?,
    val otp_id: Int?,
    val sms_job_id: Int?,
    val expires_in_seconds: Int?
)

data class JobStatusResponse(
    val success: Boolean,
    val job: JobDetails?
)

data class JobDetails(
    val id: Int,
    val phone: String,
    val status: String, // PENDING, DISPATCHING, SENT, FAILED
    val error_reason: String?,
    val updated_at: Long
)

data class OtpVerifyRequest(
    val phone: String,
    val otp: String
)

data class CommonResponse(
    val success: Boolean,
    val message: String?
)

// 2. Retrofit API Interface
interface SchoolSmsApi {
    @POST("/api/v1/otp/send")
    suspend fun requestOtp(@Body request: OtpSendRequest): OtpSendResponse

    @GET("/api/v1/sms/status")
    suspend fun checkDeliveryStatus(@Query("job_id") jobId: Int): JobStatusResponse

    @POST("/api/v1/otp/verify")
    suspend fun verifyOtp(@Body request: OtpVerifyRequest): CommonResponse
}

// 3. Client Builder with Zero-Trust Security Token Interceptor
object SchoolSmsClient {
    private const val BASE_URL = "https://sms.success0.com.np"
    private const val GATEWAY_TOKEN = "Udaya@momphone"

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder()
            .header("X-Gateway-Token", GATEWAY_TOKEN)
            .header("Content-Type", "application/json")
            .method(original.method, original.body)
            .build()
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val api: SchoolSmsApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SchoolSmsApi::class.java)
}

// 4. Usage in ViewModel / Repository
suspend fun triggerOtpAndVerifyDelivery(phoneNumber: String): Boolean {
    // Step A: Send OTP
    val response = SchoolSmsClient.api.requestOtp(OtpSendRequest(phone = phoneNumber))
    val jobId = response.sms_job_id ?: return false

    // Step B: Multi-step verification (wait for hardware confirmation)
    for (attempt in 1..5) {
        kotlinx.coroutines.delay(2000)
        val statusRes = SchoolSmsClient.api.checkDeliveryStatus(jobId)
        if (statusRes.job?.status == "SENT") {
            println("✓ Message confirmed delivered via baseband SIM 2!")
            return true
        }
    }
    return false
}
```

---

### Flutter / Dart (`http` Package)

```dart
import 'dart:convert';
import 'package:http/http.dart' as http;

class SchoolSmsGatewayService {
  static const String baseUrl = 'https://sms.success0.com.np';
  static const String secretToken = 'Udaya@momphone';

  static Map<String, String> get _headers => {
    'Content-Type': 'application/json',
    'X-Gateway-Token': secretToken,
  };

  /// Step 1: Request 6-digit OTP code to phone
  static Future<Map<String, dynamic>> sendOtp(String phone) async {
    final url = Uri.parse('$baseUrl/api/v1/otp/send');
    final response = await http.post(
      url,
      headers: _headers,
      body: jsonEncode({
        'phone': phone,
        'template': 'Your School App verification code is: {otp}. Valid for 5 mins.',
      }),
    );

    return jsonDecode(response.body);
  }

  /// Step 2: Poll delivery verification status
  static Future<String> checkDeliveryStatus(int jobId) async {
    final url = Uri.parse('$baseUrl/api/v1/sms/status?job_id=$jobId');
    final response = await http.get(url, headers: _headers);

    if (response.statusCode == 200) {
      final data = jsonDecode(response.body);
      return data['job']['status']; // PENDING, DISPATCHING, SENT, FAILED
    }
    return 'UNKNOWN';
  }

  /// Step 3: Verify the code entered by the user
  static Future<bool> verifyOtp(String phone, String code) async {
    final url = Uri.parse('$baseUrl/api/v1/otp/verify');
    final response = await http.post(
      url,
      headers: _headers,
      body: jsonEncode({
        'phone': phone,
        'otp': code.trim(),
      }),
    );

    if (response.statusCode == 200) {
      final data = jsonDecode(response.body);
      return data['success'] == true;
    }
    return false;
  }
}
```

---

### Python / Backend (`requests`)

```python
import time
import requests

BASE_URL = "https://sms.success0.com.np"
GATEWAY_TOKEN = "Udaya@momphone"

HEADERS = {
    "X-Gateway-Token": GATEWAY_TOKEN,
    "Content-Type": "application/json"
}

def send_and_verify_otp(phone_number: str) -> bool:
    # 1. Enqueue OTP
    payload = {"phone": phone_number}
    resp = requests.post(f"{BASE_URL}/api/v1/otp/send", json=payload, headers=HEADERS, timeout=10)
    data = resp.json()
    if not data.get("success"):
        print(f"Failed to request OTP: {data.get('message')}")
        return False

    job_id = data["sms_job_id"]
    print(f"✓ OTP queued! Job ID: {job_id}. Verifying cellular delivery...")

    # 2. Verify Delivery Status (Poll for up to 10 seconds)
    for _ in range(5):
        time.sleep(2)
        status_resp = requests.get(f"{BASE_URL}/api/v1/sms/status", params={"job_id": job_id}, headers=HEADERS, timeout=10)
        status_data = status_resp.json()
        job = status_data.get("job", {})
        current_status = job.get("status")
        print(f"  → Current delivery status: {current_status}")

        if current_status == "SENT":
            print(f"✓ SMS confirmed sent via SIM 2 at timestamp {job.get('updated_at')}")
            return True
        elif current_status == "FAILED":
            print(f"✗ SMS failed: {job.get('error_reason')}")
            return False

    return False
```

---

### cURL Command Line

```bash
# 1. Request OTP Code
curl -X POST "https://sms.success0.com.np/api/v1/otp/send" \
  -H "X-Gateway-Token: Udaya@momphone" \
  -H "Content-Type: application/json" \
  -d '{"phone": "9809248510"}'

# 2. Check Delivery Verification Status
curl -X GET "https://sms.success0.com.np/api/v1/sms/status?job_id=28" \
  -H "X-Gateway-Token: Udaya@momphone"

# 3. Verify OTP Code
curl -X POST "https://sms.success0.com.np/api/v1/otp/verify" \
  -H "X-Gateway-Token: Udaya@momphone" \
  -H "Content-Type: application/json" \
  -d '{"phone": "9809248510", "otp": "245510"}'

# 4. Send Custom School Announcement
curl -X POST "https://sms.success0.com.np/api/v1/sms/send" \
  -H "X-Gateway-Token: Udaya@momphone" \
  -H "Content-Type: application/json" \
  -d '{"phone": "9809248510", "message": "School Fee Reminder: Please clear term dues before Friday."}'
```

---

## 7. Production Hardening & Best Practices

1. **Token Security:** Store `Udaya@momphone` in secure application storage (`EncryptedSharedPreferences` on Android, `flutter_secure_storage` on Flutter, or environment variables on backend servers). Never log or commit the token in plaintext.
2. **Handle 429 Cooldown:** The gateway strictly blocks consecutive OTP requests to the same number within 60 seconds. Always implement a 60-second visual countdown timer on the resend button in the mobile UI.
3. **Delivery Latency:** Under standard cellular conditions, total dispatch time from API call to physical phone transmission is **1.2 to 2.5 seconds**.
4. **Autonomous Recovery:** If the phone temporarily loses internet, messages remain stored in the server SQLite WAL queue (`PENDING`). As soon as network re-establishes, the phone drains the queue automatically in FIFO order.

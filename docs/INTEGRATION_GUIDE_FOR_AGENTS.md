# 📱 School App SMS & OTP Integration Guide

**Base URL:** `https://sms.success0.com.np`  
**Authentication:** Pass header `X-Gateway-Token: Udaya@momphone` on **all** requests.  
*(Requests missing this token fail immediately with `HTTP 401 Unauthorized`.)*

---

## 1. API Endpoints

### 1.1 Request OTP Code
Generates a 6-digit code and instructs the gateway phone to dispatch an SMS via SIM 2.

- **Method / Path:** `POST /api/v1/otp/send`
- **Headers:**  
  `Content-Type: application/json`  
  `X-Gateway-Token: Udaya@momphone`
- **Request Body:**
  ```json
  {
    "phone": "9809248510"
  }
  ```
  *(Optional: `"template": "Your School verification code is: {otp}. Valid for 5 mins."`)*
- **Response (`200 OK`):**
  ```json
  {
    "success": true,
    "sms_job_id": 42,
    "expires_in_seconds": 300
  }
  ```
- **Constraints:**
  - **Rate Limit:** 60-second cooldown per phone number. Calling before 60 seconds returns `HTTP 429 Too Many Requests` with `{"retry_after_seconds": 45}`.
  - **Expiry:** Code expires automatically after 5 minutes (300 seconds).

---

### 1.2 Verify Delivery Status *(Optional Check)*
Checks whether the gateway phone's cellular baseband has physically dispatched the SMS.

- **Method / Path:** `GET /api/v1/sms/status?job_id=42`
- **Headers:**  
  `X-Gateway-Token: Udaya@momphone`
- **Response (`200 OK`):**
  ```json
  {
    "success": true,
    "job": {
      "id": 42,
      "phone": "9809248510",
      "status": "SENT",
      "error_reason": null,
      "updated_at": 1792152303
    }
  }
  ```
- **Status Lifecycle Values:**
  - `PENDING`: Stored in server queue; waiting for gateway phone pickup.
  - `DISPATCHING`: Gateway phone claimed job; sending via baseband radio.
  - `SENT`: Baseband radio confirmed transmission.
  - `FAILED`: Carrier rejected dispatch (e.g., out of balance, no service).

---

### 1.3 Verify User Submitted OTP
Validates the 6-digit code entered by the user in the School Android App.

- **Method / Path:** `POST /api/v1/otp/verify`
- **Headers:**  
  `Content-Type: application/json`  
  `X-Gateway-Token: Udaya@momphone`
- **Request Body:**
  ```json
  {
    "phone": "9809248510",
    "otp": "245510"
  }
  ```
- **Response (`200 OK` - Success):**
  ```json
  {
    "success": true,
    "message": "OTP verified successfully."
  }
  ```
- **Error Responses (`400 Bad Request`):**
  - Wrong code: `{"success": false, "message": "Invalid verification code."}`
  - Replay / Expired: `{"success": false, "message": "This verification code has already been used or expired."}`
- **Constraint:** Single-use only. A verified code cannot be reused.

---

### 1.4 Send Custom SMS Announcement *(Optional)*
Sends custom announcements or alerts to parents/students.

- **Method / Path:** `POST /api/v1/sms/send`
- **Headers:**  
  `Content-Type: application/json`  
  `X-Gateway-Token: Udaya@momphone`
- **Request Body:**
  ```json
  {
    "phone": "9809248510",
    "message": "Notice: School starts at 9:00 AM tomorrow."
  }
  ```
- **Response (`200 OK`):**
  ```json
  {
    "success": true,
    "job_id": 43,
    "status": "PENDING"
  }
  ```

---

## 2. Android Client Code (Retrofit / OkHttp)

Drop this directly into the School Android application:

```kotlin
// --- Api Models ---
data class OtpSendReq(val phone: String, val template: String? = null)
data class OtpSendRes(val success: Boolean, val sms_job_id: Int?, val message: String?)
data class OtpVerifyReq(val phone: String, val otp: String)
data class CommonRes(val success: Boolean, val message: String?)
data class JobStatusRes(val success: Boolean, val job: JobInfo?)
data class JobInfo(val id: Int, val status: String, val error_reason: String?)

// --- Retrofit Interface ---
interface SchoolSmsApi {
    @POST("/api/v1/otp/send")
    suspend fun requestOtp(@Body req: OtpSendReq): OtpSendRes

    @POST("/api/v1/otp/verify")
    suspend fun verifyOtp(@Body req: OtpVerifyReq): CommonRes

    @GET("/api/v1/sms/status")
    suspend fun checkStatus(@Query("job_id") jobId: Int): JobStatusRes
}

// --- Retrofit Builder with Token Auth ---
object SchoolSmsClient {
    private const val BASE_URL = "https://sms.success0.com.np"
    private const val TOKEN = "Udaya@momphone"

    private val client = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("X-Gateway-Token", TOKEN)
                .header("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }
        .build()

    val api: SchoolSmsApi = retrofit2.Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()
        .create(SchoolSmsApi::class.java)
}
```

### Complete Login / OTP Flow Example:

```kotlin
// Step 1: User enters phone -> Request OTP
val sendRes = SchoolSmsClient.api.requestOtp(OtpSendReq("9809248510"))
if (sendRes.success) {
    // Show OTP input dialog + start 60s cooldown countdown
    val jobId = sendRes.sms_job_id
}

// Step 2: User enters 6-digit code -> Verify
val verifyRes = SchoolSmsClient.api.verifyOtp(OtpVerifyReq("9809248510", "245510"))
if (verifyRes.success) {
    // Proceed to app home screen
} else {
    // Show error: verifyRes.message
}
```

---

## 3. Summary of HTTP Status Codes

| Code | Meaning | Action |
|---|---|---|
| `200` | Success | Proceed. |
| `401` | Unauthorized | Check `X-Gateway-Token: Udaya@momphone`. |
| `400` | Bad Request / Invalid OTP / Replay | Show error message to user. |
| `429` | Rate Limited (< 60s) | Disable Resend button for cooldown duration. |

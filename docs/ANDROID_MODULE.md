# 📱 Android Module Documentation: School SMS Gateway

## 1. Module Overview
The Android application (`com.school.smsgateway`) acts as an autonomous cellular gateway. It runs as a persistent Android foreground service, connects over HTTPS via Cloudflare Tunnel (`https://sms.success0.com.np`), and dispatches incoming OTP and notification jobs through the device's physical SIM card using native Android `SmsManager`.

---

## 2. Component Breakdown

| Component | File | Responsibility |
| :--- | :--- | :--- |
| **`MainActivity`** | `MainActivity.java` | Configuration UI, live logs, manual SMS testing, permission requests. |
| **`SmsGatewayService`** | `SmsGatewayService.java` | 24/7 Foreground Service, wakelock management, adaptive polling, heartbeat. |
| **`ApiClient`** | `ApiClient.java` | Zero-dependency HTTPS network client for handshake, jobs, and reports. |
| **`SmsSender`** | `SmsSender.java` | Native `SmsManager` cellular dispatcher with multi-part text chunking. |
| **`BatteryOptimizationHelper`**| `BatteryOptimizationHelper.java` | Manages Doze mode exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). |
| **`BootReceiver`** | `BootReceiver.java` | Auto-launches service on device power on / reboot (`BOOT_COMPLETED`). |

---

## 3. Reliability & 24/7 Survival Strategies

### 1. Battery Optimization Exemption
Standard Android aggressively kills background tasks after 10-15 minutes of screen-off time. The application prompts for:
```java
Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```
Once granted, the operating system exempts the gateway from aggressive app-standby buckets and background CPU throttling.

### 2. Dual WakeLock Strategy
The foreground service holds:
- `PowerManager.PARTIAL_WAKE_LOCK`: Keeps CPU awake to process scheduled poll loops.
- `WifiManager.WIFI_MODE_FULL_HIGH_PERF`: Prevents the Wi-Fi chip from entering deep power saving, ensuring immediate message reception.

### 3. Foreground Notification Channel
- Channel ID: `school_sms_gateway_channel_v2`
- Priority: `IMPORTANCE_LOW` (runs silently in status bar without annoying audio dings).
- Shows live stats: `Dispatched: X | Cellular Failures: Y`.

### 4. Multipart SMS Support
If a message exceeds standard 160 GSM-7 characters, `SmsSender` splits the payload using:
```java
smsManager.divideMessage(text)
smsManager.sendMultipartTextMessage(...)
```
Each segment is tracked, and confirmation is reported once the final segment is accepted by the cell tower.

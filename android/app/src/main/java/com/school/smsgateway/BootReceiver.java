package com.school.smsgateway;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/**
 * ============================================================================
 * CLASS: BootReceiver
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     Autonomous Hardware Broadcast Receiver maintaining 24/7 gateway persistence.
 *     Automatically restarts SmsGatewayService upon device boot, quick boot,
 *     package upgrade (MY_PACKAGE_REPLACED), watchdog heartbeat, or external triggers.
 *     Features unkillable AlarmManager RTC_WAKEUP self-healing watchdog.
 *
 * BROADCAST ACTIONS LISTENED:
 *     - android.intent.action.BOOT_COMPLETED: Device cold power-on.
 *     - android.intent.action.QUICKBOOT_POWERON: Fast reboot on specific chipsets.
 *     - android.intent.action.MY_PACKAGE_REPLACED: Seamless restart after APK update.
 *     - com.school.smsgateway.START_SERVICE: External control trigger to force start.
 *     - com.school.smsgateway.STOP_SERVICE: External control trigger to force stop.
 *     - com.school.smsgateway.TEST_HANDSHAKE: External trigger to perform handshake test.
 *     - com.school.smsgateway.WATCHDOG_HEARTBEAT: Exact alarm RTC_WAKEUP watchdog tick.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-19
 * ============================================================================
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    public static final String ACTION_START = "com.school.smsgateway.START_SERVICE";
    public static final String ACTION_STOP = "com.school.smsgateway.STOP_SERVICE";
    public static final String ACTION_TEST = "com.school.smsgateway.TEST_HANDSHAKE";
    public static final String ACTION_WATCHDOG = "com.school.smsgateway.WATCHDOG_HEARTBEAT";

    private static final int WATCHDOG_INTERVAL_MS = 60_000; // 60 seconds
    private static final int WATCHDOG_REQUEST_CODE = 9901;
    private static final int RESTART_REQUEST_CODE = 9902;

    private static final String DEFAULT_URL = "https://sms.success0.com.np";
    private static final String DEFAULT_TOKEN = "Udaya@momphone";

    public static void scheduleWatchdog(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, BootReceiver.class);
            intent.setAction(ACTION_WATCHDOG);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, intent, flags);

            long triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) {
            Logger.e(TAG, "Failed to schedule watchdog alarm: " + e.getMessage(), e);
        }
    }

    public static void cancelWatchdog(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, BootReceiver.class);
            intent.setAction(ACTION_WATCHDOG);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, intent, flags);
            am.cancel(pi);
            Logger.i(TAG, "Watchdog heartbeat cancelled");
        } catch (Exception e) {
            Logger.w(TAG, "Error cancelling watchdog: " + e.getMessage());
        }
    }

    public static void scheduleImmediateRestart(Context context, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, BootReceiver.class);
            intent.setAction(ACTION_START);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(context, RESTART_REQUEST_CODE, intent, flags);

            long triggerAt = System.currentTimeMillis() + delayMs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            Logger.i(TAG, "Scheduled emergency resurrection alarm in " + delayMs + "ms");
        } catch (Exception e) {
            Logger.e(TAG, "Failed to schedule emergency restart: " + e.getMessage(), e);
        }
    }

    public static void startGatewayService(Context context) {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Logger.w(TAG, "Cannot start SmsGatewayService: android.permission.SEND_SMS is not granted yet.");
            return;
        }
        Intent serviceIntent = new Intent(context, SmsGatewayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Logger.i(TAG, "Foreground start intent dispatched to SmsGatewayService.");
        } catch (Exception e) {
            Logger.e(TAG, "Failed starting SmsGatewayService: " + e.getMessage(), e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        // Initialize persistent disk logger for debugging startup events
        Logger.init(context.getApplicationContext());
        String action = intent.getAction();
        Logger.i(TAG, "BootReceiver received broadcast action: " + action);

        SharedPreferences prefs = context.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE);

        // Migrate legacy token if found in shared preferences
        String currentToken = prefs.getString("api_key", DEFAULT_TOKEN);
        if ("school_gateway_secret_2026".equals(currentToken) ||
                "school_gateway_secret_2026_super_secure_token_np".equals(currentToken)) {
            prefs.edit().putString("api_key", DEFAULT_TOKEN).apply();
            Logger.i(TAG, "Migrated BootReceiver stored token to production key: " + DEFAULT_TOKEN);
        }

        // --------------------------------------------------------------------
        // BRANCH 1: STOP SERVICE REQUEST
        // --------------------------------------------------------------------
        if (ACTION_STOP.equals(action)) {
            Logger.i(TAG, "Explicit STOP_SERVICE command received via broadcast");
            prefs.edit().putBoolean("service_enabled", false).apply();
            cancelWatchdog(context);
            Intent stopIntent = new Intent(context, SmsGatewayService.class);
            context.stopService(stopIntent);
            Logger.i(TAG, "SmsGatewayService stopped successfully via broadcast");
            return;
        }

        // --------------------------------------------------------------------
        // BRANCH 2: TEST HANDSHAKE REQUEST
        // --------------------------------------------------------------------
        if (ACTION_TEST.equals(action)) {
            Logger.i(TAG, "Explicit TEST_HANDSHAKE command received via broadcast");
            new Thread(() -> {
                String serverUrl = prefs.getString("server_url", DEFAULT_URL);
                String token = prefs.getString("api_key", DEFAULT_TOKEN);
                if ("school_gateway_secret_2026".equals(token) ||
                        "school_gateway_secret_2026_super_secure_token_np".equals(token)) {
                    token = DEFAULT_TOKEN;
                    prefs.edit().putString("api_key", DEFAULT_TOKEN).apply();
                }
                String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (deviceId == null || deviceId.isEmpty()) {
                    deviceId = "android_" + Build.MODEL;
                }

                String err = ApiClient.performHandshake(
                        serverUrl,
                        token,
                        deviceId,
                        Build.MANUFACTURER + " " + Build.MODEL,
                        100,
                        true
                );

                if (err == null) {
                    Logger.i(TAG, "Broadcast Test Handshake PASSED successfully!");
                } else {
                    Logger.e(TAG, "Broadcast Test Handshake FAILED: " + err);
                }
            }).start();
            return;
        }

        // --------------------------------------------------------------------
        // BRANCH 3: WATCHDOG HEARTBEAT ALARM
        // --------------------------------------------------------------------
        if (ACTION_WATCHDOG.equals(action)) {
            boolean isServiceEnabled = prefs.getBoolean("service_enabled", true);
            boolean isAlive = SmsGatewayService.isServiceRunning();
            Logger.i(TAG, "Watchdog heartbeat tick: enabled=" + isServiceEnabled + ", isAlive=" + isAlive);

            if (isServiceEnabled && !isAlive) {
                Logger.w(TAG, "WATCHDOG DETECTED SERVICE DEATH! Initiating emergency resurrection...");
                startGatewayService(context);
            }

            // Always reschedule next watchdog heartbeat if enabled
            if (isServiceEnabled) {
                scheduleWatchdog(context);
            }
            return;
        }

        // --------------------------------------------------------------------
        // BRANCH 4: BOOT / PACKAGE UPDATE / NETWORK CHANGE / START SERVICE
        // --------------------------------------------------------------------
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                "android.net.conn.CONNECTIVITY_CHANGE".equals(action) ||
                ACTION_START.equals(action)) {

            // Always mark enabled if explicit START or default on first install
            boolean isServiceEnabled = prefs.getBoolean("service_enabled", true);
            if (ACTION_START.equals(action)) {
                isServiceEnabled = true;
                prefs.edit().putBoolean("service_enabled", true).apply();
            }

            if (isServiceEnabled) {
                if (!SmsGatewayService.isServiceRunning()) {
                    Logger.i(TAG, "Autonomous startup authorized (" + action + "). Launching SmsGatewayService in foreground...");
                    startGatewayService(context);
                } else {
                    Logger.i(TAG, "Broadcast received (" + action + ") but service is already actively running.");
                }
                scheduleWatchdog(context);
            } else {
                Logger.i(TAG, "Service is marked disabled in preferences. Skipping auto-start.");
            }
        }
    }
}

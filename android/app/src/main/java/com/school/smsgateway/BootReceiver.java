package com.school.smsgateway;

import android.Manifest;
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
 *     package upgrade (MY_PACKAGE_REPLACED), or programmatic ADB broadcast signals.
 *
 * BROADCAST ACTIONS LISTENED:
 *     - android.intent.action.BOOT_COMPLETED: Device cold power-on.
 *     - android.intent.action.QUICKBOOT_POWERON: Fast reboot on specific chipsets.
 *     - android.intent.action.MY_PACKAGE_REPLACED: Seamless restart after APK update.
 *     - com.school.smsgateway.START_SERVICE: External control trigger to force start.
 *     - com.school.smsgateway.STOP_SERVICE: External control trigger to force stop.
 *     - com.school.smsgateway.TEST_HANDSHAKE: External trigger to perform handshake test.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    public static final String ACTION_START = "com.school.smsgateway.START_SERVICE";
    public static final String ACTION_STOP = "com.school.smsgateway.STOP_SERVICE";
    public static final String ACTION_TEST = "com.school.smsgateway.TEST_HANDSHAKE";

    private static final String DEFAULT_URL = "https://sms.success0.com.np";
    private static final String DEFAULT_TOKEN = "school_gateway_secret_2026_super_secure_token_np";

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

        // --------------------------------------------------------------------
        // BRANCH 1: STOP SERVICE REQUEST
        // --------------------------------------------------------------------
        if (ACTION_STOP.equals(action)) {
            Logger.i(TAG, "Explicit STOP_SERVICE command received via broadcast");
            prefs.edit().putBoolean("service_enabled", false).apply();
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
        // BRANCH 3: BOOT / PACKAGE UPDATE / START SERVICE
        // --------------------------------------------------------------------
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                ACTION_START.equals(action)) {

            // Always mark enabled if explicit START or default on first install
            boolean isServiceEnabled = prefs.getBoolean("service_enabled", true);
            if (ACTION_START.equals(action)) {
                isServiceEnabled = true;
                prefs.edit().putBoolean("service_enabled", true).apply();
            }

            // Verify SMS permission before starting foreground service
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Logger.w(TAG, "Cannot start SmsGatewayService: android.permission.SEND_SMS is not granted yet.");
                return;
            }

            if (isServiceEnabled) {
                Logger.i(TAG, "Autonomous startup authorized. Launching SmsGatewayService in foreground...");
                Intent serviceIntent = new Intent(context, SmsGatewayService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Logger.i(TAG, "Foreground start intent dispatched to SmsGatewayService.");
            } else {
                Logger.i(TAG, "Service is marked disabled in preferences. Skipping auto-start.");
            }
        }
    }
}

package com.school.smsgateway;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * ============================================================================
 * CLASS: BatteryOptimizationHelper
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     Ensures the SMS Gateway service survives 24/7 in the background by
 *     requesting exemption from Android Doze mode and manufacturer OEM
 *     battery killing mechanisms (e.g., Vivo background power restrictions).
 *
 * CRITICAL LIFECYCLE CONSIDERATIONS:
 *     - Without exemption, Android 9+ aggressively suspends network sockets
 *       and worker threads 5-15 minutes after screen off.
 *     - Calling ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS opens the system
 *       dialog directly without navigating through confusing settings menus.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class BatteryOptimizationHelper {

    /**
     * Checks if the application is currently exempted from Android Doze mode.
     *
     * @param context Application or Activity context.
     * @return True if battery optimization is already ignored; False otherwise.
     */
    public static boolean isBatteryOptimizationIgnored(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        }
        return true;
    }

    /**
     * Prompts the operating system's native dialog asking the user to disable
     * battery optimizations for this specific package.
     *
     * @param context Activity context needed to launch the intent.
     */
    public static void requestIgnoreBatteryOptimization(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isBatteryOptimizationIgnored(context)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    // Fallback to general battery settings if manufacturer overrides intent
                    try {
                        Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(fallback);
                    } catch (Exception ignored) {}
                }
            }
        }
    }
}

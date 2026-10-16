package com.school.smsgateway;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsManager;
import java.util.ArrayList;

/**
 * ============================================================================
 * CLASS: SmsSender
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     Native Android Cellular Radio SMS Dispatcher.
 *     Coordinates with hardware baseband modem via android.telephony.SmsManager.
 *     Provides multi-part text dividing (> 160 characters) and sent/delivery
 *     PendingIntent callbacks for cell tower verification.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class SmsSender {

    private static final String TAG = "SmsSender";
    public static final String ACTION_SMS_SENT = "com.school.smsgateway.SMS_SENT";
    public static final String EXTRA_JOB_ID = "job_id";

    public static void sendSms(Context context, int jobId, String destinationAddress, String text) throws Exception {
        Logger.i(TAG, "Preparing SMS Job #" + jobId + " -> " + destinationAddress + " (Len: " + text.length() + ")");

        SmsManager smsManager;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            smsManager = context.getSystemService(SmsManager.class);
        } else {
            smsManager = SmsManager.getDefault();
        }

        if (smsManager == null) {
            String err = "SmsManager service not available on this Android device";
            Logger.e(TAG, err);
            throw new Exception(err);
        }

        Intent sentIntent = new Intent(ACTION_SMS_SENT);
        sentIntent.setPackage(context.getPackageName());
        sentIntent.putExtra(EXTRA_JOB_ID, jobId);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent piSent = PendingIntent.getBroadcast(context, jobId, sentIntent, flags);

        try {
            if (text.length() > 160) {
                ArrayList<String> parts = smsManager.divideMessage(text);
                Logger.i(TAG, "Multipart SMS detected: Divided into " + parts.size() + " segments.");
                ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    sentIntents.add(i == parts.size() - 1 ? piSent : null);
                }
                smsManager.sendMultipartTextMessage(destinationAddress, null, parts, sentIntents, null);
            } else {
                smsManager.sendTextMessage(destinationAddress, null, text, piSent, null);
            }
            Logger.i(TAG, "Job #" + jobId + " successfully handed to Android Telephony Baseband.");
        } catch (Exception e) {
            Logger.e(TAG, "Fatal exception in SmsManager.sendTextMessage for Job #" + jobId + ": " + e.getMessage(), e);
            throw e;
        }
    }
}

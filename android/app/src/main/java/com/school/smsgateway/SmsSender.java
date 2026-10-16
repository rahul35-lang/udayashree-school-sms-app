package com.school.smsgateway;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsManager;
import java.util.ArrayList;

public class SmsSender {

    public static final String ACTION_SMS_SENT = "com.school.smsgateway.SMS_SENT";
    public static final String EXTRA_JOB_ID = "job_id";

    public static void sendSms(Context context, int jobId, String destinationAddress, String text) throws Exception {
        SmsManager smsManager;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            smsManager = context.getSystemService(SmsManager.class);
        } else {
            smsManager = SmsManager.getDefault();
        }

        if (smsManager == null) {
            throw new Exception("SmsManager not available on this device");
        }

        Intent sentIntent = new Intent(ACTION_SMS_SENT);
        sentIntent.putExtra(EXTRA_JOB_ID, jobId);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent piSent = PendingIntent.getBroadcast(context, jobId, sentIntent, flags);

        if (text.length() > 160) {
            ArrayList<String> parts = smsManager.divideMessage(text);
            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                sentIntents.add(i == parts.size() - 1 ? piSent : null);
            }
            smsManager.sendMultipartTextMessage(destinationAddress, null, parts, sentIntents, null);
        } else {
            smsManager.sendTextMessage(destinationAddress, null, text, piSent, null);
        }
    }
}

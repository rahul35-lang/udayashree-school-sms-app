package com.school.smsgateway;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 * CLASS: SmsSender
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     Native Android Cellular Radio SMS Dispatcher supporting Dual SIM slots.
 *     Coordinates with hardware baseband modem via android.telephony.SmsManager
 *     and android.telephony.SubscriptionManager.
 *
 * DUAL SIM CONVENTION:
 *     - Slot Index 0 = Physical SIM 1
 *     - Slot Index 1 = Physical SIM 2 (Configured as default)
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class SmsSender {

    private static final String TAG = "SmsSender";
    public static final String ACTION_SMS_SENT = "com.school.smsgateway.SMS_SENT";
    public static final String EXTRA_JOB_ID = "job_id";

    // Default SIM Slot: 1 corresponds to Physical SIM 2
    public static final int DEFAULT_SIM_SLOT_INDEX = 1;

    /**
     * Resolves the hardware-specific SmsManager instance bound to a target SIM slot.
     *
     * @param context Application/Service context.
     * @param targetSlotIndex Zero-based physical slot index (0 for SIM 1, 1 for SIM 2).
     * @return Bound SmsManager or system default fallback.
     */
    public static SmsManager getSmsManagerForSlot(Context context, int targetSlotIndex) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                SubscriptionManager subManager = context.getSystemService(SubscriptionManager.class);
                if (subManager != null) {
                    List<SubscriptionInfo> subList = subManager.getActiveSubscriptionInfoList();
                    if (subList != null && !subList.isEmpty()) {
                        for (SubscriptionInfo info : subList) {
                            Logger.i(TAG, "Detected SIM subscription: subId=" + info.getSubscriptionId() +
                                    ", slotIndex=" + info.getSimSlotIndex() +
                                    ", carrier=" + info.getCarrierName());

                            if (info.getSimSlotIndex() == targetSlotIndex) {
                                Logger.i(TAG, "✓ Selected SIM " + (targetSlotIndex + 1) +
                                        " [subId=" + info.getSubscriptionId() + ", " + info.getCarrierName() + "] for dispatch");
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    return context.getSystemService(SmsManager.class)
                                            .createForSubscriptionId(info.getSubscriptionId());
                                } else {
                                    return SmsManager.getSmsManagerForSubscriptionId(info.getSubscriptionId());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Logger.e(TAG, "Exception resolving SIM subscription for slot " + targetSlotIndex + ": " + e.getMessage(), e);
            }
        } else {
            Logger.w(TAG, "READ_PHONE_STATE not granted. Cannot query SubscriptionManager for SIM slot selection.");
        }

        Logger.w(TAG, "Target SIM slot " + targetSlotIndex + " not found or inaccessible; falling back to default SmsManager");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(SmsManager.class);
        } else {
            return SmsManager.getDefault();
        }
    }

    /**
     * Default dispatch method routed explicitly through SIM 2 (slot 1).
     */
    public static void sendSms(Context context, int jobId, String destinationAddress, String text) throws Exception {
        sendSms(context, jobId, destinationAddress, text, DEFAULT_SIM_SLOT_INDEX);
    }

    /**
     * Dispatches an outbound SMS text using an explicit physical SIM slot.
     *
     * @param context Application/Service context.
     * @param jobId Database tracking ID of this dispatch request.
     * @param destinationAddress Recipient phone number (E.164 or local format).
     * @param text Outbound SMS payload body.
     * @param simSlotIndex 0 for SIM 1, 1 for SIM 2.
     * @throws Exception if baseband radio refuses message handoff.
     */
    public static void sendSms(Context context, int jobId, String destinationAddress, String text, int simSlotIndex) throws Exception {
        Logger.i(TAG, "Preparing SMS Job #" + jobId + " -> " + destinationAddress +
                " via SIM " + (simSlotIndex + 1) + " (Len: " + text.length() + ")");

        SmsManager smsManager = getSmsManagerForSlot(context, simSlotIndex);

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
            Logger.i(TAG, "Job #" + jobId + " successfully handed to Android Telephony Baseband (SIM " + (simSlotIndex + 1) + ").");
        } catch (Exception e) {
            Logger.e(TAG, "Fatal exception in SmsManager.sendTextMessage for Job #" + jobId + ": " + e.getMessage(), e);
            throw e;
        }
    }
}

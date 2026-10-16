package com.school.smsgateway;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.List;

/**
 * ============================================================================
 * CLASS: SmsGatewayService
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     24/7 Persistent Foreground Service executing background cellular tasks.
 *     Deeply instrumented with Logger diagnostics for real-time inspection.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class SmsGatewayService extends Service {

    private static final String TAG = "SmsGatewayService";
    public static final String ACTION_UI_UPDATE = "com.school.smsgateway.UI_UPDATE";
    public static final String EXTRA_LOG_MSG = "log_msg";
    public static final String EXTRA_SENT_COUNT = "sent_count";
    public static final String EXTRA_FAILED_COUNT = "failed_count";
    public static final String EXTRA_LAST_PING = "last_ping";

    private static final String CHANNEL_ID = "school_sms_gateway_channel_v2";
    private static final int NOTIF_ID = 2001;

    private static final String DEFAULT_URL = "https://sms.success0.com.np";
    private static final String DEFAULT_TOKEN = "school_gateway_secret_2026_super_secure_token_np";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread pollingThread;
    private volatile boolean isRunning = false;

    private int totalSent = 0;
    private int totalFailed = 0;

    private final BroadcastReceiver smsSentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int jobId = intent.getIntExtra(SmsSender.EXTRA_JOB_ID, -1);
            int resultCode = getResultCode();
            boolean success = (resultCode == Activity.RESULT_OK);

            SharedPreferences prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);
            String serverUrl = prefs.getString("server_url", DEFAULT_URL);
            String token = prefs.getString("api_key", DEFAULT_TOKEN);

            if (success) {
                totalSent++;
                String msg = "✓ Cellular SMS delivered (Job #" + jobId + ")";
                Logger.i(TAG, msg);
                logToUi(msg);
                new Thread(() -> ApiClient.reportJobStatus(serverUrl, token, jobId, "SENT", null)).start();
            } else {
                totalFailed++;
                String err = "Cellular Error Code: " + resultCode;
                String msg = "✗ Cellular failed (Job #" + jobId + "): " + err;
                Logger.e(TAG, msg);
                logToUi(msg);
                new Thread(() -> ApiClient.reportJobStatus(serverUrl, token, jobId, "FAILED", err)).start();
            }
            updateNotification();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(getApplicationContext());
        Logger.i(TAG, "SmsGatewayService.onCreate invoked");
        createNotificationChannel();

        IntentFilter filter = new IntentFilter(SmsSender.ACTION_SMS_SENT);
        // Telephony system daemon (com.android.phone) fires the PendingIntent callback,
        // so this receiver MUST be registered with RECEIVER_EXPORTED on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(smsSentReceiver, filter);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SchoolSMSGateway:WorkerWakeLock");
            wakeLock.acquire();
            Logger.i(TAG, "WakeLock acquired: CPU power sleep disabled");
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SchoolSMSGateway:WorkerWifiLock");
            wifiLock.acquire();
            Logger.i(TAG, "WifiLock acquired: Wi-Fi deep sleep disabled");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.i(TAG, "SmsGatewayService.onStartCommand flags=" + flags + " startId=" + startId);
        startForeground(NOTIF_ID, buildNotification("SMS Gateway Active - Connected to Cloudflare Tunnel"));

        if (!isRunning) {
            isRunning = true;
            Logger.i(TAG, "Starting Gateway polling worker thread...");
            logToUi("🚀 SMS Gateway Service active in foreground.");
            startPollingLoop();
        }
        return START_STICKY;
    }

    private void startPollingLoop() {
        pollingThread = new Thread(() -> {
            int heartbeatCounter = 0;

            while (isRunning) {
                try {
                    SharedPreferences prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);
                    String serverUrl = prefs.getString("server_url", DEFAULT_URL);
                    String token = prefs.getString("api_key", DEFAULT_TOKEN);
                    int intervalSec = prefs.getInt("interval_sec", 3);

                    int batteryLevel = getBatteryLevel();
                    boolean isCharging = isCharging();
                    String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                    if (deviceId == null || deviceId.isEmpty()) {
                        deviceId = "android_" + Build.MODEL;
                    }

                    try {
                        List<ApiClient.SmsJob> jobs = ApiClient.pollPendingJobs(serverUrl, token, deviceId, batteryLevel);
                        broadcastStatus(System.currentTimeMillis());

                        if (jobs != null && !jobs.isEmpty()) {
                            Logger.i(TAG, "Received " + jobs.size() + " job(s) from server queue");
                            logToUi("📥 Received " + jobs.size() + " SMS job(s) from server.");
                            int preferredSim = prefs.getInt("preferred_sim_slot", 1); // 1 = SIM 2 (default)
                            for (ApiClient.SmsJob job : jobs) {
                                int targetSlot = (job.simSlot > 0) ? (job.simSlot - 1) : preferredSim;
                                logToUi("⏳ Cellular dispatching via SIM " + (targetSlot + 1) + " to: " + job.phone);
                                SmsSender.sendSms(this, job.id, job.phone, job.message, targetSlot);
                                Thread.sleep(700);
                            }
                        } else {
                            heartbeatCounter++;
                            if (heartbeatCounter >= 5) {
                                heartbeatCounter = 0;
                                ApiClient.sendHeartbeat(serverUrl, token, deviceId, batteryLevel, isCharging);
                            }
                        }
                    } catch (Exception e) {
                        Logger.w(TAG, "Polling exception: " + e.getMessage());
                    }

                    Thread.sleep(Math.max(1000, intervalSec * 1000));
                } catch (InterruptedException ie) {
                    Logger.i(TAG, "Polling thread interrupted");
                    break;
                } catch (Exception e) {
                    Logger.e(TAG, "Unexpected error in poll loop: " + e.getMessage(), e);
                    try { Thread.sleep(3000); } catch (Exception ignored) {}
                }
            }
        }, "GatewayPollWorker");
        pollingThread.start();
    }

    private int getBatteryLevel() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) return -1;
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level == -1 || scale == -1) return -1;
        return (int) (((float) level / (float) scale) * 100.0f);
    }

    private boolean isCharging() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) return false;
        int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void logToUi(String msg) {
        Intent intent = new Intent(ACTION_UI_UPDATE);
        intent.putExtra(EXTRA_LOG_MSG, msg);
        intent.putExtra(EXTRA_SENT_COUNT, totalSent);
        intent.putExtra(EXTRA_FAILED_COUNT, totalFailed);
        sendBroadcast(intent);
    }

    private void broadcastStatus(long lastPing) {
        Intent intent = new Intent(ACTION_UI_UPDATE);
        intent.putExtra(EXTRA_SENT_COUNT, totalSent);
        intent.putExtra(EXTRA_FAILED_COUNT, totalFailed);
        intent.putExtra(EXTRA_LAST_PING, lastPing);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "School SMS Gateway Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background 24/7 cellular SMS dispatcher");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("School SMS Gateway (Online)")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification("Dispatched: " + totalSent + " | Cellular Failures: " + totalFailed));
        }
    }

    @Override
    public void onDestroy() {
        Logger.w(TAG, "SmsGatewayService.onDestroy invoked! Cleaning up resources...");
        isRunning = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        try {
            unregisterReceiver(smsSentReceiver);
        } catch (Exception ignored) {}

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Logger.i(TAG, "WakeLock released");
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            Logger.i(TAG, "WifiLock released");
        }
        logToUi("🛑 SMS Gateway Service stopped.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

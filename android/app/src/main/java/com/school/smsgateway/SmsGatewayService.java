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
import java.util.List;

/**
 * ============================================================================
 * CLASS: SmsGatewayService
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     24/7 Persistent Foreground Service executing background cellular tasks.
 *     Maintains continuous authenticated link with the server, sends low-bandwidth
 *     heartbeat keepalives, acquires pending SMS queue jobs, and coordinates
 *     cellular radio SMS dispatches.
 *
 * LIFECYCLE & RELIABILITY GUARANTEES:
 *     - Holds PARTIAL_WAKE_LOCK so CPU remains active while screen is asleep.
 *     - Holds WIFI_MODE_FULL_HIGH_PERF lock to prevent Wi-Fi power throttling.
 *     - Operates with NotificationChannel (START_STICKY) so Android OS
 *       restarts the service if briefly killed under extreme memory pressure.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class SmsGatewayService extends Service {

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

    /**
     * BroadcastReceiver listening for cellular tower delivery reports.
     */
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
                logToUi("✓ Cellular SMS delivered (Job #" + jobId + ")");
                new Thread(() -> ApiClient.reportJobStatus(serverUrl, token, jobId, "SENT", null)).start();
            } else {
                totalFailed++;
                String err = "Cellular Error Code: " + resultCode;
                logToUi("✗ Cellular failed (Job #" + jobId + "): " + err);
                new Thread(() -> ApiClient.reportJobStatus(serverUrl, token, jobId, "FAILED", err)).start();
            }
            updateNotification();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        IntentFilter filter = new IntentFilter(SmsSender.ACTION_SMS_SENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(smsSentReceiver, filter);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SchoolSMSGateway:WorkerWakeLock");
            wakeLock.acquire();
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SchoolSMSGateway:WorkerWifiLock");
            wifiLock.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("SMS Gateway Active - Connected to Cloudflare Tunnel"));

        if (!isRunning) {
            isRunning = true;
            logToUi("🚀 SMS Gateway Service active in foreground.");
            startPollingLoop();
        }
        return START_STICKY;
    }

    /**
     * Main background worker loop.
     * Alternates between lightweight heartbeats and pending job fetching.
     */
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
                    String deviceId = Build.SERIAL != null ? Build.SERIAL : "android_phone";

                    try {
                        // 1. Fetch pending SMS jobs
                        List<ApiClient.SmsJob> jobs = ApiClient.pollPendingJobs(serverUrl, token, deviceId, batteryLevel);
                        broadcastStatus(System.currentTimeMillis());

                        if (jobs != null && !jobs.isEmpty()) {
                            logToUi("📥 Received " + jobs.size() + " SMS job(s) from server.");
                            for (ApiClient.SmsJob job : jobs) {
                                logToUi("⏳ Cellular dispatching to: " + job.phone);
                                SmsSender.sendSms(this, job.id, job.phone, job.message);
                                Thread.sleep(700); // 700ms cell tower inter-message spacing
                            }
                        } else {
                            // 2. Periodic low-bandwidth keepalive every ~15 seconds when queue is idle
                            heartbeatCounter++;
                            if (heartbeatCounter >= 5) {
                                heartbeatCounter = 0;
                                ApiClient.sendHeartbeat(serverUrl, token, deviceId, batteryLevel, isCharging);
                            }
                        }
                    } catch (Exception e) {
                        // Network offline or temporary Wi-Fi packet drop; continue loop
                    }

                    Thread.sleep(Math.max(1000, intervalSec * 1000));
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(3000); } catch (Exception ignored) {}
                }
            }
        });
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
        isRunning = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        try {
            unregisterReceiver(smsSentReceiver);
        } catch (Exception ignored) {}

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        logToUi("🛑 SMS Gateway Service stopped.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

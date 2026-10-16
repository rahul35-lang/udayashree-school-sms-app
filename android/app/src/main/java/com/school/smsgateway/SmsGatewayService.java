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

public class SmsGatewayService extends Service {

    public static final String ACTION_UI_UPDATE = "com.school.smsgateway.UI_UPDATE";
    public static final String EXTRA_LOG_MSG = "log_msg";
    public static final String EXTRA_SENT_COUNT = "sent_count";
    public static final String EXTRA_FAILED_COUNT = "failed_count";
    public static final String EXTRA_LAST_PING = "last_ping";

    private static final String CHANNEL_ID = "school_sms_gateway_channel";
    private static final int NOTIF_ID = 1001;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread pollingThread;
    private volatile boolean isRunning = false;

    private int totalSent = 0;
    private int totalFailed = 0;

    private BroadcastReceiver smsSentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int jobId = intent.getIntExtra(SmsSender.EXTRA_JOB_ID, -1);
            int resultCode = getResultCode();
            boolean success = (resultCode == Activity.RESULT_OK);

            SharedPreferences prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);
            String serverUrl = prefs.getString("server_url", "http://192.168.1.100:8000");
            String apiKey = prefs.getString("api_key", "school_gateway_secret_2026");

            if (success) {
                totalSent++;
                logToUi("✓ SMS dispatched successfully (Job #" + jobId + ")");
                new Thread(() -> ApiClient.reportJobStatus(serverUrl, apiKey, jobId, "SENT", null)).start();
            } else {
                totalFailed++;
                String err = "Failure code: " + resultCode;
                logToUi("✗ SMS failed (Job #" + jobId + "): " + err);
                new Thread(() -> ApiClient.reportJobStatus(serverUrl, apiKey, jobId, "FAILED", err)).start();
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SchoolSMSGateway:WakeLock");
            wakeLock.acquire();
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SchoolSMSGateway:WifiLock");
            wifiLock.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("SMS Gateway Active - Waiting for jobs..."));

        if (!isRunning) {
            isRunning = true;
            logToUi("🚀 SMS Gateway Service started in foreground.");
            startPollingLoop();
        }
        return START_STICKY;
    }

    private void startPollingLoop() {
        pollingThread = new Thread(() -> {
            while (isRunning) {
                try {
                    SharedPreferences prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);
                    String serverUrl = prefs.getString("server_url", "http://192.168.1.100:8000");
                    String apiKey = prefs.getString("api_key", "school_gateway_secret_2026");
                    int intervalSec = prefs.getInt("interval_sec", 3);

                    int batteryLevel = getBatteryLevel();
                    boolean isCharging = isCharging();

                    try {
                        List<ApiClient.SmsJob> jobs = ApiClient.pollPendingJobs(serverUrl, apiKey, batteryLevel, isCharging);
                        broadcastStatus(System.currentTimeMillis());

                        if (jobs != null && !jobs.isEmpty()) {
                            logToUi("📥 Received " + jobs.size() + " SMS job(s) from server.");
                            for (ApiClient.SmsJob job : jobs) {
                                logToUi("⏳ Sending to: " + job.phone);
                                SmsSender.sendSms(this, job.id, job.phone, job.message);
                                Thread.sleep(600); // 600ms spacing between cellular messages
                            }
                        }
                    } catch (Exception e) {
                        // Network error or server offline
                        // logToUi("⚠️ Ping failed: " + e.getMessage());
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
                .setContentTitle("School SMS Gateway (Active)")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification("Sent: " + totalSent + " | Failed: " + totalFailed));
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

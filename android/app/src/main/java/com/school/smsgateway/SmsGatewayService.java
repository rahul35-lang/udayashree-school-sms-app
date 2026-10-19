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
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
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
 *     Features self-healing watchdog persistence, unkillable lifecycle guards,
 *     and dynamic network reconnection wakeups.
 *     Deeply instrumented with Logger diagnostics for real-time inspection.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-19
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
    private static final String DEFAULT_TOKEN = "Udaya@momphone";

    private static volatile boolean isServiceAlive = false;

    public static boolean isServiceRunning() {
        return isServiceAlive;
    }

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread pollingThread;
    private volatile boolean isRunning = false;
    private final java.util.concurrent.atomic.AtomicBoolean runningFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private DeviceHttpServer deviceHttpServer;

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
        isServiceAlive = true;
        Logger.init(getApplicationContext());
        Logger.i(TAG, "SmsGatewayService.onCreate invoked");
        createNotificationChannel();

        // Ensure watchdog is immediately armed
        BootReceiver.scheduleWatchdog(this);

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

        // Register dynamic network callback for instant recovery on reconnection
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Logger.i(TAG, "Network connection RESTORED. Waking polling thread...");
                    if (pollingThread != null && pollingThread.isAlive()) {
                        pollingThread.interrupt();
                    }
                }

                @Override
                public void onLost(Network network) {
                    Logger.w(TAG, "Network connection LOST. Poller awaiting recovery.");
                }
            };
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
                Logger.i(TAG, "Registered NetworkCallback for active connectivity tracking");
            } catch (Exception e) {
                Logger.w(TAG, "Failed registering NetworkCallback: " + e.getMessage());
            }
        }

        // Start Embedded Device HTTP Server on local port 8088
        try {
            deviceHttpServer = new DeviceHttpServer(this, DeviceHttpServer.DEFAULT_PORT);
            deviceHttpServer.setDispatchListener((jobId, phone, message, simSlot) -> {
                String logMsg = "⚡ Direct async call (Job #" + jobId + ") to " + phone;
                Logger.i(TAG, logMsg);
                logToUi(logMsg);
            });
            deviceHttpServer.start();
            logToUi("🌐 Embedded Server listening on port " + DeviceHttpServer.DEFAULT_PORT);
        } catch (Exception e) {
            Logger.e(TAG, "Could not start embedded DeviceHttpServer: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isServiceAlive = true;
        Logger.i(TAG, "SmsGatewayService.onStartCommand flags=" + flags + " startId=" + startId);

        Notification notification = buildNotification("SMS Gateway Active - Connected to Cloudflare Tunnel");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
            startForeground(NOTIF_ID, notification, serviceType);
        } else {
            startForeground(NOTIF_ID, notification);
        }

        BootReceiver.scheduleWatchdog(this);

        if (!isRunning) {
            isRunning = true;
            runningFlag.set(true);
            Logger.i(TAG, "Starting Gateway asynchronous listener worker thread...");
            logToUi("🚀 SMS Gateway Service active in foreground.");
            startAsynchronousListenerLoop();
        }
        return START_STICKY;
    }

    private void startAsynchronousListenerLoop() {
        pollingThread = new Thread(() -> {
            Logger.i(TAG, "Starting Asynchronous Tunnel Stream Listener...");
            logToUi("🎧 Listening for instant SMS dispatch on Cloudflare Tunnel & port " + DeviceHttpServer.DEFAULT_PORT);

            while (runningFlag.get()) {
                try {
                    SharedPreferences prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);
                    String serverUrl = prefs.getString("server_url", DEFAULT_URL);
                    String token = prefs.getString("api_key", DEFAULT_TOKEN);
                    if ("school_gateway_secret_2026".equals(token) ||
                            "school_gateway_secret_2026_super_secure_token_np".equals(token)) {
                        token = DEFAULT_TOKEN;
                        prefs.edit().putString("api_key", DEFAULT_TOKEN).apply();
                        Logger.i(TAG, "Migrated SmsGatewayService stored token to production key: " + DEFAULT_TOKEN);
                    }

                    int preferredSim = prefs.getInt("preferred_sim_slot", 1);
                    int batteryLevel = getBatteryLevel();
                    String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                    if (deviceId == null || deviceId.isEmpty()) {
                        deviceId = "android_" + Build.MODEL;
                    }

                    // 1. Initial quick sweep for any PENDING jobs queued before connection
                    try {
                        List<ApiClient.SmsJob> pending = ApiClient.pollPendingJobs(serverUrl, token, deviceId, batteryLevel);
                        if (pending != null && !pending.isEmpty()) {
                            Logger.i(TAG, "Sweep claimed " + pending.size() + " pending job(s)");
                            logToUi("📥 Sweep received " + pending.size() + " SMS job(s).");
                            for (ApiClient.SmsJob job : pending) {
                                int targetSlot = (job.simSlot > 0) ? (job.simSlot - 1) : preferredSim;
                                logToUi("⏳ Cellular dispatching via SIM " + (targetSlot + 1) + " to: " + job.phone);
                                SmsSender.sendSms(this, job.id, job.phone, job.message, targetSlot);
                                Thread.sleep(700);
                            }
                        }
                    } catch (Exception e) {
                        Logger.w(TAG, "Pre-stream sweep warning: " + e.getMessage());
                    }

                    broadcastStatus(System.currentTimeMillis());

                    // 2. Open persistent real-time streaming listener on Cloudflare Tunnel
                    ApiClient.listenToTunnelStream(
                            serverUrl,
                            token,
                            deviceId,
                            batteryLevel,
                            new ApiClient.TunnelStreamListener() {
                                @Override
                                public void onConnected() {
                                    logToUi("🟢 Tunnel Stream ACTIVE - Listening for real-time OTPs");
                                    broadcastStatus(System.currentTimeMillis());
                                }

                                @Override
                                public void onJobReceived(ApiClient.SmsJob job) {
                                    logToUi("⚡ Real-time OTP received via tunnel (Job #" + job.id + ") -> " + job.phone);
                                    int targetSlot = (job.simSlot > 0) ? (job.simSlot - 1) : preferredSim;
                                    try {
                                        SmsSender.sendSms(SmsGatewayService.this, job.id, job.phone, job.message, targetSlot);
                                    } catch (Exception e) {
                                        Logger.e(TAG, "Cellular modem dispatch failure: " + e.getMessage(), e);
                                        logToUi("✗ Cellular dispatch error: " + e.getMessage());
                                    }
                                    broadcastStatus(System.currentTimeMillis());
                                }

                                @Override
                                public void onDisconnected(String reason) {
                                    logToUi("🟡 Tunnel stream disconnected (" + reason + "). Reconnecting...");
                                }
                            },
                            runningFlag
                    );

                    // Reconnection backoff
                    if (runningFlag.get()) {
                        Thread.sleep(3000);
                    }
                } catch (InterruptedException ie) {
                    if (!runningFlag.get()) {
                        Logger.i(TAG, "Listener thread stopped via interrupt");
                        break;
                    } else {
                        Logger.i(TAG, "Listener thread woken early (network restoration trigger)");
                    }
                } catch (Exception e) {
                    Logger.e(TAG, "Unexpected error in listener loop: " + e.getMessage(), e);
                    try { Thread.sleep(3000); } catch (Exception ignored) {}
                }
            }
        }, "TunnelListenerWorker");
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
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Logger.w(TAG, "onTaskRemoved triggered: Task swiped or killed! Scheduling instant resurrection...");
        SharedPreferences prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("service_enabled", true)) {
            BootReceiver.scheduleImmediateRestart(this, 1000);
        }
    }

    @Override
    public void onDestroy() {
        Logger.w(TAG, "SmsGatewayService.onDestroy invoked! Cleaning up resources...");
        isServiceAlive = false;
        isRunning = false;
        runningFlag.set(false);

        if (deviceHttpServer != null) {
            try {
                deviceHttpServer.stop();
            } catch (Exception ignored) {}
            deviceHttpServer = null;
        }

        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        try {
            unregisterReceiver(smsSentReceiver);
        } catch (Exception ignored) {}

        if (connectivityManager != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Logger.i(TAG, "WakeLock released");
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            Logger.i(TAG, "WifiLock released");
        }
        logToUi("🛑 SMS Gateway Service stopped.");

        // If service was destroyed while still enabled (e.g. LMK, OEM cleaner), resurrect!
        SharedPreferences prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("service_enabled", true)) {
            Logger.w(TAG, "Service was destroyed unexpectedly while enabled! Scheduling resurrection in 2s...");
            BootReceiver.scheduleImmediateRestart(this, 2000);
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

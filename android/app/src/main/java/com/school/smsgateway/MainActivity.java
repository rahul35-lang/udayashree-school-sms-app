package com.school.smsgateway;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ============================================================================
 * CLASS: MainActivity
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     Interactive Dashboard and Configuration Console for the Android Gateway.
 *     Deeply instrumented with Logger diagnostics for real-time inspection.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 101;
    private static final String DEFAULT_TUNNEL_URL = "https://sms.success0.com.np";
    private static final String DEFAULT_TOKEN = "school_gateway_secret_2026_super_secure_token_np";

    private TextView tvStatusBadge;
    private TextView tvStatSent;
    private TextView tvStatFailed;
    private TextView tvStatPing;
    private EditText etServerUrl;
    private EditText etApiKey;
    private Button btnTestConnection;
    private Button btnToggleService;
    private Button btnPresetTunnel;
    private Button btnPresetLocal;
    private EditText etTestPhone;
    private Button btnSendTestSms;
    private TextView tvLogs;
    private TextView btnClearLogs;

    private SharedPreferences prefs;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SmsGatewayService.ACTION_UI_UPDATE.equals(intent.getAction())) {
                String logMsg = intent.getStringExtra(SmsGatewayService.EXTRA_LOG_MSG);
                int sent = intent.getIntExtra(SmsGatewayService.EXTRA_SENT_COUNT, -1);
                int failed = intent.getIntExtra(SmsGatewayService.EXTRA_FAILED_COUNT, -1);
                long ping = intent.getLongExtra(SmsGatewayService.EXTRA_LAST_PING, 0);

                if (logMsg != null) {
                    appendLog(logMsg);
                }
                if (sent >= 0) tvStatSent.setText(String.valueOf(sent));
                if (failed >= 0) tvStatFailed.setText(String.valueOf(failed));
                if (ping > 0) tvStatPing.setText(timeFormat.format(new Date(ping)));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.init(getApplicationContext());
        Logger.i(TAG, "MainActivity.onCreate started");

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);

        initViews();
        loadSettings();
        requestNecessaryPermissions();
        BatteryOptimizationHelper.requestIgnoreBatteryOptimization(this);
        updateServiceStatusUi();

        appendLog("System ready. Log path: " + Logger.getLogFilePath());

        // --------------------------------------------------------------------
        // AUTONOMOUS GATEWAY ACTIVATION
        // If SEND_SMS permission is already granted, automatically initialize
        // the 24/7 foreground polling daemon without waiting for manual tap.
        // --------------------------------------------------------------------
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            boolean autoStart = prefs.getBoolean("service_enabled", true);
            if (autoStart) {
                Logger.i(TAG, "Permissions already granted. Auto-starting SmsGatewayService...");
                startGatewayService();
            }
        }
    }

    private void initViews() {
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        tvStatSent = findViewById(R.id.tvStatSent);
        tvStatFailed = findViewById(R.id.tvStatFailed);
        tvStatPing = findViewById(R.id.tvStatPing);
        etServerUrl = findViewById(R.id.etServerUrl);
        etApiKey = findViewById(R.id.etApiKey);
        btnTestConnection = findViewById(R.id.btnTestConnection);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnPresetTunnel = findViewById(R.id.btnPresetUsb);
        btnPresetLocal = findViewById(R.id.btnPresetWifi);
        etTestPhone = findViewById(R.id.etTestPhone);
        btnSendTestSms = findViewById(R.id.btnSendTestSms);
        tvLogs = findViewById(R.id.tvLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        btnPresetTunnel.setText("🌐 Cloudflare Tunnel");
        btnPresetTunnel.setOnClickListener(v -> {
            Logger.i(TAG, "User clicked Preset Tunnel");
            etServerUrl.setText(DEFAULT_TUNNEL_URL);
        });

        btnPresetLocal.setText("🏠 Local Network");
        btnPresetLocal.setOnClickListener(v -> {
            Logger.i(TAG, "User clicked Preset Local");
            etServerUrl.setText("http://192.168.2.231:8080");
        });

        btnClearLogs.setOnClickListener(v -> {
            Logger.i(TAG, "User cleared UI logs");
            tvLogs.setText("[Logs cleared]");
        });

        btnTestConnection.setOnClickListener(v -> testServerHandshake());
        btnToggleService.setOnClickListener(v -> toggleService());
        btnSendTestSms.setOnClickListener(v -> sendDirectTestSms());
    }

    private void loadSettings() {
        String savedUrl = prefs.getString("server_url", DEFAULT_TUNNEL_URL);
        String savedKey = prefs.getString("api_key", DEFAULT_TOKEN);
        // Seamlessly migrate legacy pre-shared token if stale key remains in prefs
        if ("school_gateway_secret_2026".equals(savedKey)) {
            savedKey = DEFAULT_TOKEN;
            prefs.edit().putString("api_key", DEFAULT_TOKEN).apply();
            Logger.i(TAG, "Migrated legacy pre-shared token to secure default in device preferences");
        }

        // Ensure SIM 2 (slot index 1) is configured as active default cellular dispatcher
        if (!prefs.contains("preferred_sim_slot") || prefs.getInt("preferred_sim_slot", -1) != 1) {
            prefs.edit().putInt("preferred_sim_slot", 1).apply();
            Logger.i(TAG, "Configured preferred cellular SIM slot to SIM 2 (slot index 1)");
        }
        etServerUrl.setText(savedUrl);
        etApiKey.setText(savedKey);
    }

    private void saveSettings() {
        prefs.edit()
                .putString("server_url", ApiClient.normalizeUrl(etServerUrl.getText().toString().trim()))
                .putString("api_key", etApiKey.getText().toString().trim())
                .apply();
        Logger.i(TAG, "Settings saved: url=" + etServerUrl.getText().toString());
    }

    private void toggleService() {
        saveSettings();
        boolean isRunning = prefs.getBoolean("service_enabled", false);

        if (isRunning) {
            stopGatewayService();
        } else {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Logger.w(TAG, "Cannot start service: SEND_SMS permission missing");
                requestNecessaryPermissions();
                Toast.makeText(this, "SMS Permission is required!", Toast.LENGTH_LONG).show();
                return;
            }
            startGatewayService();
        }
    }

    private void startGatewayService() {
        Logger.i(TAG, "Starting Gateway Service requested by user");
        prefs.edit().putBoolean("service_enabled", true).apply();
        Intent intent = new Intent(this, SmsGatewayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateServiceStatusUi();
        appendLog("✓ Starting Gateway Service...");
    }

    private void stopGatewayService() {
        Logger.i(TAG, "Stopping Gateway Service requested by user");
        prefs.edit().putBoolean("service_enabled", false).apply();
        stopService(new Intent(this, SmsGatewayService.class));
        updateServiceStatusUi();
        appendLog("🛑 Stopped Gateway Service.");
    }

    private void updateServiceStatusUi() {
        boolean isRunning = prefs.getBoolean("service_enabled", false);
        if (isRunning) {
            tvStatusBadge.setText("ACTIVE");
            tvStatusBadge.setBackgroundResource(R.drawable.btn_primary);
            btnToggleService.setText("STOP SERVICE");
            btnToggleService.setBackgroundResource(R.drawable.btn_danger);
        } else {
            tvStatusBadge.setText("STOPPED");
            tvStatusBadge.setBackgroundResource(R.drawable.btn_danger);
            btnToggleService.setText("START SERVICE");
            btnToggleService.setBackgroundResource(R.drawable.btn_primary);
        }
    }

    private void testServerHandshake() {
        saveSettings();
        String url = etServerUrl.getText().toString().trim();
        String key = etApiKey.getText().toString().trim();
        Logger.i(TAG, "Manual Handshake Test initiated to " + url);
        appendLog("Testing Handshake with: " + url + "...");

        new Thread(() -> {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = "android_" + Build.MODEL;
            }
            String error = ApiClient.performHandshake(
                    url,
                    key,
                    deviceId,
                    Build.MANUFACTURER + " " + Build.MODEL,
                    100,
                    true
            );
            runOnUiThread(() -> {
                if (error == null) {
                    Logger.i(TAG, "Handshake test PASSED");
                    Toast.makeText(MainActivity.this, "✓ Handshake Successful! Connected.", Toast.LENGTH_SHORT).show();
                    appendLog("✓ Handshake verified: Server acknowledged token & hardware.");
                } else {
                    Logger.e(TAG, "Handshake test FAILED: " + error);
                    Toast.makeText(MainActivity.this, "✗ Handshake Failed: " + error, Toast.LENGTH_LONG).show();
                    appendLog("✗ Handshake error: " + error);
                }
            });
        }).start();
    }

    private void sendDirectTestSms() {
        String phone = etTestPhone.getText().toString().trim();
        if (phone.isEmpty()) {
            Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show();
            return;
        }

        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestNecessaryPermissions();
            return;
        }

        Logger.i(TAG, "Direct Test SMS requested for phone " + phone);
        appendLog("Dispatching direct test SMS to " + phone + "...");
        try {
            SmsSender.sendSms(this, 99999, phone, "School Gateway Test SMS! Cellular dispatch verified.");
            Toast.makeText(this, "SMS submitted to cell tower!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Logger.e(TAG, "Direct Test SMS failed: " + e.getMessage(), e);
            appendLog("✗ Cellular Error: " + e.getMessage());
            Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void appendLog(String msg) {
        String time = timeFormat.format(new Date());
        String current = tvLogs.getText().toString();
        String newLogs = "[" + time + "] " + msg + "\n" + current;
        if (newLogs.length() > 3000) {
            newLogs = newLogs.substring(0, 3000);
        }
        tvLogs.setText(newLogs);
    }

    private void requestNecessaryPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = {
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions = new String[]{
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.POST_NOTIFICATIONS
                };
            }
            requestPermissions(permissions, PERMISSION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean smsGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.SEND_SMS.equals(permissions[i]) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    smsGranted = true;
                    break;
                }
            }
            if (smsGranted) {
                Logger.i(TAG, "SEND_SMS permission granted by user. Initializing gateway service...");
                startGatewayService();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SmsGatewayService.ACTION_UI_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(uiReceiver, filter);
        }
        updateServiceStatusUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(uiReceiver);
        } catch (Exception ignored) {}
    }
}

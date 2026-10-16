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
 *     Allows administrators to inspect cellular liveness, test server handshake,
 *     dispatch test SMS directly, and view real-time cellular event logs.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class MainActivity extends Activity {

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

    /**
     * BroadcastReceiver listening for real-time updates dispatched from SmsGatewayService.
     */
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
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);

        initViews();
        loadSettings();
        requestNecessaryPermissions();
        BatteryOptimizationHelper.requestIgnoreBatteryOptimization(this);
        updateServiceStatusUi();
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
        btnPresetTunnel = findViewById(R.id.btnPresetUsb);    // Re-mapped to Cloudflare Tunnel
        btnPresetLocal = findViewById(R.id.btnPresetWifi);    // Re-mapped to Local Server
        etTestPhone = findViewById(R.id.etTestPhone);
        btnSendTestSms = findViewById(R.id.btnSendTestSms);
        tvLogs = findViewById(R.id.tvLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        // Preset button click listeners
        btnPresetTunnel.setText("🌐 Cloudflare Tunnel");
        btnPresetTunnel.setOnClickListener(v -> etServerUrl.setText(DEFAULT_TUNNEL_URL));

        btnPresetLocal.setText("🏠 Local Network");
        btnPresetLocal.setOnClickListener(v -> etServerUrl.setText("http://192.168.2.231:8080"));

        btnClearLogs.setOnClickListener(v -> tvLogs.setText("[Logs cleared]"));

        btnTestConnection.setOnClickListener(v -> testServerHandshake());
        btnToggleService.setOnClickListener(v -> toggleService());
        btnSendTestSms.setOnClickListener(v -> sendDirectTestSms());
    }

    private void loadSettings() {
        etServerUrl.setText(prefs.getString("server_url", DEFAULT_TUNNEL_URL));
        etApiKey.setText(prefs.getString("api_key", DEFAULT_TOKEN));
    }

    private void saveSettings() {
        prefs.edit()
                .putString("server_url", ApiClient.normalizeUrl(etServerUrl.getText().toString().trim()))
                .putString("api_key", etApiKey.getText().toString().trim())
                .apply();
    }

    private void toggleService() {
        saveSettings();
        boolean isRunning = prefs.getBoolean("service_enabled", false);

        if (isRunning) {
            stopGatewayService();
        } else {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                requestNecessaryPermissions();
                Toast.makeText(this, "SMS Permission is required!", Toast.LENGTH_LONG).show();
                return;
            }
            startGatewayService();
        }
    }

    private void startGatewayService() {
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
        appendLog("Testing Handshake with: " + url + "...");

        new Thread(() -> {
            String error = ApiClient.performHandshake(
                    url,
                    key,
                    Build.SERIAL != null ? Build.SERIAL : "android_phone",
                    Build.MODEL,
                    100,
                    true
            );
            runOnUiThread(() -> {
                if (error == null) {
                    Toast.makeText(MainActivity.this, "✓ Handshake Successful! Connected.", Toast.LENGTH_SHORT).show();
                    appendLog("✓ Handshake verified: Server acknowledged token & hardware.");
                } else {
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

        appendLog("Dispatching direct test SMS to " + phone + "...");
        try {
            SmsSender.sendSms(this, 99999, phone, "School Gateway Test SMS! Cellular dispatch verified.");
            Toast.makeText(this, "SMS submitted to cell tower!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
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

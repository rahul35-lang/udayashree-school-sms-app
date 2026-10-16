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

public class MainActivity extends Activity {

    private static final int PERMISSION_REQ_CODE = 101;

    private TextView tvStatusBadge;
    private TextView tvStatSent;
    private TextView tvStatFailed;
    private TextView tvStatPing;
    private EditText etServerUrl;
    private EditText etApiKey;
    private Button btnTestConnection;
    private Button btnToggleService;
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
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);

        initViews();
        loadSettings();
        requestNecessaryPermissions();
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
        etTestPhone = findViewById(R.id.etTestPhone);
        btnSendTestSms = findViewById(R.id.btnSendTestSms);
        tvLogs = findViewById(R.id.tvLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        btnClearLogs.setOnClickListener(v -> tvLogs.setText("[Logs cleared]"));

        btnTestConnection.setOnClickListener(v -> testServerConnection());
        btnToggleService.setOnClickListener(v -> toggleService());
        btnSendTestSms.setOnClickListener(v -> sendDirectTestSms());
    }

    private void loadSettings() {
        etServerUrl.setText(prefs.getString("server_url", "http://127.0.0.1:8000"));
        etApiKey.setText(prefs.getString("api_key", "school_gateway_secret_2026"));
    }

    private void saveSettings() {
        prefs.edit()
                .putString("server_url", etServerUrl.getText().toString().trim())
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

    private void testServerConnection() {
        saveSettings();
        String url = etServerUrl.getText().toString().trim();
        String key = etApiKey.getText().toString().trim();
        appendLog("Testing connection to: " + url + "...");

        new Thread(() -> {
            String error = ApiClient.testConnection(url, key);
            runOnUiThread(() -> {
                if (error == null) {
                    Toast.makeText(MainActivity.this, "✓ Server Connected Successfully!", Toast.LENGTH_SHORT).show();
                    appendLog("✓ Connected! Server is reachable.");
                } else {
                    Toast.makeText(MainActivity.this, "✗ Error: " + error, Toast.LENGTH_LONG).show();
                    appendLog("✗ Connection failed: " + error);
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

        appendLog("Sending direct test SMS to " + phone + "...");
        try {
            SmsSender.sendSms(this, 99999, phone, "Test SMS from School SMS Gateway! Cellular dispatch verified.");
            Toast.makeText(this, "SMS request sent to cell tower!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            appendLog("✗ Direct SMS Error: " + e.getMessage());
            Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void appendLog(String msg) {
        String time = timeFormat.format(new Date());
        String current = tvLogs.getText().toString();
        String newLogs = "[" + time + "] " + msg + "\n" + current;
        if (newLogs.length() > 2500) {
            newLogs = newLogs.substring(0, 2500);
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

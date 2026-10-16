package com.school.smsgateway;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 * CLASS: ApiClient
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     Lightweight, Zero-Dependency HTTP & HTTPS Network Client.
 *     Communicates securely with the 24/7 central server over both local IP
 *     and public Cloudflare Tunnel domains (e.g., https://sms.success0.com.np).
 *     Deeply instrumented with Logger diagnostics for transparent debugging.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class ApiClient {

    private static final String TAG = "ApiClient";

    public static class SmsJob {
        public final int id;
        public final String phone;
        public final String message;
        public final int simSlot;

        public SmsJob(int id, String phone, String message, int simSlot) {
            this.id = id;
            this.phone = phone;
            this.message = message;
            this.simSlot = simSlot;
        }
    }

    public static String normalizeUrl(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "https://sms.success0.com.np";
        }
        String s = input.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            if (s.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*")) {
                s = "http://" + s;
            } else {
                s = "https://" + s;
            }
        }
        return s.replaceAll("/+$", "");
    }

    public static String performHandshake(
            String serverUrl,
            String token,
            String deviceId,
            String model,
            int battery,
            boolean isCharging
    ) {
        String cleanUrl = normalizeUrl(serverUrl) + "/api/v1/gateway/handshake";
        long startTime = System.currentTimeMillis();
        Logger.i(TAG, "Starting Handshake -> " + cleanUrl + " (Model: " + model + ", Battery: " + battery + "%)");

        try {
            URL url = new URL(cleanUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("X-Gateway-Token", token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("device_id", deviceId);
            body.put("device_model", model);
            body.put("battery_level", battery);
            body.put("is_charging", isCharging);
            body.put("app_version", "2.0.0");

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - startTime;
            String respText = (code == 200) ? readStream(conn.getInputStream()) : readStream(conn.getErrorStream());

            Logger.i(TAG, "Handshake Response HTTP " + code + " in " + elapsed + "ms -> " + respText);

            if (code == 200) {
                return null; // Handshake successful!
            } else {
                return "Handshake Rejected (HTTP " + code + "): " + respText;
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            Logger.e(TAG, "Handshake Exception after " + elapsed + "ms: " + e.getMessage(), e);
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static boolean sendHeartbeat(
            String serverUrl,
            String token,
            String deviceId,
            int battery,
            boolean isCharging
    ) {
        String cleanUrl = normalizeUrl(serverUrl) + "/api/v1/gateway/heartbeat";
        try {
            URL url = new URL(cleanUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("X-Gateway-Token", token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);
            body.put("battery_level", battery);
            body.put("is_charging", isCharging);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            Logger.w(TAG, "Heartbeat failed to " + cleanUrl + ": " + e.getMessage());
            return false;
        }
    }

    public static List<SmsJob> pollPendingJobs(
            String serverUrl,
            String token,
            String deviceId,
            int batteryLevel
    ) throws Exception {
        String cleanUrl = normalizeUrl(serverUrl) + "/api/v1/gateway/jobs?device_id=" +
                deviceId + "&battery=" + batteryLevel;

        URL url = new URL(cleanUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Gateway-Token", token);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readStream(conn.getErrorStream());
            Logger.w(TAG, "Poll failed HTTP " + code + " -> " + err);
            throw new Exception("HTTP " + code + ": " + err);
        }

        String responseText = readStream(conn.getInputStream());
        JSONObject json = new JSONObject(responseText);

        List<SmsJob> jobs = new ArrayList<>();
        if (json.optBoolean("success", false) && json.has("jobs")) {
            JSONArray arr = json.getJSONArray("jobs");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                jobs.add(new SmsJob(
                        obj.getInt("id"),
                        obj.getString("phone"),
                        obj.getString("message"),
                        obj.optInt("sim_slot", 0)
                ));
            }
        }
        if (!jobs.isEmpty()) {
            Logger.i(TAG, "Poll succeeded: Claimed " + jobs.size() + " job(s)");
        }
        return jobs;
    }

    public static boolean reportJobStatus(
            String serverUrl,
            String token,
            int jobId,
            String status,
            String error
    ) {
        String cleanUrl = normalizeUrl(serverUrl) + "/api/v1/gateway/report";
        Logger.i(TAG, "Reporting Job #" + jobId + " status=" + status + (error != null ? " error=" + error : ""));

        try {
            URL url = new URL(cleanUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("X-Gateway-Token", token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            JSONObject body = new JSONObject();
            body.put("job_id", jobId);
            body.put("status", status);
            if (error != null) {
                body.put("error", error);
            }

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            boolean ok = (code == 200);
            Logger.i(TAG, "Report Job #" + jobId + " result HTTP " + code);
            return ok;
        } catch (Exception e) {
            Logger.e(TAG, "Failed reporting Job #" + jobId + ": " + e.getMessage(), e);
            return false;
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

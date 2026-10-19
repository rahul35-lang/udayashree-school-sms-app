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

        HttpURLConnection conn = null;
        try {
            URL url = new URL(cleanUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Connection", "close");
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
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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
        HttpURLConnection conn = null;
        try {
            URL url = new URL(cleanUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Connection", "close");
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
            if (code == 200) {
                readStream(conn.getInputStream());
            } else {
                readStream(conn.getErrorStream());
            }
            return code == 200;
        } catch (Exception e) {
            Logger.w(TAG, "Heartbeat failed to " + cleanUrl + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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

        HttpURLConnection conn = null;
        try {
            URL url = new URL(cleanUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");
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
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
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

        HttpURLConnection conn = null;
        try {
            URL url = new URL(cleanUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Connection", "close");
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
            if (ok) {
                readStream(conn.getInputStream());
            } else {
                readStream(conn.getErrorStream());
            }
            Logger.i(TAG, "Report Job #" + jobId + " result HTTP " + code);
            return ok;
        } catch (Exception e) {
            Logger.e(TAG, "Failed reporting Job #" + jobId + ": " + e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public interface TunnelStreamListener {
        void onConnected();
        void onJobReceived(SmsJob job);
        void onDisconnected(String reason);
    }

    public static void listenToTunnelStream(
            String serverUrl,
            String token,
            String deviceId,
            int batteryLevel,
            TunnelStreamListener listener,
            java.util.concurrent.atomic.AtomicBoolean runningFlag
    ) {
        String cleanUrl = normalizeUrl(serverUrl) + "/api/v1/gateway/stream?device_id=" +
                deviceId + "&battery=" + batteryLevel;

        Logger.i(TAG, "Opening real-time tunnel stream listener -> " + cleanUrl);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(cleanUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Gateway-Token", token);
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000); // 45s read timeout (server sends keepalive every 25s)

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readStream(conn.getErrorStream());
                Logger.w(TAG, "Stream rejected HTTP " + code + " -> " + err);
                listener.onDisconnected("HTTP " + code + ": " + err);
                return;
            }

            Logger.i(TAG, "✓ Tunnel stream connected successfully! Listening for real-time events...");
            listener.onConnected();

            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;

            while (runningFlag.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(":")) {
                    // Blank line or keepalive ping (:keepalive)
                    continue;
                }
                if (line.startsWith("data:")) {
                    String jsonStr = line.substring(5).trim();
                    try {
                        JSONObject obj = new JSONObject(jsonStr);
                        String event = obj.optString("event");
                        if ("SEND_SMS".equals(event) && obj.has("job")) {
                            JSONObject jobObj = obj.getJSONObject("job");
                            SmsJob job = new SmsJob(
                                    jobObj.getInt("id"),
                                    jobObj.getString("phone"),
                                    jobObj.getString("message"),
                                    jobObj.optInt("sim_slot", 0)
                            );
                            Logger.i(TAG, "⚡ Real-time Job #" + job.id + " received over tunnel stream!");
                            listener.onJobReceived(job);
                        } else if ("CONNECTED".equals(event)) {
                            Logger.i(TAG, "Stream handshake confirmed by server: " + obj.optString("message"));
                        }
                    } catch (Exception parseEx) {
                        Logger.w(TAG, "Failed parsing stream event line: " + line);
                    }
                }
            }
            listener.onDisconnected("Stream ended by server");
        } catch (Exception e) {
            Logger.w(TAG, "Tunnel stream interrupted: " + e.getMessage());
            listener.onDisconnected(e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                is.close();
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }
}

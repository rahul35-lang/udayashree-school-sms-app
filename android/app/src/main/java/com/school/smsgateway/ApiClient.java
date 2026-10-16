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
 *
 * PROTOCOLS IMPLEMENTED:
 *     1. Mutual Token Handshake: /api/v1/gateway/handshake
 *     2. Low-Bandwidth Heartbeat: /api/v1/gateway/heartbeat
 *     3. Atomic SMS Job Acquisition: /api/v1/gateway/jobs
 *     4. Cellular Delivery Acknowledgment: /api/v1/gateway/report
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class ApiClient {

    /**
     * Data Transfer Object representing an individual outbound SMS message job.
     */
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

    /**
     * Cleans and sanitizes user-entered server URLs.
     * Automatically prepends 'https://' or 'http://' and removes trailing slashes.
     *
     * @param input Raw URL string entered by user or loaded from defaults.
     * @return Formatted root URL (e.g. "https://sms.success0.com.np").
     */
    public static String normalizeUrl(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "https://sms.success0.com.np";
        }
        String s = input.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            // Default to https if domain name, or http if local IP
            if (s.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*")) {
                s = "http://" + s;
            } else {
                s = "https://" + s;
            }
        }
        return s.replaceAll("/+$", "");
    }

    /**
     * Executes initial mutual handshake with the 24/7 server.
     *
     * @param serverUrl Target server endpoint.
     * @param token Master shared secret token.
     * @param deviceId Unique hardware identifier.
     * @param model Android phone hardware model (e.g. "Vivo V30").
     * @param battery Current battery percentage (0-100).
     * @param isCharging True if plugged into charger.
     * @return Null on success, or diagnostic error message on failure.
     */
    public static String performHandshake(
            String serverUrl,
            String token,
            String deviceId,
            String model,
            int battery,
            boolean isCharging
    ) {
        try {
            String cleanUrl = normalizeUrl(serverUrl) + "/api/v1/gateway/handshake";
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
            if (code == 200) {
                return null; // Handshake successful!
            } else {
                return "Handshake Rejected (HTTP " + code + ")";
            }
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Sends an ultra-low-bandwidth keepalive ping to the server.
     * Consumes less than 120 bytes of network payload.
     *
     * @param serverUrl Target server endpoint.
     * @param token Secret token.
     * @param deviceId Unique device identifier.
     * @param battery Battery level.
     * @param isCharging Charger status.
     * @return True if server acknowledged heartbeat; False otherwise.
     */
    public static boolean sendHeartbeat(
            String serverUrl,
            String token,
            String deviceId,
            int battery,
            boolean isCharging
    ) {
        try {
            String cleanUrl = normalizeUrl(serverUrl) + "/api/v1/gateway/heartbeat";
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
            return false;
        }
    }

    /**
     * Polls the server for pending cellular SMS jobs.
     *
     * @param serverUrl Target server endpoint.
     * @param token Secret authentication token.
     * @param deviceId Device identifier.
     * @param batteryLevel Battery level.
     * @return List of acquired SMS jobs, or empty list if none available.
     * @throws Exception On network timeout or HTTP error.
     */
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
            throw new Exception("HTTP error code: " + code);
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
        return jobs;
    }

    /**
     * Reports final cellular dispatch status back to the server.
     *
     * @param serverUrl Target server endpoint.
     * @param token Secret authentication token.
     * @param jobId Integer ID of the SMS job.
     * @param status 'SENT' or 'FAILED'.
     * @param error Diagnostic reason if failed.
     * @return True if server recorded report; False otherwise.
     */
    public static boolean reportJobStatus(
            String serverUrl,
            String token,
            int jobId,
            String status,
            String error
    ) {
        try {
            String cleanUrl = normalizeUrl(serverUrl) + "/api/v1/gateway/report";
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
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}

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

public class ApiClient {

    public static class SmsJob {
        public final int id;
        public final String phone;
        public final String message;

        public SmsJob(int id, String phone, String message) {
            this.id = id;
            this.phone = phone;
            this.message = message;
        }
    }

    public static String normalizeUrl(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "http://127.0.0.1:8080";
        }
        String s = input.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        return s.replaceAll("/+$", "");
    }

    public static String testConnection(String serverUrl, String apiKey) {
        try {
            String cleanUrl = normalizeUrl(serverUrl) + "/api/status";
            URL url = new URL(cleanUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code == 200) {
                return null; // Success
            } else {
                return "HTTP Code " + code;
            }
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static List<SmsJob> pollPendingJobs(String serverUrl, String apiKey, int batteryLevel, boolean isCharging) throws Exception {
        String cleanUrl = normalizeUrl(serverUrl) + "/api/gateway/poll?key=" + apiKey +
                "&battery=" + batteryLevel + "&charging=" + (isCharging ? "1" : "0") + "&device=AndroidGatewayPhone";

        URL url = new URL(cleanUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Gateway-Key", apiKey);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);

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
                        obj.getString("message")
                ));
            }
        }
        return jobs;
    }

    public static boolean reportJobStatus(String serverUrl, String apiKey, int jobId, String status, String error) {
        try {
            String cleanUrl = normalizeUrl(serverUrl) + "/api/gateway/report";
            URL url = new URL(cleanUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Gateway-Key", apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JSONObject body = new JSONObject();
            body.put("key", apiKey);
            body.put("id", jobId);
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

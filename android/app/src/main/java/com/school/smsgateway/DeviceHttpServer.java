package com.school.smsgateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ============================================================================
 * CLASS: DeviceHttpServer
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     Lightweight, Zero-Dependency Embedded HTTP Server for Android.
 *     Listens on a local port (e.g. 8088) or reverse tunnel, enabling the central
 *     server and school applications to asynchronously call this mobile device
 *     directly to dispatch cellular SMS with near-zero latency.
 *
 * ENDPOINTS:
 *     - GET  /api/v1/ping         -> Liveness probe and battery status
 *     - POST /api/v1/sms/dispatch -> Asynchronous direct SMS cellular dispatch
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-19
 * ============================================================================
 */
public class DeviceHttpServer {

    private static final String TAG = "DeviceHttpServer";
    public static final int DEFAULT_PORT = 8088;
    private static final String DEFAULT_TOKEN = "Udaya@momphone";

    private final Context context;
    private final int port;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;
    private ExecutorService threadPool;

    public interface OnDispatchListener {
        void onDispatched(int jobId, String phone, String message, int simSlot);
    }

    private OnDispatchListener dispatchListener;

    public DeviceHttpServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port > 0 ? port : DEFAULT_PORT;
    }

    public void setDispatchListener(OnDispatchListener listener) {
        this.dispatchListener = listener;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Logger.i(TAG, "✓ DeviceHttpServer started and listening on 0.0.0.0:" + port);

                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        threadPool.execute(() -> handleClient(client));
                    } catch (Exception e) {
                        if (!isRunning) break;
                        Logger.w(TAG, "Accept error: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Logger.e(TAG, "Failed to start DeviceHttpServer on port " + port + ": " + e.getMessage(), e);
            } finally {
                stop();
            }
        }, "DeviceHttpServerThread");
        serverThread.start();
    }

    public synchronized void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
        Logger.i(TAG, "🛑 DeviceHttpServer stopped");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getPort() {
        return port;
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(10000);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                socket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(os, 400, "Bad Request", "{\"error\":\"Invalid HTTP request\"}");
                socket.close();
                return;
            }

            String method = parts[0].toUpperCase();
            String path = parts[1];

            // Read HTTP Headers
            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.trim().isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String value = line.substring(colon + 1).trim();
                    headers.put(name, value);
                    if ("content-length".equals(name)) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Read Body if Content-Length specified
            String body = "";
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int readTotal = 0;
                while (readTotal < contentLength) {
                    int r = reader.read(bodyChars, readTotal, contentLength - readTotal);
                    if (r == -1) break;
                    readTotal += r;
                }
                body = new String(bodyChars, 0, readTotal);
            }

            SharedPreferences prefs = context.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE);
            String configuredToken = prefs.getString("api_key", DEFAULT_TOKEN);
            String requestToken = headers.get("x-gateway-token");

            // ROUTE 1: OPTIONS CORS Preflight
            if ("OPTIONS".equals(method)) {
                sendOptions(os);
                socket.close();
                return;
            }

            // ROUTE 2: GET /api/v1/ping
            if ("GET".equals(method) && ("/api/v1/ping".equals(path) || "/ping".equals(path))) {
                JSONObject res = new JSONObject();
                res.put("status", "ONLINE");
                res.put("device_model", Build.MANUFACTURER + " " + Build.MODEL);
                res.put("server", "SchoolSMSGateway Embedded Server");
                res.put("port", port);
                sendResponse(os, 200, "OK", res.toString());
                socket.close();
                return;
            }

            // AUTHENTICATION GUARD FOR PROTECTED ENDPOINTS
            if (requestToken == null || !requestToken.equals(configuredToken)) {
                Logger.w(TAG, "Unauthorized request to " + path + " from " + socket.getInetAddress());
                sendResponse(os, 401, "Unauthorized", "{\"error\":\"Invalid or missing X-Gateway-Token\"}");
                socket.close();
                return;
            }

            // ROUTE 3: POST /api/v1/sms/dispatch (Direct Asynchronous Cellular Dispatch)
            if ("POST".equals(method) && ("/api/v1/sms/dispatch".equals(path) || "/api/v1/send".equals(path))) {
                try {
                    JSONObject reqJson = new JSONObject(body);
                    int jobId = reqJson.optInt("job_id", (int) (System.currentTimeMillis() % 1000000));
                    String phone = reqJson.getString("phone");
                    String message = reqJson.getString("message");
                    int simSlot = reqJson.optInt("sim_slot", prefs.getInt("preferred_sim_slot", 1));

                    Logger.i(TAG, "⚡ DIRECT ASYNC CALL: Dispatching SMS (Job #" + jobId + ") to " + phone + " via SIM " + (simSlot + 1));

                    // Execute cellular dispatch via SIM
                    SmsSender.sendSms(context, jobId, phone, message, simSlot);

                    if (dispatchListener != null) {
                        dispatchListener.onDispatched(jobId, phone, message, simSlot);
                    }

                    JSONObject res = new JSONObject();
                    res.put("success", true);
                    res.put("status", "DISPATCHING");
                    res.put("job_id", jobId);
                    res.put("phone", phone);
                    res.put("sim_slot", simSlot + 1);
                    res.put("message", "SMS dispatched directly to cellular modem");

                    sendResponse(os, 200, "OK", res.toString());
                } catch (Exception e) {
                    Logger.e(TAG, "Failed parsing dispatch payload: " + e.getMessage(), e);
                    sendResponse(os, 400, "Bad Request", "{\"error\":\"Malformed JSON: " + e.getMessage() + "\"}");
                }
                socket.close();
                return;
            }

            sendResponse(os, 404, "Not Found", "{\"error\":\"Endpoint not found\"}");
            socket.close();
        } catch (Exception e) {
            Logger.w(TAG, "Client handling exception: " + e.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void sendResponse(OutputStream os, int statusCode, String statusText, String jsonBody) {
        try {
            byte[] bytes = jsonBody.getBytes("UTF-8");
            String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type, Authorization, X-Gateway-Token\r\n" +
                    "Connection: close\r\n\r\n";
            os.write(response.getBytes("UTF-8"));
            os.write(bytes);
            os.flush();
        } catch (Exception ignored) {}
    }

    private void sendOptions(OutputStream os) {
        try {
            String response = "HTTP/1.1 204 No Content\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type, Authorization, X-Gateway-Token\r\n" +
                    "Connection: close\r\n\r\n";
            os.write(response.getBytes("UTF-8"));
            os.flush();
        } catch (Exception ignored) {}
    }
}

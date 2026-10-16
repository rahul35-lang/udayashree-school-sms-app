#!/usr/bin/env python3
"""
===============================================================================
FILE: server.py
MODULE: SchoolSMSGateway.server.server
DESCRIPTION:
    High-Performance, Zero-Dependency Multithreaded REST Gateway Server.
    Integrates Cloudflare Tunnel ingress, token-based mutual handshakes,
    OTP verification workflows, and persistent cellular SMS dispatch queuing.

ENDPOINTS SPECIFICATION:
    1. GATEWAY API (Shared Token Protected):
       - POST /api/v1/gateway/handshake -> Hardware registration & session key
       - GET  /api/v1/gateway/jobs      -> Atomic FIFO SMS queue fetch
       - POST /api/v1/gateway/report    -> Acknowledge SENT or FAILED delivery
       - POST /api/v1/gateway/heartbeat -> Ultra-low bandwidth liveness ping

    2. SCHOOL APP API:
       - POST /api/v1/otp/send          -> Request 6-digit verification code
       - POST /api/v1/otp/verify        -> Validate parent submitted code
       - POST /api/v1/sms/send          -> Outbound announcement SMS

    3. MONITORING & DASHBOARD:
       - GET  /api/v1/status            -> Live system and cellular metrics
       - GET  /                         -> Modern browser management dashboard

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import json
import os
import sys
import threading
import time
from http import HTTPStatus
from http.server import HTTPServer, ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

try:
    from . import auth, config, database, otp_service, sms_queue
except ImportError:
    import auth, config, database, otp_service, sms_queue

HTML_UI = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>School SMS & OTP Gateway</title>
    <style>
        :root {
            --primary: #4f46e5;
            --primary-hover: #4338ca;
            --bg: #0f172a;
            --card-bg: #1e293b;
            --card-border: #334155;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --success: #10b981;
            --warning: #f59e0b;
            --danger: #ef4444;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background: var(--bg); color: var(--text-main); padding: 24px; min-height: 100vh; }
        .container { max-width: 1100px; margin: 0 auto; }
        header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; flex-wrap: wrap; gap: 12px; }
        h1 { font-size: 22px; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 10px; }
        .badge { padding: 6px 14px; border-radius: 9999px; font-size: 13px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }
        .badge.online { background: rgba(16, 185, 129, 0.2); color: var(--success); border: 1px solid var(--success); }
        .badge.offline { background: rgba(239, 68, 68, 0.2); color: var(--danger); border: 1px solid var(--danger); }
        .grid-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px; }
        .card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 20px; }
        .stat-label { font-size: 12px; color: var(--text-muted); text-transform: uppercase; font-weight: 600; margin-bottom: 8px; }
        .stat-value { font-size: 30px; font-weight: 700; color: #fff; }
        .grid-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-bottom: 24px; }
        @media (max-width: 768px) { .grid-actions { grid-template-columns: 1fr; } }
        h2 { font-size: 17px; margin-bottom: 16px; color: #cbd5e1; }
        .form-group { margin-bottom: 14px; }
        label { display: block; font-size: 13px; margin-bottom: 6px; color: var(--text-muted); }
        input, textarea { width: 100%; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--card-border); background: #0f172a; color: #fff; font-size: 14px; }
        input:focus, textarea:focus { outline: none; border-color: var(--primary); }
        button { background: var(--primary); color: #fff; border: none; padding: 10px 18px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; transition: 0.2s; }
        button:hover { background: var(--primary-hover); }
        .alert { padding: 10px; border-radius: 8px; font-size: 13px; margin-top: 10px; display: none; }
        .alert.success { background: rgba(16, 185, 129, 0.2); color: var(--success); display: block; }
        .alert.error { background: rgba(239, 68, 68, 0.2); color: var(--danger); display: block; }
        table { width: 100%; border-collapse: collapse; text-align: left; font-size: 13px; }
        th, td { padding: 12px 14px; border-bottom: 1px solid var(--card-border); }
        th { color: var(--text-muted); font-weight: 600; text-transform: uppercase; font-size: 11px; letter-spacing: 0.5px; }
        .status-tag { display: inline-block; padding: 3px 8px; border-radius: 6px; font-size: 11px; font-weight: 700; }
        .status-tag.PENDING { background: #3b82f6; color: #fff; }
        .status-tag.SENT { background: #10b981; color: #fff; }
        .status-tag.FAILED { background: #ef4444; color: #fff; }
        .status-tag.DISPATCHING { background: #f59e0b; color: #fff; }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div>
                <h1>📱 School SMS & OTP Gateway</h1>
                <p style="color: var(--text-muted); font-size: 13px; margin-top: 4px;">Cloudflare Tunnel: <code>https://sms.success0.com.np</code></p>
            </div>
            <div id="gatewayStatus" class="badge offline">Phone Gateway Offline</div>
        </header>

        <div class="grid-stats">
            <div class="card">
                <div class="stat-label">Total Outbound SMS</div>
                <div class="stat-value" id="statTotal">-</div>
            </div>
            <div class="card">
                <div class="stat-label">Pending In Queue</div>
                <div class="stat-value" id="statPending" style="color: #38bdf8;">-</div>
            </div>
            <div class="card">
                <div class="stat-label">Successfully Dispatched</div>
                <div class="stat-value" id="statSent" style="color: var(--success);">-</div>
            </div>
            <div class="card">
                <div class="stat-label">Cellular Failures</div>
                <div class="stat-value" id="statFailed" style="color: var(--danger);">-</div>
            </div>
        </div>

        <div class="grid-actions">
            <!-- Send OTP Test -->
            <div class="card">
                <h2>🔑 Send & Verify OTP (Parent Verification)</h2>
                <div class="form-group">
                    <label>Parent Mobile Number</label>
                    <input type="text" id="otpPhone" placeholder="e.g. 9841234567 or +9779841234567">
                </div>
                <button onclick="requestOtp()">Send Verification OTP</button>
                <div id="otpAlert" class="alert"></div>

                <div style="margin-top: 20px; border-top: 1px solid var(--card-border); padding-top: 16px;">
                    <label>Verify OTP Code</label>
                    <div style="display: flex; gap: 8px;">
                        <input type="text" id="verifyCode" placeholder="Enter 6-digit OTP" style="flex: 1;">
                        <button onclick="verifyOtp()" style="background: var(--success);">Verify</button>
                    </div>
                    <div id="verifyAlert" class="alert"></div>
                </div>
            </div>

            <!-- Send Custom SMS -->
            <div class="card">
                <h2>✉️ Send Custom SMS</h2>
                <div class="form-group">
                    <label>Recipient Number</label>
                    <input type="text" id="smsPhone" placeholder="e.g. 9841234567">
                </div>
                <div class="form-group">
                    <label>Message Content</label>
                    <textarea id="smsText" rows="3" placeholder="Dear Parent, your child Rahul was marked present today."></textarea>
                </div>
                <button onclick="sendCustomSms()">Queue & Send SMS</button>
                <div id="smsAlert" class="alert"></div>
            </div>
        </div>

        <div class="card">
            <h2>📜 Real-time SMS Queue & Activity Log</h2>
            <div style="overflow-x: auto; margin-top: 12px;">
                <table>
                    <thead>
                        <tr>
                            <th>ID</th>
                            <th>Time</th>
                            <th>Recipient</th>
                            <th>Message</th>
                            <th>Status</th>
                        </tr>
                    </thead>
                    <tbody id="logsTable">
                        <tr><td colspan="5" style="text-align: center; color: var(--text-muted);">Loading logs...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <script>
        async function fetchStatus() {
            try {
                const res = await fetch('/api/v1/status');
                const data = await res.json();
                
                const badge = document.getElementById('gatewayStatus');
                if (data.gateway_online) {
                    badge.className = 'badge online';
                    badge.innerText = `● Phone Online (${data.device_model || 'Device'} - ${data.battery ?? '?' }%)`;
                } else {
                    badge.className = 'badge offline';
                    badge.innerText = data.last_seen_seconds != null ? `● Phone Offline (Last seen ${data.last_seen_seconds}s ago)` : '● Phone Not Connected';
                }

                document.getElementById('statTotal').innerText = data.metrics.total_messages;
                document.getElementById('statPending').innerText = data.metrics.pending;
                document.getElementById('statSent').innerText = data.metrics.sent;
                document.getElementById('statFailed').innerText = data.metrics.failed;

                renderLogs(data.metrics.recent_activity);
            } catch (err) {
                console.error("Status fetch error:", err);
            }
        }

        function renderLogs(logs) {
            const tbody = document.getElementById('logsTable');
            if (!logs || logs.length === 0) {
                tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: var(--text-muted);">No messages sent yet.</td></tr>';
                return;
            }
            tbody.innerHTML = logs.map(l => `
                <tr>
                    <td>#${l.id}</td>
                    <td>${new Date(l.created_at * 1000).toLocaleTimeString()}</td>
                    <td><strong>${l.phone}</strong></td>
                    <td style="max-width: 380px; word-break: break-word;">${l.message}</td>
                    <td><span class="status-tag ${l.status}">${l.status}</span></td>
                </tr>
            `).join('');
        }

        async function requestOtp() {
            const phone = document.getElementById('otpPhone').value.trim();
            const alertBox = document.getElementById('otpAlert');
            if (!phone) return showMsg(alertBox, 'Please enter a phone number', false);
            
            try {
                const res = await fetch('/api/v1/otp/send', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ phone })
                });
                const d = await res.json();
                if (d.success) {
                    showMsg(alertBox, `✓ OTP queued for ${phone}! Cellular dispatch in progress.`, true);
                    fetchStatus();
                } else {
                    showMsg(alertBox, `✗ ${d.message || d.error}`, false);
                }
            } catch (err) {
                showMsg(alertBox, `Network error: ${err.message}`, false);
            }
        }

        async function verifyOtp() {
            const phone = document.getElementById('otpPhone').value.trim();
            const otp = document.getElementById('verifyCode').value.trim();
            const alertBox = document.getElementById('verifyAlert');
            if (!phone || !otp) return showMsg(alertBox, 'Enter phone and OTP code', false);
            
            try {
                const res = await fetch('/api/v1/otp/verify', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ phone, otp })
                });
                const d = await res.json();
                if (d.success) {
                    showMsg(alertBox, `✓ Verified! Phone number authenticated.`, true);
                } else {
                    showMsg(alertBox, `✗ ${d.message || d.error}`, false);
                }
            } catch (err) {
                showMsg(alertBox, `Network error: ${err.message}`, false);
            }
        }

        async function sendCustomSms() {
            const phone = document.getElementById('smsPhone').value.trim();
            const message = document.getElementById('smsText').value.trim();
            const alertBox = document.getElementById('smsAlert');
            if (!phone || !message) return showMsg(alertBox, 'Enter recipient and message', false);

            try {
                const res = await fetch('/api/v1/sms/send', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ phone, message })
                });
                const d = await res.json();
                if (d.success) {
                    showMsg(alertBox, `✓ SMS Queued (Job #${d.job_id})!`, true);
                    document.getElementById('smsText').value = '';
                    fetchStatus();
                } else {
                    showMsg(alertBox, `✗ ${d.message || d.error}`, false);
                }
            } catch (err) {
                showMsg(alertBox, `Network error: ${err.message}`, false);
            }
        }

        function showMsg(el, msg, ok) {
            el.className = `alert ${ok ? 'success' : 'error'}`;
            el.innerText = msg;
        }

        fetchStatus();
        setInterval(fetchStatus, 3000);
    </script>
</body>
</html>
"""

class ModularGatewayHandler(BaseHTTPRequestHandler):
    """
    HTTP Request Handler routing all API endpoints and static assets.
    """

    def _send_json(self, data, status=HTTPStatus.OK):
        """Helper to serialize dictionary to JSON and send with full CORS headers."""
        payload = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Gateway-Token")
        self.end_headers()
        self.wfile.write(payload)

    def _send_html(self, html_text, status=HTTPStatus.OK):
        """Helper to send HTML documents."""
        payload = html_text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_OPTIONS(self):
        """Handles browser pre-flight CORS negotiation."""
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Gateway-Token")
        self.end_headers()

    def do_GET(self):
        """Dispatches HTTP GET requests."""
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        # Dashboard Web UI
        if path in ("/", "/dashboard", "/index.html"):
            self._send_html(HTML_UI)
            return

        # System Metrics & Health Endpoint
        if path in ("/api/v1/status", "/api/status"):
            self._handle_status()
            return

        # Gateway Job Polling Endpoint (Authorized)
        if path in ("/api/v1/gateway/jobs", "/api/gateway/poll"):
            self._handle_gateway_jobs(query)
            return

        self._send_json({"success": False, "error": "Endpoint not found"}, status=HTTPStatus.NOT_FOUND)

    def do_POST(self):
        """Dispatches HTTP POST requests."""
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        content_length = int(self.headers.get("Content-Length", 0))
        body_bytes = self.rfile.read(content_length) if content_length > 0 else b"{}"

        try:
            body = json.loads(body_bytes.decode("utf-8")) if body_bytes else {}
        except json.JSONDecodeError:
            self._send_json({"success": False, "error": "Malformed JSON payload"}, status=HTTPStatus.BAD_REQUEST)
            return

        # Gateway Handshake
        if path in ("/api/v1/gateway/handshake", "/api/gateway/handshake"):
            self._handle_gateway_handshake(body)
            return

        # Gateway Heartbeat
        if path in ("/api/v1/gateway/heartbeat", "/api/gateway/heartbeat"):
            self._handle_gateway_heartbeat(body, query)
            return

        # Gateway Status Reporting (SENT / FAILED)
        if path in ("/api/v1/gateway/report", "/api/gateway/report"):
            self._handle_gateway_report(body, query)
            return

        # OTP Generation Request
        if path in ("/api/v1/otp/send", "/api/otp/send"):
            self._handle_otp_send(body)
            return

        # OTP Verification Submission
        if path in ("/api/v1/otp/verify", "/api/otp/verify"):
            self._handle_otp_verify(body)
            return

        # Generic Outbound SMS Request
        if path in ("/api/v1/sms/send", "/api/sms/send"):
            self._handle_sms_send(body)
            return

        self._send_json({"success": False, "error": "Endpoint not found"}, status=HTTPStatus.NOT_FOUND)

    # =========================================================================
    # HANDLER IMPLEMENTATIONS
    # =========================================================================

    def _handle_status(self):
        """Gathers and serializes overall system metrics and device liveness."""
        metrics = sms_queue.get_queue_metrics()
        conn = database.get_connection()
        cursor = conn.cursor()
        now = int(time.time())

        # Check most active gateway device
        cursor.execute("""
            SELECT device_id, device_model, battery_level, is_charging, last_heartbeat_at
            FROM gateway_devices
            ORDER BY last_heartbeat_at DESC LIMIT 1;
        """)
        dev = cursor.fetchone()
        conn.close()

        gateway_online = False
        last_seen = None
        battery = None
        model = None

        if dev:
            last_seen = now - dev["last_heartbeat_at"]
            gateway_online = (last_seen <= config.HEARTBEAT_TIMEOUT_SECONDS)
            battery = dev["battery_level"]
            model = dev["device_model"]

        self._send_json({
            "status": "ONLINE",
            "server_time": now,
            "gateway_online": gateway_online,
            "device_model": model,
            "last_seen_seconds": last_seen,
            "battery": battery,
            "metrics": metrics
        })

    def _handle_gateway_handshake(self, body):
        """Processes mutual handshake with Android device."""
        success, message, payload = auth.process_handshake(body)
        status_code = HTTPStatus.OK if success else HTTPStatus.UNAUTHORIZED
        self._send_json({
            "success": success,
            "message": message,
            "data": payload
        }, status=status_code)

    def _handle_gateway_heartbeat(self, body, query):
        """Handles lightweight keepalive ping from phone."""
        if not auth.verify_request_auth(dict(self.headers), query) and not auth.constant_time_verify(body.get("token"), config.GATEWAY_SECRET_TOKEN):
            self._send_json({"success": False, "error": "Unauthorized"}, status=HTTPStatus.UNAUTHORIZED)
            return

        device_id = str(body.get("device_id", "default")).strip()
        battery = body.get("battery_level")
        is_charging = 1 if body.get("is_charging") else 0
        now = int(time.time())

        conn = database.get_connection()
        cursor = conn.cursor()
        cursor.execute("""
            UPDATE gateway_devices
            SET last_heartbeat_at = ?, battery_level = COALESCE(?, battery_level), is_charging = ?
            WHERE device_id = ?;
        """, (now, battery, is_charging, device_id))
        conn.commit()
        conn.close()

        self._send_json({"success": True, "ack": "HEARTBEAT_ACK", "timestamp": now})

    def _handle_gateway_jobs(self, query):
        """Atomically returns pending SMS jobs to authenticated phone."""
        if not auth.verify_request_auth(dict(self.headers), query):
            self._send_json({"success": False, "error": "Unauthorized token"}, status=HTTPStatus.UNAUTHORIZED)
            return

        # Update keepalive on poll
        device_id = query.get("device_id", ["default"])[0]
        battery = query.get("battery", [None])[0]
        now = int(time.time())

        conn = database.get_connection()
        cursor = conn.cursor()
        cursor.execute("""
            UPDATE gateway_devices
            SET last_heartbeat_at = ?, battery_level = COALESCE(?, battery_level)
            WHERE device_id = ?;
        """, (now, int(battery) if battery else None, device_id))
        conn.commit()
        conn.close()

        jobs = sms_queue.fetch_and_claim_pending_jobs(limit=config.BATCH_POLL_LIMIT)
        self._send_json({
            "success": True,
            "jobs_count": len(jobs),
            "jobs": jobs
        })

    def _handle_gateway_report(self, body, query):
        """Receives delivery confirmation from cellular dispatcher."""
        if not auth.verify_request_auth(dict(self.headers), query) and not auth.constant_time_verify(body.get("token"), config.GATEWAY_SECRET_TOKEN):
            self._send_json({"success": False, "error": "Unauthorized"}, status=HTTPStatus.UNAUTHORIZED)
            return

        job_id = body.get("job_id") or body.get("id")
        status = body.get("status", "SENT")
        error_reason = body.get("error") or body.get("error_reason")

        if not job_id:
            self._send_json({"success": False, "error": "job_id is required"}, status=HTTPStatus.BAD_REQUEST)
            return

        updated = sms_queue.report_dispatch_result(int(job_id), status, error_reason)
        self._send_json({"success": updated, "job_id": job_id, "status": status})

    def _handle_otp_send(self, body):
        """Creates and queues a single-use verification code."""
        phone = body.get("phone", "")
        length = int(body.get("length", config.OTP_DEFAULT_LENGTH))
        expiry = int(body.get("expiry_seconds", config.OTP_DEFAULT_EXPIRY_SECONDS))
        template = body.get("template")

        success, msg, details = otp_service.create_and_queue_otp(
            phone_raw=phone,
            length=length,
            expiry_seconds=expiry,
            custom_template=template
        )

        status_code = HTTPStatus.OK if success else (HTTPStatus.TOO_MANY_REQUESTS if "Rate limit" in msg else HTTPStatus.BAD_REQUEST)
        self._send_json({
            "success": success,
            "message": msg,
            **details
        }, status=status_code)

    def _handle_otp_verify(self, body):
        """Verifies parent submitted OTP."""
        phone = body.get("phone", "")
        otp = str(body.get("otp", "")).strip()

        success, msg = otp_service.verify_submitted_otp(phone, otp)
        status_code = HTTPStatus.OK if success else HTTPStatus.BAD_REQUEST
        self._send_json({
            "success": success,
            "message": msg
        }, status=status_code)

    def _handle_sms_send(self, body):
        """Enqueues generic custom announcement or alert SMS."""
        phone = otp_service.sanitize_phone_number(body.get("phone", ""))
        message = body.get("message", "").strip()

        if not phone or not message:
            self._send_json({"success": False, "error": "Both phone and message are required"}, status=HTTPStatus.BAD_REQUEST)
            return

        job_id = sms_queue.enqueue_sms(phone, message)
        self._send_json({
            "success": True,
            "job_id": job_id,
            "phone": phone,
            "status": "PENDING"
        })

def run():
    """Initializes database and boots multithreaded servers on both ports."""
    database.init_db()

    def listen_on(port):
        try:
            httpd = ThreadingHTTPServer((config.HOST, port), ModularGatewayHandler)
            print(f"  ✓ Listening on http://{config.HOST}:{port}")
            httpd.serve_forever()
        except Exception as e:
            print(f"  Failed to bind port {port}: {e}")

    print("=======================================================")
    print("  🚀 Modular School SMS Gateway Server (Overhaul)")
    print(f"  Primary Tunnel Port:   {config.PRIMARY_PORT}")
    print(f"  Secondary Port:        {config.SECONDARY_PORT}")
    print(f"  Shared Secret Token:   {config.GATEWAY_SECRET_TOKEN[:6]}... (active)")
    print(f"  Database Path:         {config.DB_PATH}")
    print("=======================================================")

    t = threading.Thread(target=listen_on, args=(config.SECONDARY_PORT,), daemon=True)
    t.start()
    listen_on(config.PRIMARY_PORT)

if __name__ == "__main__":
    run()

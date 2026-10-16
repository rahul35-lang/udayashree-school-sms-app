#!/usr/bin/env python3
"""
School SMS & OTP Gateway Server
Zero-dependency, standalone server with SQLite persistence.
Handles OTP generation, verification, and queues SMS jobs for the Android Gateway.
"""

import json
import os
import random
import re
import sqlite3
import string
import sys
import time
from http import HTTPStatus
from http.server import HTTPServer, ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

# Configuration Defaults
PORT = int(os.environ.get("PORT", 8000))
HOST = os.environ.get("HOST", "0.0.0.0")
GATEWAY_API_KEY = os.environ.get("GATEWAY_API_KEY", "school_gateway_secret_2026")
DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sms_gateway.db")

def init_db():
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    # SMS Queue Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS sms_queue (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            phone TEXT NOT NULL,
            message TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'PENDING',
            error TEXT,
            sim_slot INTEGER DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
    """)
    
    # OTP Verifications Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS otp_verifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            phone TEXT NOT NULL,
            otp TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            is_verified INTEGER DEFAULT 0,
            verified_at INTEGER,
            created_at INTEGER NOT NULL
        )
    """)
    
    # Gateway Heartbeat Table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS gateway_heartbeat (
            id INTEGER PRIMARY KEY,
            device_info TEXT,
            battery_level INTEGER,
            is_charging INTEGER,
            last_seen INTEGER NOT NULL
        )
    """)
    
    conn.commit()
    conn.close()

def get_db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn

def sanitize_phone(phone: str) -> str:
    cleaned = re.sub(r'[\s\-\(\)]', '', phone.strip())
    return cleaned

def generate_otp(length: int = 6) -> str:
    return "".join(random.choices(string.digits, k=length))

HTML_DASHBOARD = """<!DOCTYPE html>
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
        header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
        h1 { font-size: 24px; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 10px; }
        .badge { padding: 6px 12px; border-radius: 9999px; font-size: 13px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }
        .badge.online { background: rgba(16, 185, 129, 0.2); color: var(--success); border: 1px solid var(--success); }
        .badge.offline { background: rgba(239, 68, 68, 0.2); color: var(--danger); border: 1px solid var(--danger); }
        .grid-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px; }
        .card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 20px; }
        .stat-label { font-size: 13px; color: var(--text-muted); text-transform: uppercase; font-weight: 600; margin-bottom: 8px; }
        .stat-value { font-size: 32px; font-weight: 700; color: #fff; }
        .grid-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-bottom: 24px; }
        @media (max-width: 768px) { .grid-actions { grid-template-columns: 1fr; } }
        h2 { font-size: 18px; margin-bottom: 16px; color: #cbd5e1; }
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
                <p style="color: var(--text-muted); font-size: 13px; margin-top: 4px;">Self-Hosted 24/7 Cellular SMS Gateway</p>
            </div>
            <div id="gatewayStatus" class="badge offline">Phone Gateway Offline</div>
        </header>

        <div class="grid-stats">
            <div class="card">
                <div class="stat-label">Total OTPs Sent</div>
                <div class="stat-value" id="statOtps">-</div>
            </div>
            <div class="card">
                <div class="stat-label">Pending In Queue</div>
                <div class="stat-value" id="statPending" style="color: #38bdf8;">-</div>
            </div>
            <div class="card">
                <div class="stat-label">Successfully Sent</div>
                <div class="stat-value" id="statSent" style="color: var(--success);">-</div>
            </div>
            <div class="card">
                <div class="stat-label">Failed SMS</div>
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
            <h2>📜 Recent SMS Queue & Activity Log</h2>
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
                const res = await fetch('/api/status');
                const data = await res.json();
                
                const badge = document.getElementById('gatewayStatus');
                if (data.gateway_online) {
                    badge.className = 'badge online';
                    badge.innerText = `● Phone Online (Battery ${data.battery ?? '?' }%)`;
                } else {
                    badge.className = 'badge offline';
                    badge.innerText = data.last_seen_seconds != null ? `● Phone Offline (Last seen ${data.last_seen_seconds}s ago)` : '● Phone Not Connected';
                }

                document.getElementById('statOtps').innerText = data.total_otps;
                document.getElementById('statPending').innerText = data.pending;
                document.getElementById('statSent').innerText = data.sent;
                document.getElementById('statFailed').innerText = data.failed;

                renderLogs(data.recent_logs);
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
                const res = await fetch('/api/otp/send', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ phone })
                });
                const d = await res.json();
                if (d.success) {
                    showMsg(alertBox, `✓ OTP sent to ${phone}! Expires in 5 minutes.`, true);
                    fetchStatus();
                } else {
                    showMsg(alertBox, `✗ ${d.error}`, false);
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
                const res = await fetch('/api/otp/verify', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ phone, otp })
                });
                const d = await res.json();
                if (d.success) {
                    showMsg(alertBox, `✓ Verified! Phone number authenticated.`, true);
                } else {
                    showMsg(alertBox, `✗ ${d.error}`, false);
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
                const res = await fetch('/api/sms/send', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ phone, message })
                });
                const d = await res.json();
                if (d.success) {
                    showMsg(alertBox, `✓ SMS Queued (ID #${d.message_id})!`, true);
                    document.getElementById('smsText').value = '';
                    fetchStatus();
                } else {
                    showMsg(alertBox, `✗ ${d.error}`, false);
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

class SMSGatewayHandler(BaseHTTPRequestHandler):
    def _send_json(self, data, status=HTTPStatus.OK):
        payload = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Gateway-Key")
        self.end_headers()
        self.wfile.write(payload)

    def _send_html(self, html_content, status=HTTPStatus.OK):
        payload = html_content.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_OPTIONS(self):
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Gateway-Key")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path == "/" or path == "/dashboard":
            self._send_html(HTML_DASHBOARD)
            return

        if path == "/api/status":
            self._handle_status()
            return

        if path == "/api/gateway/poll":
            self._handle_gateway_poll(query)
            return

        self._send_json({"error": "Endpoint not found"}, status=HTTPStatus.NOT_FOUND)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        content_length = int(self.headers.get("Content-Length", 0))
        body_bytes = self.rfile.read(content_length) if content_length > 0 else b"{}"
        
        try:
            body = json.loads(body_bytes.decode("utf-8")) if body_bytes else {}
        except json.JSONDecodeError:
            self._send_json({"error": "Invalid JSON body"}, status=HTTPStatus.BAD_REQUEST)
            return

        if path == "/api/otp/send":
            self._handle_otp_send(body)
            return

        if path == "/api/otp/verify":
            self._handle_otp_verify(body)
            return

        if path == "/api/sms/send":
            self._handle_sms_send(body)
            return

        if path == "/api/gateway/report":
            self._handle_gateway_report(body)
            return

        self._send_json({"error": "Endpoint not found"}, status=HTTPStatus.NOT_FOUND)

    def _handle_status(self):
        conn = get_db()
        cursor = conn.cursor()
        now = int(time.time())

        # Gateway Phone Status
        cursor.execute("SELECT battery_level, is_charging, last_seen FROM gateway_heartbeat WHERE id = 1")
        hb = cursor.fetchone()
        gateway_online = False
        last_seen_sec = None
        battery = None
        if hb:
            last_seen_sec = now - hb["last_seen"]
            # Active if polled in last 30 seconds
            gateway_online = last_seen_sec <= 30
            battery = hb["battery_level"]

        # Counts
        cursor.execute("SELECT COUNT(*) FROM otp_verifications")
        total_otps = cursor.fetchone()[0]

        cursor.execute("SELECT COUNT(*) FROM sms_queue WHERE status = 'PENDING' OR status = 'DISPATCHING'")
        pending = cursor.fetchone()[0]

        cursor.execute("SELECT COUNT(*) FROM sms_queue WHERE status = 'SENT'")
        sent = cursor.fetchone()[0]

        cursor.execute("SELECT COUNT(*) FROM sms_queue WHERE status = 'FAILED'")
        failed = cursor.fetchone()[0]

        # Recent 15 logs
        cursor.execute("""
            SELECT id, phone, message, status, created_at 
            FROM sms_queue 
            ORDER BY id DESC LIMIT 15
        """)
        rows = cursor.fetchall()
        recent = [dict(r) for r in rows]

        conn.close()

        self._send_json({
            "gateway_online": gateway_online,
            "last_seen_seconds": last_seen_sec,
            "battery": battery,
            "total_otps": total_otps,
            "pending": pending,
            "sent": sent,
            "failed": failed,
            "recent_logs": recent
        })

    def _handle_otp_send(self, body):
        phone_raw = body.get("phone", "")
        phone = sanitize_phone(phone_raw)
        if not phone or len(phone) < 8:
            self._send_json({"success": False, "error": "Invalid phone number"}, status=HTTPStatus.BAD_REQUEST)
            return

        length = int(body.get("length", 6))
        expiry_seconds = int(body.get("expiry_seconds", 300))  # 5 min default
        template = body.get("template", "Your School App verification code is: {otp}. Valid for 5 mins.")
        
        now = int(time.time())
        conn = get_db()
        cursor = conn.cursor()

        # Rate Limiting: 1 OTP per 60 seconds per phone
        cursor.execute("""
            SELECT created_at FROM otp_verifications 
            WHERE phone = ? AND is_verified = 0 AND ? - created_at < 60
            ORDER BY id DESC LIMIT 1
        """, (phone, now))
        recent = cursor.fetchone()
        if recent:
            wait_time = 60 - (now - recent["created_at"])
            conn.close()
            self._send_json({
                "success": False, 
                "error": f"Please wait {wait_time}s before requesting another OTP."
            }, status=HTTPStatus.TOO_MANY_REQUESTS)
            return

        otp = generate_otp(length)
        expires_at = now + expiry_seconds
        message_text = template.replace("{otp}", otp)

        # Record OTP
        cursor.execute("""
            INSERT INTO otp_verifications (phone, otp, expires_at, created_at)
            VALUES (?, ?, ?, ?)
        """, (phone, otp, expires_at, now))

        # Queue SMS for Android Gateway Phone
        cursor.execute("""
            INSERT INTO sms_queue (phone, message, status, created_at, updated_at)
            VALUES (?, ?, 'PENDING', ?, ?)
        """, (phone, message_text, now, now))
        msg_id = cursor.lastrowid

        conn.commit()
        conn.close()

        self._send_json({
            "success": True,
            "message_id": msg_id,
            "phone": phone,
            "expires_in": expiry_seconds
        })

    def _handle_otp_verify(self, body):
        phone_raw = body.get("phone", "")
        otp = str(body.get("otp", "")).strip()
        phone = sanitize_phone(phone_raw)

        if not phone or not otp:
            self._send_json({"success": False, "error": "Phone and OTP are required"}, status=HTTPStatus.BAD_REQUEST)
            return

        now = int(time.time())
        conn = get_db()
        cursor = conn.cursor()

        cursor.execute("""
            SELECT id, otp, expires_at FROM otp_verifications
            WHERE phone = ? AND is_verified = 0
            ORDER BY id DESC LIMIT 1
        """, (phone,))
        record = cursor.fetchone()

        if not record:
            conn.close()
            self._send_json({"success": False, "error": "No OTP requested for this number."}, status=HTTPStatus.BAD_REQUEST)
            return

        if now > record["expires_at"]:
            conn.close()
            self._send_json({"success": False, "error": "OTP has expired. Please request a new one."}, status=HTTPStatus.BAD_REQUEST)
            return

        if record["otp"] != otp:
            conn.close()
            self._send_json({"success": False, "error": "Incorrect OTP code. Please try again."}, status=HTTPStatus.BAD_REQUEST)
            return

        # Mark as verified (prevent replay attacks)
        cursor.execute("""
            UPDATE otp_verifications 
            SET is_verified = 1, verified_at = ?
            WHERE id = ?
        """, (now, record["id"]))
        conn.commit()
        conn.close()

        self._send_json({
            "success": True,
            "message": "Phone number verified successfully!"
        })

    def _handle_sms_send(self, body):
        phone_raw = body.get("phone", "")
        message = body.get("message", "").strip()
        phone = sanitize_phone(phone_raw)

        if not phone or not message:
            self._send_json({"success": False, "error": "Phone and message are required"}, status=HTTPStatus.BAD_REQUEST)
            return

        now = int(time.time())
        conn = get_db()
        cursor = conn.cursor()

        cursor.execute("""
            INSERT INTO sms_queue (phone, message, status, created_at, updated_at)
            VALUES (?, ?, 'PENDING', ?, ?)
        """, (phone, message, now, now))
        msg_id = cursor.lastrowid
        conn.commit()
        conn.close()

        self._send_json({
            "success": True,
            "message_id": msg_id
        })

    def _check_gateway_auth(self, query):
        auth_header = self.headers.get("X-Gateway-Key", "")
        query_key = query.get("key", [""])[0]
        return auth_header == GATEWAY_API_KEY or query_key == GATEWAY_API_KEY

    def _handle_gateway_poll(self, query):
        if not self._check_gateway_auth(query):
            self._send_json({"error": "Unauthorized gateway key"}, status=HTTPStatus.UNAUTHORIZED)
            return

        now = int(time.time())
        battery = query.get("battery", [None])[0]
        charging = query.get("charging", [None])[0]
        device = query.get("device", ["Android Phone"])[0]

        conn = get_db()
        cursor = conn.cursor()

        # Update heartbeat
        cursor.execute("""
            INSERT INTO gateway_heartbeat (id, device_info, battery_level, is_charging, last_seen)
            VALUES (1, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                device_info = excluded.device_info,
                battery_level = excluded.battery_level,
                is_charging = excluded.is_charging,
                last_seen = excluded.last_seen
        """, (device, int(battery) if battery else None, 1 if charging == "1" else 0, now))

        # Fetch up to 5 PENDING jobs
        cursor.execute("""
            SELECT id, phone, message 
            FROM sms_queue 
            WHERE status = 'PENDING'
            ORDER BY id ASC LIMIT 5
        """)
        rows = cursor.fetchall()
        jobs = []

        if rows:
            ids = [r["id"] for r in rows]
            cursor.execute(f"""
                UPDATE sms_queue 
                SET status = 'DISPATCHING', updated_at = ?
                WHERE id IN ({','.join(['?']*len(ids))})
            """, [now] + ids)
            jobs = [{"id": r["id"], "phone": r["phone"], "message": r["message"]} for r in rows]

        conn.commit()
        conn.close()

        self._send_json({"success": True, "jobs": jobs})

    def _handle_gateway_report(self, body):
        key = body.get("key") or self.headers.get("X-Gateway-Key")
        if key != GATEWAY_API_KEY:
            self._send_json({"error": "Unauthorized gateway key"}, status=HTTPStatus.UNAUTHORIZED)
            return

        msg_id = body.get("id")
        status = body.get("status", "SENT")  # SENT, FAILED
        error = body.get("error", "")

        now = int(time.time())
        conn = get_db()
        cursor = conn.cursor()

        cursor.execute("""
            UPDATE sms_queue 
            SET status = ?, error = ?, updated_at = ?
            WHERE id = ?
        """, (status, error, now, msg_id))

        conn.commit()
        conn.close()

        self._send_json({"success": True})

def run():
    init_db()
    import threading

    def serve_on_port(port):
        try:
            httpd = ThreadingHTTPServer((HOST, port), SMSGatewayHandler)
            print(f"  ✓ Listening on http://localhost:{port}")
            httpd.serve_forever()
        except Exception as e:
            print(f"  Could not bind on port {port}: {e}")

    print(f"=======================================================")
    print(f"  🚀 School SMS & OTP Gateway Server Running")
    print(f"  Web Dashboard:  http://localhost:8000  OR  http://localhost:8080")
    print(f"  Gateway Auth Key: {GATEWAY_API_KEY}")
    print(f"=======================================================")

    t = threading.Thread(target=serve_on_port, args=(8080,), daemon=True)
    t.start()
    serve_on_port(8000)

if __name__ == "__main__":
    run()

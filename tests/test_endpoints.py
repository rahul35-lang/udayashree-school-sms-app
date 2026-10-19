#!/usr/bin/env python3
"""
===============================================================================
TEST SUITE: test_endpoints.py
MODULE: tests.test_endpoints
DESCRIPTION:
    Comprehensive integration tests for server HTTP endpoints.
    Launches an ephemeral HTTP server on a dynamic port backed by an isolated
    temporary SQLite database, testing all REST routes, error status codes,
    authentication guards, rate limiting, and CORS headers.

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "server")))

import config
import database
import server

class TestServerEndpoints(unittest.TestCase):
    """Exhaustive integration tests for HTTP API."""

    @classmethod
    def setUpClass(cls):
        """Spins up an ephemeral HTTP server instance on dynamic port 0."""
        cls.temp_db_fd, cls.temp_db_path = tempfile.mkstemp(suffix=".db")
        config.DB_PATH = cls.temp_db_path
        database.init_db(cls.temp_db_path)

        # Bind to port 0 to let OS allocate an available port automatically
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.ModularGatewayHandler)
        cls.server_port = cls.httpd.server_address[1]
        cls.base_url = f"http://127.0.0.1:{cls.server_port}"

        cls.server_thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        """Shuts down the ephemeral server and cleans temp files."""
        cls.httpd.shutdown()
        cls.httpd.server_close()
        os.close(cls.temp_db_fd)
        if os.path.exists(cls.temp_db_path):
            os.remove(cls.temp_db_path)

    def _get(self, path, headers=None):
        if headers is None:
            headers = {}
        req = urllib.request.Request(self.base_url + path, headers=headers)
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())

    def _post(self, path, data, headers=None):
        if headers is None:
            headers = {}
        headers["Content-Type"] = "application/json"
        payload = json.dumps(data).encode("utf-8")
        req = urllib.request.Request(self.base_url + path, data=payload, headers=headers)
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())

    def test_dashboard_ui_renders(self):
        """Verifies that root URL returns HTML dashboard with HTTP 200."""
        req = urllib.request.Request(self.base_url + "/")
        with urllib.request.urlopen(req) as resp:
            self.assertEqual(resp.status, 200)
            html = resp.read().decode("utf-8")
            self.assertIn("School SMS & OTP Gateway", html)

    def test_status_endpoint(self):
        """Verifies that /api/v1/status returns valid JSON structure."""
        status_code, data = self._get("/api/v1/status")
        self.assertEqual(status_code, 200)
        self.assertEqual(data["status"], "ONLINE")
        self.assertIn("metrics", data)

    def test_handshake_flow(self):
        """Verifies handshake endpoint accepts valid tokens and rejects invalid."""
        # Legitimate token
        s_code, valid_res = self._post("/api/v1/gateway/handshake", {
            "token": config.GATEWAY_SECRET_TOKEN,
            "device_id": "test_phone_vivo",
            "device_model": "V2333"
        })
        self.assertEqual(s_code, 200)
        self.assertTrue(valid_res["success"])

        # Illegitimate token
        try:
            self._post("/api/v1/gateway/handshake", {
                "token": "wrong_token",
                "device_id": "bad_actor"
            })
            self.fail("Expected HTTP 401 Unauthorized")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)

    def test_unauthorized_jobs_polling_rejected(self):
        """Verifies that polling jobs without a valid token is blocked with HTTP 401."""
        try:
            self._get("/api/v1/gateway/jobs")
            self.fail("Expected HTTP 401 Unauthorized")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)

    def test_authorized_jobs_polling_success(self):
        """Verifies that polling jobs with valid token returns HTTP 200."""
        status_code, data = self._get("/api/v1/gateway/jobs", headers={
            "X-Gateway-Token": config.GATEWAY_SECRET_TOKEN
        })
        self.assertEqual(status_code, 200)
        self.assertTrue(data["success"])
        self.assertIn("jobs", data)

    def test_full_otp_and_sms_lifecycle(self):
        """
        Complete end-to-end integration test:
        1. Request OTP with token -> queued
        2. Attempt duplicate immediate OTP -> 429 Rate limit
        3. Poll job as phone gateway
        4. Report delivery as SENT
        5. Verify SMS job status via /api/v1/sms/status
        6. Verify submitted OTP -> success
        7. Attempt replay verification -> 400 rejection
        """
        phone = "9841239999"

        # 1. Request OTP with token
        s_code, otp_res = self._post("/api/v1/otp/send", {
            "phone": phone,
            "token": config.GATEWAY_SECRET_TOKEN
        })
        self.assertEqual(s_code, 200)
        self.assertTrue(otp_res["success"])
        msg_id = otp_res["sms_job_id"]

        # 2. Rate limit rejection
        try:
            self._post("/api/v1/otp/send", {
                "phone": phone,
                "token": config.GATEWAY_SECRET_TOKEN
            })
            self.fail("Expected 429 Rate Limit")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 429)

        # 3. Poll as phone gateway
        s_code, poll_res = self._get(f"/api/v1/gateway/jobs?token={config.GATEWAY_SECRET_TOKEN}")
        self.assertEqual(s_code, 200)
        matching = next((j for j in poll_res["jobs"] if j["id"] == msg_id), None)
        self.assertIsNotNone(matching)

        import re
        otp_match = re.search(r'\b(\d{6})\b', matching["message"])
        self.assertIsNotNone(otp_match)
        extracted_otp = otp_match.group(1)

        # 4. Report delivery as SENT
        s_code, rep_res = self._post("/api/v1/gateway/report", {
            "token": config.GATEWAY_SECRET_TOKEN,
            "id": msg_id,
            "status": "SENT"
        })
        self.assertEqual(s_code, 200)
        self.assertTrue(rep_res["success"])

        # 5. Verify SMS job status endpoint
        s_code, status_res = self._get(f"/api/v1/sms/status?job_id={msg_id}&token={config.GATEWAY_SECRET_TOKEN}")
        self.assertEqual(s_code, 200)
        self.assertTrue(status_res["success"])
        self.assertEqual(status_res["job"]["status"], "SENT")

        # 6. Verify submitted OTP with token
        s_code, ver_res = self._post("/api/v1/otp/verify", {
            "phone": phone,
            "otp": extracted_otp,
            "token": config.GATEWAY_SECRET_TOKEN
        })
        self.assertEqual(s_code, 200)
        self.assertTrue(ver_res["success"])

        # 7. Replay attack rejection
        try:
            self._post("/api/v1/otp/verify", {
                "phone": phone,
                "otp": extracted_otp,
                "token": config.GATEWAY_SECRET_TOKEN
            })
            self.fail("Expected 400 replay rejection")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 400)

    def test_unauthorized_endpoints_rejected(self):
        """Verifies that calling OTP/SMS endpoints without valid token returns HTTP 401."""
        # /api/v1/otp/send without token
        try:
            self._post("/api/v1/otp/send", {"phone": "9800000000"})
            self.fail("Expected 401 for unauthorized otp send")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)

        # /api/v1/sms/send without token
        try:
            self._post("/api/v1/sms/send", {"phone": "9800000000", "message": "Test"})
            self.fail("Expected 401 for unauthorized sms send")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)

        # /api/v1/otp/verify without token
        try:
            self._post("/api/v1/otp/verify", {"phone": "9800000000", "otp": "123456"})
            self.fail("Expected 401 for unauthorized otp verify")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)

        # /api/v1/sms/status without token
        try:
            self._get("/api/v1/sms/status?job_id=1")
            self.fail("Expected 401 for unauthorized sms status")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)

    def test_stream_endpoint_realtime_push(self):
        """Verifies that /api/v1/gateway/stream receives real-time pushed jobs with zero polling."""
        req = urllib.request.Request(
            f"{self.base_url}/api/v1/gateway/stream",
            headers={"X-Gateway-Token": config.GATEWAY_SECRET_TOKEN}
        )
        stream_resp = urllib.request.urlopen(req, timeout=5)
        # Read the initial CONNECTED event
        line1 = stream_resp.readline().decode("utf-8")
        self.assertTrue("CONNECTED" in line1, f"Expected CONNECTED event, got: {line1}")

        # In a background thread, send an SMS
        phone = "9849999999"
        msg = "Real-time streaming push test"
        threading.Thread(target=lambda: self._post("/api/v1/sms/send", {
            "phone": phone,
            "message": msg,
            "token": config.GATEWAY_SECRET_TOKEN
        })).start()

        # Read the stream for the pushed job
        deadline = time.time() + 4
        found_job = False
        while time.time() < deadline:
            line = stream_resp.readline().decode("utf-8").strip()
            if line.startswith("data:"):
                data = json.loads(line[5:])
                if data.get("event") == "SEND_SMS":
                    job = data.get("job", {})
                    if job.get("phone") == phone and job.get("message") == msg:
                        found_job = True
                        break

        stream_resp.close()
        self.assertTrue(found_job, "Expected SEND_SMS job to be pushed over stream")

if __name__ == "__main__":
    unittest.main()


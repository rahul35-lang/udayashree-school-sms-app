#!/usr/bin/env python3
"""
Automated End-to-End Test for School SMS & OTP Gateway
Tests:
1. Server Health Check (/api/status)
2. OTP Request (/api/otp/send)
3. Rate Limiting (< 60s wait)
4. Gateway Polling (/api/gateway/poll)
5. Gateway Dispatch Status Reporting (/api/gateway/report)
6. OTP Verification (/api/otp/verify)
"""

import json
import os
import subprocess
import time
import urllib.request
import urllib.error

SERVER_URL = "http://127.0.0.1:8000"
GATEWAY_KEY = "school_gateway_secret_2026"
TEST_PHONE = "9841000000"

def get(path):
    req = urllib.request.Request(SERVER_URL + path)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode())

def post(path, data, headers=None):
    if headers is None:
        headers = {}
    headers["Content-Type"] = "application/json"
    req = urllib.request.Request(SERVER_URL + path, data=json.dumps(data).encode(), headers=headers)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode())

def run_tests():
    print("====================================================")
    print(" Running End-to-End Tests on School SMS Gateway")
    print("====================================================")

    # 1. Status Check
    print("\n[1/6] Testing Server Health & Dashboard API...")
    status = get("/api/status")
    assert "total_otps" in status, "Missing total_otps in status"
    print(f"✓ Server online! Current total OTPs: {status['total_otps']}, Pending: {status['pending']}")

    # 2. Send OTP
    print(f"\n[2/6] Requesting OTP for phone: {TEST_PHONE}...")
    otp_res = post("/api/otp/send", {"phone": TEST_PHONE, "length": 6})
    assert otp_res.get("success") is True, f"Failed to send OTP: {otp_res}"
    msg_id = otp_res["message_id"]
    print(f"✓ OTP generated & queued successfully! Job ID: #{msg_id}")

    # 3. Test Rate Limiting
    print("\n[3/6] Testing 60-second spam/rate-limiting protection...")
    try:
        post("/api/otp/send", {"phone": TEST_PHONE})
        assert False, "Rate limiter failed to reject immediate spam"
    except urllib.error.HTTPError as e:
        body = json.loads(e.read().decode())
        assert e.code == 429, f"Expected 429, got {e.code}"
        print(f"✓ Rate limiting protected server! Message: {body.get('error')}")

    # 4. Gateway Phone Polling
    print("\n[4/6] Simulating Android Phone polling for pending SMS...")
    poll_res = get(f"/api/gateway/poll?key={GATEWAY_KEY}&battery=85&charging=1&device=TestPhone")
    assert poll_res.get("success") is True, f"Gateway poll failed: {poll_res}"
    jobs = poll_res.get("jobs", [])
    matching_job = next((j for j in jobs if j["id"] == msg_id), None)
    assert matching_job is not None, f"Job #{msg_id} not found in poll results!"
    print(f"✓ Android phone received job #{msg_id}!")
    print(f"  Phone: {matching_job['phone']}")
    print(f"  Message: \"{matching_job['message']}\"")

    # Extract OTP from message for testing verification
    import re
    m = re.search(r'\b(\d{6})\b', matching_job["message"])
    assert m, "No 6-digit OTP found in message body"
    generated_otp = m.group(1)
    print(f"  Extracted OTP code for test verification: {generated_otp}")

    # 5. Gateway Phone Reports Sent Status
    print("\n[5/6] Simulating Android Phone reporting 'SENT' back to server...")
    report_res = post("/api/gateway/report", {"key": GATEWAY_KEY, "id": msg_id, "status": "SENT"})
    assert report_res.get("success") is True, f"Report status failed: {report_res}"
    print("✓ Status updated to 'SENT' in database!")

    # 6. Verify OTP
    print(f"\n[6/6] Verifying OTP {generated_otp} for {TEST_PHONE}...")
    verify_res = post("/api/otp/verify", {"phone": TEST_PHONE, "otp": generated_otp})
    assert verify_res.get("success") is True, f"Verification failed: {verify_res}"
    print(f"✓ Parent phone number authenticated successfully: {verify_res.get('message')}")

    # Test replay attack prevention (re-using same OTP should fail)
    print("  Testing replay prevention (using same OTP again)...")
    try:
        replay_res = post("/api/otp/verify", {"phone": TEST_PHONE, "otp": generated_otp})
        assert False, "Replay attack was not prevented!"
    except urllib.error.HTTPError as e:
        body = json.loads(e.read().decode())
        assert e.code == 400, f"Expected 400, got {e.code}"
        print(f"✓ Replay prevention confirmed: {body.get('error')}")

    print("\n====================================================")
    print(" 🎉 ALL TESTS PASSED! FULL GATEWAY FLOW IS VERIFIED!")
    print("====================================================")

if __name__ == "__main__":
    run_tests()

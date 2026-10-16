#!/usr/bin/env python3
"""
===============================================================================
TEST SUITE: test_otp.py
MODULE: tests.test_otp
DESCRIPTION:
    Comprehensive unit tests for server.otp_service module.
    Verifies phone sanitization, cryptographic code entropy, cooldown rate
    limiting, expiration enforcement, and replay attack immunity.

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "server")))

import database
import otp_service

class TestOtpModule(unittest.TestCase):
    """Exhaustive test suite for OTP lifecycle and security."""

    def setUp(self):
        """Creates an isolated temporary database for each test case."""
        self.temp_db_fd, self.temp_db_path = tempfile.mkstemp(suffix=".db")
        database.init_db(self.temp_db_path)

    def tearDown(self):
        """Cleans up the temporary database file."""
        os.close(self.temp_db_fd)
        if os.path.exists(self.temp_db_path):
            os.remove(self.temp_db_path)

    def test_sanitize_phone_number(self):
        """Verifies robust cleaning of messy user phone formats."""
        self.assertEqual(otp_service.sanitize_phone_number("+977 98-412-34567"), "+9779841234567")
        self.assertEqual(otp_service.sanitize_phone_number("(984) 123-4567"), "9841234567")
        self.assertEqual(otp_service.sanitize_phone_number("  9841234567  "), "9841234567")
        self.assertEqual(otp_service.sanitize_phone_number(""), "")

    def test_generate_crypto_otp(self):
        """Verifies that generated OTPs match specified length and are purely numeric."""
        otp6 = otp_service.generate_crypto_otp(6)
        self.assertEqual(len(otp6), 6)
        self.assertTrue(otp6.isdigit())

        otp4 = otp_service.generate_crypto_otp(4)
        self.assertEqual(len(otp4), 4)
        self.assertTrue(otp4.isdigit())

        # Statistical test: multiple generations must not produce immediate collisions
        sample = set(otp_service.generate_crypto_otp(6) for _ in range(50))
        self.assertEqual(len(sample), 50)

    def test_create_and_queue_otp_success(self):
        """Verifies normal OTP creation and automatic outbound SMS queuing."""
        success, msg, details = otp_service.create_and_queue_otp(
            phone_raw="+9779841000001",
            length=6,
            expiry_seconds=300,
            db_path=self.temp_db_path
        )
        self.assertTrue(success)
        self.assertIn("otp_record_id", details)
        self.assertIn("sms_job_id", details)

        # Verify queued SMS message in database
        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT message, status FROM sms_queue WHERE id = ?", (details["sms_job_id"],))
        sms_row = cursor.fetchone()
        conn.close()

        self.assertIsNotNone(sms_row)
        self.assertEqual(sms_row["status"], "PENDING")
        self.assertIn("verification code", sms_row["message"])

    def test_rate_limiting_protection(self):
        """Verifies that an immediate second OTP request within 60s is rejected."""
        phone = "9841999999"
        # First request must succeed
        s1, m1, _ = otp_service.create_and_queue_otp(phone, db_path=self.temp_db_path)
        self.assertTrue(s1)

        # Immediate second request must trigger rate limit (429 condition)
        s2, m2, d2 = otp_service.create_and_queue_otp(phone, db_path=self.temp_db_path)
        self.assertFalse(s2)
        self.assertIn("Rate limit reached", m2)
        self.assertGreater(d2.get("retry_after_seconds", 0), 0)

    def test_verify_submitted_otp_success(self):
        """Verifies that providing the correct generated code succeeds."""
        phone = "9841888888"
        otp_service.create_and_queue_otp(phone, db_path=self.temp_db_path)

        # Fetch the generated code from DB
        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT otp_code FROM otp_verifications WHERE phone = ? ORDER BY id DESC LIMIT 1", (phone,))
        code = cursor.fetchone()["otp_code"]
        conn.close()

        valid, msg = otp_service.verify_submitted_otp(phone, code, db_path=self.temp_db_path)
        self.assertTrue(valid)
        self.assertIn("verified successfully", msg)

    def test_verify_submitted_otp_wrong_code(self):
        """Verifies rejection of invalid OTP code."""
        phone = "9841777777"
        otp_service.create_and_queue_otp(phone, db_path=self.temp_db_path)

        valid, msg = otp_service.verify_submitted_otp(phone, "000000", db_path=self.temp_db_path)
        self.assertFalse(valid)
        self.assertIn("Incorrect verification code", msg)

    def test_replay_attack_prevention(self):
        """Verifies that a valid OTP code CANNOT be reused a second time (single-use guarantee)."""
        phone = "9841666666"
        otp_service.create_and_queue_otp(phone, db_path=self.temp_db_path)

        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT otp_code FROM otp_verifications WHERE phone = ? ORDER BY id DESC LIMIT 1", (phone,))
        code = cursor.fetchone()["otp_code"]
        conn.close()

        # First verification must succeed
        valid1, _ = otp_service.verify_submitted_otp(phone, code, db_path=self.temp_db_path)
        self.assertTrue(valid1)

        # Second verification with exact same code must be rejected
        valid2, msg2 = otp_service.verify_submitted_otp(phone, code, db_path=self.temp_db_path)
        self.assertFalse(valid2)
        self.assertIn("No active verification request", msg2)

    def test_expired_otp_rejection(self):
        """Verifies that an expired OTP is rejected."""
        phone = "9841555555"
        # Create OTP that expired 10 seconds ago
        now = int(time.time())
        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO otp_verifications (phone, otp_code, expires_at, is_verified, created_at)
            VALUES (?, '123456', ?, 0, ?);
        """, (phone, now - 10, now - 300))
        conn.commit()
        conn.close()

        valid, msg = otp_service.verify_submitted_otp(phone, "123456", db_path=self.temp_db_path)
        self.assertFalse(valid)
        self.assertIn("expired", msg.lower())

if __name__ == "__main__":
    unittest.main()

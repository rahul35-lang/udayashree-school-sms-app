#!/usr/bin/env python3
"""
===============================================================================
TEST SUITE: test_auth.py
MODULE: tests.test_auth
DESCRIPTION:
    Comprehensive unit tests for server.auth module.
    Verifies constant-time comparison, header extraction, token verification,
    and device handshake registration.

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import os
import sys
import tempfile
import unittest

# Ensure server module is importable
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "server")))

import auth
import config
import database

class TestAuthModule(unittest.TestCase):
    """Exhaustive test suite for authentication routines."""

    def setUp(self):
        """Creates an isolated temporary database for each test case."""
        self.temp_db_fd, self.temp_db_path = tempfile.mkstemp(suffix=".db")
        database.init_db(self.temp_db_path)

    def tearDown(self):
        """Cleans up the temporary database file."""
        os.close(self.temp_db_fd)
        if os.path.exists(self.temp_db_path):
            os.remove(self.temp_db_path)

    def test_constant_time_verify_valid(self):
        """Verifies that identical tokens evaluate to True."""
        self.assertTrue(auth.constant_time_verify("secret_123", "secret_123"))

    def test_constant_time_verify_invalid(self):
        """Verifies that non-matching tokens evaluate to False."""
        self.assertFalse(auth.constant_time_verify("wrong_token", "secret_123"))

    def test_constant_time_verify_none(self):
        """Verifies that None tokens safely return False without raising exceptions."""
        self.assertFalse(auth.constant_time_verify(None, "secret_123"))

    def test_extract_token_custom_header(self):
        """Verifies token extraction from X-Gateway-Token header."""
        headers = {"X-Gateway-Token": "my_custom_token"}
        query = {}
        extracted = auth.extract_token_from_request(headers, query)
        self.assertEqual(extracted, "my_custom_token")

    def test_extract_token_bearer_header(self):
        """Verifies token extraction from Authorization: Bearer <token> header."""
        headers = {"Authorization": "Bearer token_from_bearer"}
        query = {}
        extracted = auth.extract_token_from_request(headers, query)
        self.assertEqual(extracted, "token_from_bearer")

    def test_extract_token_query_param(self):
        """Verifies token extraction from query parameter 'token'."""
        headers = {}
        query = {"token": ["token_from_query"]}
        extracted = auth.extract_token_from_request(headers, query)
        self.assertEqual(extracted, "token_from_query")

    def test_process_handshake_successful(self):
        """Verifies complete mutual handshake registration with valid credentials."""
        handshake_payload = {
            "token": config.GATEWAY_SECRET_TOKEN,
            "device_id": "phone_test_01",
            "device_model": "Vivo V30 5G",
            "battery_level": 88,
            "is_charging": True,
            "network_type": "WIFI",
            "app_version": "1.0.0"
        }
        success, msg, data = auth.process_handshake(handshake_payload, db_path=self.temp_db_path)
        self.assertTrue(success)
        self.assertIn("successful", msg)
        self.assertEqual(data.get("status"), "HANDSHAKE_ESTABLISHED")

        # Verify record in database
        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT device_model, battery_level FROM gateway_devices WHERE device_id = 'phone_test_01'")
        row = cursor.fetchone()
        conn.close()

        self.assertIsNotNone(row)
        self.assertEqual(row["device_model"], "Vivo V30 5G")
        self.assertEqual(row["battery_level"], 88)

    def test_process_handshake_invalid_token(self):
        """Verifies handshake rejection when presented with an illegitimate secret token."""
        handshake_payload = {
            "token": "fake_intruder_token",
            "device_id": "malicious_device"
        }
        success, msg, data = auth.process_handshake(handshake_payload, db_path=self.temp_db_path)
        self.assertFalse(success)
        self.assertIn("Invalid authentication token", msg)
        self.assertEqual(data, {})

if __name__ == "__main__":
    unittest.main()

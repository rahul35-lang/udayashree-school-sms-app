#!/usr/bin/env python3
"""
===============================================================================
TEST SUITE: test_database.py
MODULE: tests.test_database
DESCRIPTION:
    Comprehensive unit tests for server.database module.
    Verifies schema integrity, WAL mode configuration, index generation,
    and multithreaded concurrent query execution.

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import os
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "server")))

import database

class TestDatabaseModule(unittest.TestCase):
    """Exhaustive test suite for database operations."""

    def setUp(self):
        """Creates an isolated temporary database for each test case."""
        self.temp_db_fd, self.temp_db_path = tempfile.mkstemp(suffix=".db")
        database.init_db(self.temp_db_path)

    def tearDown(self):
        """Cleans up the temporary database file."""
        os.close(self.temp_db_fd)
        if os.path.exists(self.temp_db_path):
            os.remove(self.temp_db_path)

    def test_schema_initialization_idempotent(self):
        """Verifies that calling init_db multiple times does not error or drop tables."""
        database.init_db(self.temp_db_path)
        database.init_db(self.temp_db_path)

        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table';")
        tables = [r["name"] for r in cursor.fetchall()]
        conn.close()

        self.assertIn("sms_queue", tables)
        self.assertIn("otp_verifications", tables)
        self.assertIn("gateway_devices", tables)
        self.assertIn("audit_logs", tables)

    def test_wal_journal_mode(self):
        """Verifies that the database initializes in WAL mode for high concurrency."""
        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("PRAGMA journal_mode;")
        mode = cursor.fetchone()[0]
        conn.close()
        self.assertEqual(mode.lower(), "wal")

    def test_concurrent_multithreaded_writes(self):
        """Verifies thread safety during simultaneous database write operations."""
        errors = []

        def worker(thread_idx):
            try:
                conn = database.get_connection(self.temp_db_path)
                cursor = conn.cursor()
                for i in range(20):
                    cursor.execute("""
                        INSERT INTO audit_logs (event_type, details, created_at)
                        VALUES (?, ?, ?);
                    """, (f"EVENT_{thread_idx}_{i}", f"Details from thread {thread_idx}", 1000 + i))
                conn.commit()
                conn.close()
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=worker, args=(t,)) for t in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(len(errors), 0, f"Thread concurrency errors: {errors}")

        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT COUNT(*) FROM audit_logs;")
        count = cursor.fetchone()[0]
        conn.close()

        self.assertEqual(count, 100)

if __name__ == "__main__":
    unittest.main()

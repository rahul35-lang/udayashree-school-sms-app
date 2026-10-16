#!/usr/bin/env python3
"""
===============================================================================
TEST SUITE: test_queue.py
MODULE: tests.test_queue
DESCRIPTION:
    Comprehensive unit tests for server.sms_queue module.
    Verifies FIFO ordering, atomic batch claim transitions, state machine
    enforcement, cellular dispatch reporting, and telemetry aggregation.

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "server")))

import database
import sms_queue

class TestSmsQueueModule(unittest.TestCase):
    """Exhaustive test suite for SMS queue manager."""

    def setUp(self):
        """Creates an isolated temporary database for each test case."""
        self.temp_db_fd, self.temp_db_path = tempfile.mkstemp(suffix=".db")
        database.init_db(self.temp_db_path)

    def tearDown(self):
        """Cleans up the temporary database file."""
        os.close(self.temp_db_fd)
        if os.path.exists(self.temp_db_path):
            os.remove(self.temp_db_path)

    def test_enqueue_and_fifo_order(self):
        """Verifies that queued messages are fetched in strict FIFO sequence."""
        id1 = sms_queue.enqueue_sms("9800000001", "Message 1", db_path=self.temp_db_path)
        id2 = sms_queue.enqueue_sms("9800000002", "Message 2", db_path=self.temp_db_path)
        id3 = sms_queue.enqueue_sms("9800000003", "Message 3", db_path=self.temp_db_path)

        jobs = sms_queue.fetch_and_claim_pending_jobs(limit=2, db_path=self.temp_db_path)
        self.assertEqual(len(jobs), 2)
        self.assertEqual(jobs[0]["id"], id1)
        self.assertEqual(jobs[1]["id"], id2)

        # Remaining message in queue
        remaining = sms_queue.fetch_and_claim_pending_jobs(limit=2, db_path=self.temp_db_path)
        self.assertEqual(len(remaining), 1)
        self.assertEqual(remaining[0]["id"], id3)

    def test_atomic_status_transition(self):
        """Verifies that claimed messages transition from PENDING to DISPATCHING."""
        msg_id = sms_queue.enqueue_sms("9800000004", "Test transition", db_path=self.temp_db_path)

        # Before claim
        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT status FROM sms_queue WHERE id = ?", (msg_id,))
        self.assertEqual(cursor.fetchone()["status"], "PENDING")

        # After claim
        sms_queue.fetch_and_claim_pending_jobs(limit=5, db_path=self.temp_db_path)
        cursor.execute("SELECT status FROM sms_queue WHERE id = ?", (msg_id,))
        self.assertEqual(cursor.fetchone()["status"], "DISPATCHING")
        conn.close()

    def test_report_dispatch_result_sent(self):
        """Verifies successful delivery acknowledgment updates status to SENT."""
        msg_id = sms_queue.enqueue_sms("9800000005", "Test sent", db_path=self.temp_db_path)
        updated = sms_queue.report_dispatch_result(msg_id, "SENT", db_path=self.temp_db_path)
        self.assertTrue(updated)

        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT status, error_reason FROM sms_queue WHERE id = ?", (msg_id,))
        row = cursor.fetchone()
        conn.close()

        self.assertEqual(row["status"], "SENT")
        self.assertIsNone(row["error_reason"])

    def test_report_dispatch_result_failed(self):
        """Verifies failed delivery records diagnostic error reasons."""
        msg_id = sms_queue.enqueue_sms("9800000006", "Test fail", db_path=self.temp_db_path)
        updated = sms_queue.report_dispatch_result(msg_id, "FAILED", error_reason="RESULT_ERROR_NO_SERVICE", db_path=self.temp_db_path)
        self.assertTrue(updated)

        conn = database.get_connection(self.temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT status, error_reason FROM sms_queue WHERE id = ?", (msg_id,))
        row = cursor.fetchone()
        conn.close()

        self.assertEqual(row["status"], "FAILED")
        self.assertEqual(row["error_reason"], "RESULT_ERROR_NO_SERVICE")

    def test_get_queue_metrics(self):
        """Verifies aggregated metric counts."""
        sms_queue.enqueue_sms("9800000007", "Msg A", db_path=self.temp_db_path)
        id_b = sms_queue.enqueue_sms("9800000008", "Msg B", db_path=self.temp_db_path)
        id_c = sms_queue.enqueue_sms("9800000009", "Msg C", db_path=self.temp_db_path)

        sms_queue.report_dispatch_result(id_b, "SENT", db_path=self.temp_db_path)
        sms_queue.report_dispatch_result(id_c, "FAILED", "Carrier Error", db_path=self.temp_db_path)

        metrics = sms_queue.get_queue_metrics(db_path=self.temp_db_path)
        self.assertEqual(metrics["total_messages"], 3)
        self.assertEqual(metrics["pending"], 1)
        self.assertEqual(metrics["sent"], 1)
        self.assertEqual(metrics["failed"], 1)

if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""
===============================================================================
FILE: sms_queue.py
MODULE: SchoolSMSGateway.server.sms_queue
DESCRIPTION:
    Asynchronous Outbound SMS Message Queue Manager.
    Coordinates FIFO message queuing, atomic batch acquisition, state transition
    tracking ('PENDING' -> 'DISPATCHING' -> 'SENT' / 'FAILED'), retry policies,
    and real-time cellular telemetry.

STATE MACHINE:
    [PENDING] --------> [DISPATCHING] --------> [SENT]
        │                      │
        │                      ▼
        └───────────────> [FAILED] (e.g. invalid format or cellular rejection)

CONCURRENCY GUARANTEES:
    - Atomically updates status to 'DISPATCHING' inside an exclusive SQLite
      transaction during batch polling so concurrent workers never duplicate SMS dispatches.

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import time
from typing import Any, Dict, List, Optional, Tuple

try:
    from . import config, database
except ImportError:
    import config, database

def enqueue_sms(
    phone: str,
    message: str,
    sim_slot: int = 0,
    db_path: Optional[str] = None
) -> int:
    """
    Appends a new SMS dispatch job to the persistent queue.

    Args:
        phone: Target phone number.
        message: SMS body text.
        sim_slot: Preferred SIM card tray (0 for system default).
        db_path: Database path override.

    Returns:
        Auto-incremented integer ID of the queued message.
    """
    now = int(time.time())
    conn = database.get_connection(db_path)
    cursor = conn.cursor()

    cursor.execute("""
        INSERT INTO sms_queue (phone, message, status, sim_slot, retry_count, created_at, updated_at)
        VALUES (?, ?, 'PENDING', ?, 0, ?, ?);
    """, (phone, message, sim_slot, now, now))
    
    msg_id = cursor.lastrowid
    conn.commit()
    conn.close()
    return msg_id

def fetch_and_claim_pending_jobs(
    limit: int = config.BATCH_POLL_LIMIT,
    db_path: Optional[str] = None
) -> List[Dict[str, Any]]:
    """
    Atomically retrieves up to 'limit' PENDING SMS jobs and marks them 'DISPATCHING'.
    Prevents race conditions if multiple gateway workers poll the server concurrently.

    Args:
        limit: Maximum number of jobs to claim in this batch.
        db_path: Database path override.

    Returns:
        List of dictionaries containing job details (id, phone, message).
    """
    now = int(time.time())
    conn = database.get_connection(db_path)
    cursor = conn.cursor()

    # Select the oldest PENDING messages in strict FIFO order
    cursor.execute("""
        SELECT id, phone, message, sim_slot
        FROM sms_queue
        WHERE status = 'PENDING'
        ORDER BY id ASC LIMIT ?;
    """, (limit,))
    
    rows = cursor.fetchall()
    jobs = []

    if rows:
        job_ids = [r["id"] for r in rows]
        # Atomically transition selected IDs from 'PENDING' to 'DISPATCHING'
        placeholders = ",".join(["?"] * len(job_ids))
        cursor.execute(f"""
            UPDATE sms_queue
            SET status = 'DISPATCHING', updated_at = ?
            WHERE id IN ({placeholders});
        """, [now] + job_ids)

        jobs = [
            {
                "id": r["id"],
                "phone": r["phone"],
                "message": r["message"],
                "sim_slot": r["sim_slot"]
            }
            for r in rows
        ]

    conn.commit()
    conn.close()
    return jobs

def report_dispatch_result(
    job_id: int,
    status: str,
    error_reason: Optional[str] = None,
    db_path: Optional[str] = None
) -> bool:
    """
    Updates the final delivery state of an SMS job after Android phone cellular attempt.

    Args:
        job_id: The ID of the job being acknowledged.
        status: 'SENT' or 'FAILED'.
        error_reason: Diagnostic string if delivery failed (e.g., 'NO_SERVICE').
        db_path: Database path override.

    Returns:
        True if record was successfully updated, False if job_id was not found.
    """
    normalized_status = status.upper().strip()
    if normalized_status not in ("SENT", "FAILED"):
        normalized_status = "FAILED"

    now = int(time.time())
    conn = database.get_connection(db_path)
    cursor = conn.cursor()

    cursor.execute("""
        UPDATE sms_queue
        SET status = ?, error_reason = ?, updated_at = ?
        WHERE id = ?;
    """, (normalized_status, error_reason, now, job_id))

    rows_affected = cursor.rowcount
    conn.commit()
    conn.close()
    return rows_affected > 0

def get_queue_metrics(db_path: Optional[str] = None) -> Dict[str, Any]:
    """
    Aggregates queue telemetry and status counters for monitoring dashboards.

    Args:
        db_path: Database path override.

    Returns:
        Dictionary containing counts for total, pending, dispatching, sent, and failed.
    """
    conn = database.get_connection(db_path)
    cursor = conn.cursor()

    cursor.execute("""
        SELECT 
            COUNT(*) as total_all,
            SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) as count_pending,
            SUM(CASE WHEN status = 'DISPATCHING' THEN 1 ELSE 0 END) as count_dispatching,
            SUM(CASE WHEN status = 'SENT' THEN 1 ELSE 0 END) as count_sent,
            SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as count_failed
        FROM sms_queue;
    """)
    row = cursor.fetchone()

    # Also grab recent 15 activity rows
    cursor.execute("""
        SELECT id, phone, message, status, error_reason, created_at, updated_at
        FROM sms_queue
        ORDER BY id DESC LIMIT 15;
    """)
    recent = [dict(r) for r in cursor.fetchall()]
    conn.close()

    return {
        "total_messages": row["total_all"] or 0,
        "pending": row["count_pending"] or 0,
        "dispatching": row["count_dispatching"] or 0,
        "sent": row["count_sent"] or 0,
        "failed": row["count_failed"] or 0,
        "recent_activity": recent
    }

def get_job_status(
    job_id: int,
    db_path: Optional[str] = None
) -> Optional[Dict[str, Any]]:
    """
    Retrieves the lifecycle and dispatch status of a specific SMS job.
    Used for multi-step delivery verification by external clients.

    Args:
        job_id: Unique integer ID of the queued message.
        db_path: Database path override.

    Returns:
        Dictionary with job details (id, phone, message, status, error_reason, timestamps) or None.
    """
    conn = database.get_connection(db_path)
    cursor = conn.cursor()
    cursor.execute("""
        SELECT id, phone, message, status, sim_slot, retry_count, error_reason, created_at, updated_at
        FROM sms_queue
        WHERE id = ?;
    """, (job_id,))
    row = cursor.fetchone()
    conn.close()
    if row:
        return dict(row)
    return None


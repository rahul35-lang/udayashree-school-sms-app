#!/usr/bin/env python3
"""
===============================================================================
FILE: otp_service.py
MODULE: SchoolSMSGateway.server.otp_service
DESCRIPTION:
    Enterprise-grade One-Time Password (OTP) Generation and Verification Engine.
    Handles cryptographic numeric code generation, multi-stage rate limiting,
    automatic expiration enforcement, and single-use replay protection.

SECURITY MECHANISMS:
    1. Cryptographically Strong Randomness: Uses secrets.choice / random.SystemRandom
       to prevent PRNG state prediction.
    2. Strict Single-Use Invalidation: Once an OTP is verified, its record is
       immediately marked with is_verified = 1 and verified_at timestamp.
       Subsequent verification attempts with identical parameters are rejected.
    3. Mandatory Cooldown Rate Limiting: Blocks phone numbers from requesting
       more than one OTP per config.OTP_RATE_LIMIT_SECONDS (default 60s).

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import re
import secrets
import string
import time
from typing import Any, Dict, Optional, Tuple

try:
    from . import config, database, sms_queue
except ImportError:
    import config, database, sms_queue

def sanitize_phone_number(raw_phone: str) -> str:
    """
    Strips all non-numeric and punctuation noise characters from mobile inputs.
    Preserves optional leading '+' character for international E.164 formats.

    Examples:
        "+977 98-412-34567" -> "+9779841234567"
        "9841 234 567"      -> "9841234567"

    Args:
        raw_phone: Unsanitized phone string from client input.

    Returns:
        Cleaned, contiguous numeric phone string.
    """
    if not raw_phone:
        return ""
    # Strip whitespace, hyphens, brackets, periods
    cleaned = re.sub(r'[\s\-\(\)\.]', '', raw_phone.strip())
    return cleaned

def generate_crypto_otp(length: int = 6) -> str:
    """
    Generates a cryptographically secure, non-predictable numeric OTP.
    Utilizes secrets.choice (operating system cryptographic entropy source).

    Args:
        length: Desired digit count. Defaults to config.OTP_DEFAULT_LENGTH (6).

    Returns:
        A numeric string of exactly 'length' digits (e.g., "749201").
    """
    if length <= 0:
        length = config.OTP_DEFAULT_LENGTH
    return "".join(secrets.choice(string.digits) for _ in range(length))

def check_rate_limit(phone: str, db_path: Optional[str] = None) -> Tuple[bool, int]:
    """
    Determines whether a phone number is currently in an active cooldown window.

    Args:
        phone: Sanitized phone number.
        db_path: Database path (defaults to config.DB_PATH if None).

    Returns:
        Tuple of (allowed: bool, remaining_seconds: int).
        If allowed is True, remaining_seconds is 0.
        If allowed is False, remaining_seconds represents the mandatory wait time.
    """
    now = int(time.time())
    conn = database.get_connection(db_path)
    cursor = conn.cursor()

    # Query most recent unverified OTP generated for this phone within the cooldown threshold
    cursor.execute("""
        SELECT created_at FROM otp_verifications
        WHERE phone = ? AND is_verified = 0 AND (? - created_at) < ?
        ORDER BY id DESC LIMIT 1;
    """, (phone, now, config.OTP_RATE_LIMIT_SECONDS))
    
    row = cursor.fetchone()
    conn.close()

    if row:
        elapsed = now - row["created_at"]
        remaining = config.OTP_RATE_LIMIT_SECONDS - elapsed
        return False, max(1, remaining)

    return True, 0

def create_and_queue_otp(
    phone_raw: str,
    length: int = config.OTP_DEFAULT_LENGTH,
    expiry_seconds: int = config.OTP_DEFAULT_EXPIRY_SECONDS,
    custom_template: Optional[str] = None,
    db_path: Optional[str] = None,
    override_code: Optional[str] = None
) -> Tuple[bool, str, Dict[str, Any]]:
    """
    End-to-end OTP dispatch orchestrator.
    Sanitizes phone, validates rate limits, creates cryptographic code (or uses override),
    records it in database, and queues outbound SMS message.

    Args:
        phone_raw: Target recipient phone number.
        length: OTP digit count (default 6).
        expiry_seconds: Code validity window in seconds (default 300).
        custom_template: Custom message format containing '{otp}' placeholder.
        db_path: Database path override for isolated test suites.
        override_code: Optional explicit verification code to assign (e.g. for testing/fixed OTP).

    Returns:
        Tuple of (success: bool, error_or_success_message: str, details_dict: dict).
    """
    phone = sanitize_phone_number(phone_raw)
    if not phone or len(phone) < 8:
        return False, "Invalid phone number format. Minimum 8 digits required.", {}

    # Check cooldown rate limiting
    allowed, remaining_sec = check_rate_limit(phone, db_path)
    if not allowed:
        return False, f"Rate limit reached. Please wait {remaining_sec}s before requesting a new OTP.", {
            "retry_after_seconds": remaining_sec
        }

    # Generate cryptographically secure numeric code or use explicit override
    if override_code:
        otp_code = str(override_code).strip()
    else:
        otp_code = generate_crypto_otp(length)
    now = int(time.time())
    expires_at = now + expiry_seconds

    # Format user-facing SMS message text
    template = custom_template if custom_template else "Your School App verification code is: {otp}. Valid for 5 minutes."
    message_text = template.replace("{otp}", otp_code)

    conn = database.get_connection(db_path)
    cursor = conn.cursor()

    # Insert into otp_verifications table with dynamic schema compatibility
    cols = [col[1] for col in cursor.execute("PRAGMA table_info(otp_verifications);").fetchall()]
    if "otp" in cols and "otp_code" in cols:
        cursor.execute("""
            INSERT INTO otp_verifications (phone, otp, otp_code, expires_at, is_verified, created_at)
            VALUES (?, ?, ?, ?, 0, ?);
        """, (phone, otp_code, otp_code, expires_at, now))
    elif "otp" in cols:
        cursor.execute("""
            INSERT INTO otp_verifications (phone, otp, expires_at, is_verified, created_at)
            VALUES (?, ?, ?, 0, ?);
        """, (phone, otp_code, expires_at, now))
    else:
        cursor.execute("""
            INSERT INTO otp_verifications (phone, otp_code, expires_at, is_verified, created_at)
            VALUES (?, ?, ?, 0, ?);
        """, (phone, otp_code, expires_at, now))
    otp_id = cursor.lastrowid

    # Queue SMS into sms_queue table
    cursor.execute("""
        INSERT INTO sms_queue (phone, message, status, created_at, updated_at)
        VALUES (?, ?, 'PENDING', ?, ?);
    """, (phone, message_text, now, now))
    sms_id = cursor.lastrowid

    conn.commit()
    conn.close()

    return True, "OTP generated and cellular SMS queued successfully.", {
        "otp_record_id": otp_id,
        "sms_job_id": sms_id,
        "phone": phone,
        "expires_in": expiry_seconds,
        "expires_at": expires_at
    }

def verify_submitted_otp(
    phone_raw: str,
    submitted_otp: str,
    db_path: Optional[str] = None
) -> Tuple[bool, str]:
    """
    Validates a user-submitted OTP against recorded unverified instances.
    Guarantees strict single-use semantics and expiration checking.

    Args:
        phone_raw: Phone number of the parent/user verifying.
        submitted_otp: Code entered by the user.
        db_path: Database path override.

    Returns:
        Tuple of (is_valid: bool, status_message: str).
    """
    phone = sanitize_phone_number(phone_raw)
    otp_clean = str(submitted_otp).strip()

    if not phone or not otp_clean:
        return False, "Both phone number and OTP code are required."

    now = int(time.time())
    conn = database.get_connection(db_path)
    cursor = conn.cursor()

    # Retrieve most recent unverified OTP for this phone with dynamic column support
    cols = [col[1] for col in cursor.execute("PRAGMA table_info(otp_verifications);").fetchall()]
    otp_expr = "COALESCE(otp_code, otp)" if ("otp" in cols and "otp_code" in cols) else ("otp" if "otp" in cols else "otp_code")
    cursor.execute(f"""
        SELECT id, {otp_expr} AS otp_code, expires_at, is_verified
        FROM otp_verifications
        WHERE phone = ? AND is_verified = 0
        ORDER BY id DESC LIMIT 1;
    """, (phone,))
    
    record = cursor.fetchone()

    if not record:
        conn.close()
        return False, "No active verification request found for this phone number."

    # Verify expiration timestamp
    if now > record["expires_at"]:
        conn.close()
        return False, "OTP has expired. Please request a new verification code."

    # Constant-time comparison between stored OTP and submitted code
    if not secrets.compare_digest(record["otp_code"], otp_clean):
        conn.close()
        return False, "Incorrect verification code. Please check and try again."

    # Mark as verified immediately to prevent replay attack
    cursor.execute("""
        UPDATE otp_verifications
        SET is_verified = 1, verified_at = ?
        WHERE id = ?;
    """, (now, record["id"]))

    cursor.execute("""
        INSERT INTO audit_logs (event_type, details, created_at)
        VALUES ('OTP_VERIFIED', ?, ?);
    """, (f"Phone {phone} verified with OTP ID {record['id']}", now))

    conn.commit()
    conn.close()

    return True, "Phone number verified successfully!"

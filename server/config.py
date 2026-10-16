#!/usr/bin/env python3
"""
===============================================================================
FILE: config.py
MODULE: SchoolSMSGateway.server.config
DESCRIPTION:
    Centralized configuration repository for the SMS & OTP Gateway Server.
    All environment-variable overrides, network defaults, database file paths,
    shared secret handshake tokens, and cryptographic constants are defined here.

SECURITY CONSIDERATIONS:
    - In production, GATEWAY_SECRET_TOKEN MUST be passed via environment variable
      or stored in a secure secret store, NOT left as the default string.
    - All network binding defaults default to 0.0.0.0 to accept traffic both
      locally and through Cloudflare Tunnel reverse proxies.

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import os
import sys

# =============================================================================
# NETWORK & BINDING CONFIGURATION
# =============================================================================
# HOST: IP interface to bind server on. "0.0.0.0" means all local and network
# interfaces, including localhost, LAN interfaces, and cloudflare tunnels.
HOST = os.environ.get("GATEWAY_HOST", "0.0.0.0")

# PRIMARY_PORT: Default HTTP listening port. Port 8080 is mapped directly by
# cloudflared tunnel (sms.success0.com.np -> localhost:8080).
PRIMARY_PORT = int(os.environ.get("GATEWAY_PORT", 8080))

# SECONDARY_PORT: Fallback or secondary port (e.g. 8000) so both ports can
# receive requests concurrently for backward compatibility with local scripts.
SECONDARY_PORT = int(os.environ.get("GATEWAY_PORT_SECONDARY", 8000))

# =============================================================================
# AUTHENTICATION & HANDSHAKE CONSTANTS
# =============================================================================
# GATEWAY_SECRET_TOKEN: The master pre-shared key (PSK) shared between the
# 24/7 central server and the Android Gateway Mobile Phone.
# All handshake and polling requests MUST provide this token in headers or params.
GATEWAY_SECRET_TOKEN = os.environ.get(
    "GATEWAY_SECRET_TOKEN",
    "Udaya@momphone"
)

# =============================================================================
# PERSISTENCE & DATABASE CONFIGURATION
# =============================================================================
# BASE_DIR: Absolute directory containing this config file.
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# DB_PATH: Absolute path to the SQLite database file. By default, stored in
# the server directory as 'sms_gateway.db'. Can be overridden by environment.
DB_PATH = os.environ.get("GATEWAY_DB_PATH", os.path.join(BASE_DIR, "sms_gateway.db"))

# =============================================================================
# OTP BUSINESS LOGIC CONSTANTS
# =============================================================================
# OTP_DEFAULT_LENGTH: Number of numeric decimal digits in generated verification code.
OTP_DEFAULT_LENGTH = 6

# OTP_DEFAULT_EXPIRY_SECONDS: Lifespan of an unverified OTP before it expires (5 minutes).
OTP_DEFAULT_EXPIRY_SECONDS = 300

# OTP_RATE_LIMIT_SECONDS: Minimum time a user/phone must wait before requesting
# a new OTP. Prevents SMS flooding, cellular cost abuse, and SMS spamming.
OTP_RATE_LIMIT_SECONDS = 60

# =============================================================================
# HEARTBEAT & LIVENESS CONSTANTS
# =============================================================================
# HEARTBEAT_TIMEOUT_SECONDS: Maximum elapsed time since last phone ping before
# the gateway status is marked as 'OFFLINE' in dashboard and health endpoints.
HEARTBEAT_TIMEOUT_SECONDS = 45

# BATCH_POLL_LIMIT: Maximum number of queued SMS messages returned to the
# Android device in a single polling loop iteration to prevent cellular buffer choke.
BATCH_POLL_LIMIT = 5

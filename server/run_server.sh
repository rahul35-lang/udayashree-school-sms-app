#!/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PORT="${PORT:-8000}"
export GATEWAY_API_KEY="${GATEWAY_API_KEY:-school_gateway_secret_2026}"
echo "Starting School SMS & OTP Gateway on port $PORT..."
python3 "$DIR/server.py"

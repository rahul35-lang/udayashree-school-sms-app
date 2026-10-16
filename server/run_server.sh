#!/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PORT="${PORT:-8000}"
export GATEWAY_API_KEY="${GATEWAY_API_KEY:-Udaya@momphone}"
echo "Starting School SMS & OTP Gateway on port $PORT..."
python3 "$DIR/server.py"

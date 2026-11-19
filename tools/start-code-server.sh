#!/usr/bin/env bash
# Starts the code-server that this app connects to, inside the DSH Ubuntu environment.
# Address for the app:  http://127.0.0.1:8080/?folder=/sdcard/Projects
set -euo pipefail
CS="${CS:-/opt/cs/code-server-4.96.4-linux-arm64/bin/code-server}"
FOLDER="${FOLDER:-/sdcard/Projects}"
PORT="${PORT:-8080}"

if pgrep -f "code-server.*--bind-addr" >/dev/null 2>&1; then
  echo "code-server already running on port $PORT"
  exit 0
fi

echo "starting code-server on http://127.0.0.1:$PORT (workspace: $FOLDER)"
nohup setsid "$CS" --auth none --bind-addr "127.0.0.1:$PORT" --disable-telemetry "$FOLDER" \
  > /tmp/code-server.log 2>&1 < /dev/null &
sleep 6
echo "done. log: /tmp/code-server.log"

#!/bin/sh
#
# Stop script for the phoss-ap Peppol Access Point daemon.
#
# Counterpart to start-phoss-ap.sh: reads the PID file written on startup,
# asks the JVM to shut down gracefully (SIGTERM) and only escalates to
# SIGKILL if it does not exit within the grace period.
#

# --- Configuration (must match start-phoss-ap.sh; override via environment) --
APP_HOME="${APP_HOME:-/opt/peppol-ap}"
PID_DIR="${PID_DIR:-$APP_HOME/pid}"
PID_FILE="${PID_FILE:-$PID_DIR/phoss-ap.pid}"
# Seconds to wait for a graceful shutdown before forcing a kill
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"

cd "$APP_HOME" 2>/dev/null || true

PID=`cat "$PID_FILE" 2>/dev/null`

if [ -z "$PID" ]; then
  echo "No PID file at $PID_FILE - phoss-ap does not appear to be running."
  exit 0
fi

if ! kill -0 "$PID" 2>/dev/null; then
  echo "Process $PID not running - removing stale PID file."
  rm -f "$PID_FILE" 2>/dev/null
  exit 0
fi

echo "Stopping phoss-ap (PID $PID) ..."
kill "$PID" 2>/dev/null

# Wait for graceful shutdown
i=0
while [ "$i" -lt "$STOP_TIMEOUT" ]; do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "phoss-ap stopped."
    rm -f "$PID_FILE" 2>/dev/null
    exit 0
  fi
  i=$((i + 1))
  sleep 1
done

# Still alive -> force kill
echo "phoss-ap did not stop within ${STOP_TIMEOUT}s - sending SIGKILL to $PID"
kill -9 "$PID" 2>/dev/null
rm -f "$PID_FILE" 2>/dev/null
echo "phoss-ap killed."

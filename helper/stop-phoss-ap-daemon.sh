#!/bin/sh
#
# Stop script for the phoss-ap Peppol Access Point *systemd service*.
#
# Thin, friendly wrapper around "systemctl stop $SERVICE_NAME" that
#   1. verifies the unit is actually installed,
#   2. is idempotent - stopping an already stopped service is a no-op,
#   3. waits until the JVM is really gone and reports if systemd had to kill it
#      (the unit allows TimeoutStopSec=30 for a graceful Spring Boot shutdown).
#
# It only stops the service - it stays enabled and will come back on the next
# boot. Set DISABLE=1 to also "systemctl disable" it.
#
# Note: this is the systemd counterpart of stop-phoss-ap.sh, which kills the
# PID from $APP_HOME/pid and does NOT involve systemd.
#
# Must be run as root (systemctl stop).
# Counterpart: start-phoss-ap-daemon.sh
#

set -e

# --- Configuration (must match install-phoss-ap-daemon.sh) ------------------
SERVICE_NAME="${SERVICE_NAME:-phoss-ap}"
# Seconds to wait for the service to become inactive
STOP_TIMEOUT="${STOP_TIMEOUT:-45}"
# 1 = also disable the service (no start on boot)
DISABLE="${DISABLE:-0}"
# Number of journal lines to show when the stop fails
LOG_LINES="${LOG_LINES:-40}"

# --- Require root -----------------------------------------------------------
if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: stopping a systemd service requires root." >&2
  echo "       Retry with: sudo $0" >&2
  exit 1
fi

# --- Verify the unit exists --------------------------------------------------
if ! systemctl cat "$SERVICE_NAME" >/dev/null 2>&1; then
  echo "ERROR: no systemd unit '${SERVICE_NAME}.service' found - nothing to stop." >&2
  echo "       (Set SERVICE_NAME=... if the service was installed under another name.)" >&2
  exit 1
fi

# --- Stop --------------------------------------------------------------------
if systemctl is-active --quiet "$SERVICE_NAME"; then
  echo "Stopping '$SERVICE_NAME' (graceful shutdown, up to ${STOP_TIMEOUT}s) ..."
  # systemctl stop blocks until the unit is down or TimeoutStopSec elapsed; the
  # loop below is the safety net for the "still shutting down" corner case.
  systemctl stop "$SERVICE_NAME" || true

  i=0
  while [ "$i" -lt "$STOP_TIMEOUT" ]; do
    systemctl is-active --quiet "$SERVICE_NAME" || break
    i=$((i + 1))
    sleep 1
  done

  if systemctl is-active --quiet "$SERVICE_NAME"; then
    echo "ERROR: '$SERVICE_NAME' is still active after ${STOP_TIMEOUT}s." >&2
    systemctl --no-pager --lines=0 status "$SERVICE_NAME" >&2 || true
    echo "" >&2
    journalctl -u "$SERVICE_NAME" --no-pager --lines="$LOG_LINES" >&2 || true
    echo "" >&2
    echo "       Force it with: systemctl kill -s SIGKILL $SERVICE_NAME" >&2
    exit 1
  fi
  echo "'$SERVICE_NAME' stopped."
else
  echo "Service '$SERVICE_NAME' is not running - nothing to stop."
fi

# --- Report a failed state ---------------------------------------------------
# A crashed unit stays in "failed" and blocks nothing, but it is worth showing.
if systemctl is-failed --quiet "$SERVICE_NAME" 2>/dev/null; then
  echo ""
  echo "NOTE: '$SERVICE_NAME' is in the 'failed' state - last log lines:"
  journalctl -u "$SERVICE_NAME" --no-pager --lines="$LOG_LINES" || true
  echo ""
  echo "Clear it with: systemctl reset-failed $SERVICE_NAME"
fi

# --- Optional: disable on boot ------------------------------------------------
if [ "$DISABLE" = "1" ]; then
  if systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
    echo "Disabling '$SERVICE_NAME' (no start on boot) ..."
    systemctl disable "$SERVICE_NAME"
  else
    echo "Service '$SERVICE_NAME' is already disabled."
  fi
else
  echo "The service is still enabled and will start again on the next boot"
  echo "(re-run with DISABLE=1 to turn that off)."
fi

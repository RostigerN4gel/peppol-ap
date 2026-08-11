#!/bin/sh
#
# Start script for the phoss-ap Peppol Access Point *systemd service*.
#
# Thin, friendly wrapper around "systemctl start $SERVICE_NAME" that
#   1. verifies the unit is actually installed (and names look-alike units if not),
#   2. is idempotent - an already running service is reported, not restarted,
#   3. waits until the service has settled and dumps the last journal lines if
#      the JVM died right after startup (a Type=simple unit counts as "active"
#      the moment it is forked, long before Spring Boot is up).
#
# Note: this is the systemd counterpart of start-phoss-ap.sh, which starts the
# jar directly via a PID file and does NOT involve systemd. Use this one if the
# service was installed with install-phoss-ap-daemon.sh.
#
# Must be run as root (systemctl start).
# Counterpart: stop-phoss-ap-daemon.sh
#

set -e

# --- Configuration (must match install-phoss-ap-daemon.sh) ------------------
SERVICE_NAME="${SERVICE_NAME:-phoss-ap}"
# Seconds to watch the service after "systemctl start" before declaring success
WAIT_TIMEOUT="${WAIT_TIMEOUT:-15}"
# 1 = follow the journal after a successful start (Ctrl-C to detach)
FOLLOW="${FOLLOW:-0}"
# Number of journal lines to show when the start fails
LOG_LINES="${LOG_LINES:-40}"

# Also accept "-f" / "--follow" as the first argument
case "$1" in
  -f | --follow) FOLLOW=1 ;;
esac

# --- Require root -----------------------------------------------------------
if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: starting a systemd service requires root." >&2
  echo "       Retry with: sudo $0" >&2
  exit 1
fi

# --- Verify the unit exists --------------------------------------------------
if ! systemctl cat "$SERVICE_NAME" >/dev/null 2>&1; then
  echo "ERROR: no systemd unit '${SERVICE_NAME}.service' found." >&2
  echo "       Install it first: sudo ./install-phoss-ap-daemon.sh" >&2
  # Mind the naming: $APP_HOME defaults to /opt/peppol-ap while the service is
  # called phoss-ap - so list whatever look-alike units are registered.
  OTHER=$(systemctl list-unit-files --no-legend --no-pager 2>/dev/null |
          awk '{print $1}' | grep -Ei 'phoss|peppol' || true)
  if [ -n "$OTHER" ]; then
    echo "       Similar units on this host (maybe you meant one of these?):" >&2
    echo "$OTHER" | sed 's/^/         /' >&2
  fi
  exit 1
fi

# --- Already running? --------------------------------------------------------
if systemctl is-active --quiet "$SERVICE_NAME"; then
  echo "Service '$SERVICE_NAME' is already running."
  systemctl --no-pager --lines=0 status "$SERVICE_NAME" || true
  exit 0
fi

# --- Start -------------------------------------------------------------------
echo "Starting '$SERVICE_NAME' ..."
if ! systemctl start "$SERVICE_NAME"; then
  echo "ERROR: 'systemctl start $SERVICE_NAME' failed." >&2
  journalctl -u "$SERVICE_NAME" --no-pager --lines="$LOG_LINES" >&2 || true
  exit 1
fi

# --- Watch it settle ---------------------------------------------------------
# The unit is Type=simple, so it is "active" immediately. Give the JVM a moment
# and make sure it does not fall over during startup (bad config, port in use,
# DB unreachable, ...).
i=0
while [ "$i" -lt "$WAIT_TIMEOUT" ]; do
  if ! systemctl is-active --quiet "$SERVICE_NAME"; then
    echo "ERROR: '$SERVICE_NAME' stopped again $i second(s) after the start." >&2
    echo "       State: $(systemctl is-active "$SERVICE_NAME" 2>/dev/null)" >&2
    echo "" >&2
    journalctl -u "$SERVICE_NAME" --no-pager --lines="$LOG_LINES" >&2 || true
    exit 1
  fi
  i=$((i + 1))
  sleep 1
done

echo ""
systemctl --no-pager --lines=0 status "$SERVICE_NAME" || true
echo ""
echo "=== '$SERVICE_NAME' is running (survived ${WAIT_TIMEOUT}s) ==="
echo "Note: 'active' only means the JVM is alive - watch the log for the"
echo "      Spring Boot 'Started PhossAPApplication' line:"
echo "  journalctl -u $SERVICE_NAME -f"

if [ "$FOLLOW" = "1" ]; then
  echo ""
  echo "Following the journal (Ctrl-C to detach; the service keeps running) ..."
  exec journalctl -u "$SERVICE_NAME" -f
fi

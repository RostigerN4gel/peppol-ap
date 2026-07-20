#!/bin/sh
#
# Uninstall script for the phoss-ap Peppol Access Point systemd daemon.
#
# What it does:
#   1. Stops the service if running and disables it (no start on boot).
#   2. Removes /etc/systemd/system/$SERVICE_NAME.service and reloads systemd.
#   3. Removes the deployed jar(s) and the stable symlink from $APP_HOME.
#
# By default it leaves $APP_HOME and the logs intact.
# Set PURGE=1 to also remove $APP_HOME entirely.
#
# The service user/group (default: ec2-user) is never touched - this script
# neither creates nor deletes it (it is a pre-existing shared account).
#
# Must be run as root. Idempotent - safe to run even if nothing is installed.
# Counterpart: install-phoss-ap-daemon.sh
#

set -e

# --- Configuration (must match install-phoss-ap-daemon.sh) ------------------
APP_HOME="${APP_HOME:-/opt/peppol-ap}"
SERVICE_NAME="${SERVICE_NAME:-phoss-ap}"
SERVICE_USER="${SERVICE_USER:-ec2-user}"
PURGE="${PURGE:-0}"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

# --- Require root -----------------------------------------------------------
if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: this uninstaller must be run as root." >&2
  echo "       Retry with: sudo $0" >&2
  exit 1
fi

# --- Stop and disable the service -------------------------------------------
if systemctl list-unit-files "${SERVICE_NAME}.service" >/dev/null 2>&1 &&
   systemctl cat "$SERVICE_NAME" >/dev/null 2>&1; then
  if systemctl is-active --quiet "$SERVICE_NAME"; then
    echo "Stopping $SERVICE_NAME ..."
    systemctl stop "$SERVICE_NAME"
  fi
  if systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
    echo "Disabling $SERVICE_NAME ..."
    systemctl disable "$SERVICE_NAME"
  fi
else
  echo "Service '$SERVICE_NAME' not registered - skipping stop/disable."
fi

# --- Remove the unit file ----------------------------------------------------
if [ -f "$UNIT_FILE" ]; then
  echo "Removing $UNIT_FILE"
  rm -f "$UNIT_FILE"
fi
systemctl daemon-reload
systemctl reset-failed "$SERVICE_NAME" 2>/dev/null || true

# --- Remove deployed artifacts ----------------------------------------------
if [ -L "$APP_HOME/$SERVICE_NAME.jar" ] || [ -e "$APP_HOME/$SERVICE_NAME.jar" ]; then
  echo "Removing symlink $APP_HOME/$SERVICE_NAME.jar"
  rm -f "$APP_HOME/$SERVICE_NAME.jar"
fi
for f in "$APP_HOME"/phoss-ap-webapp-*.jar; do
  [ -f "$f" ] || continue
  echo "Removing deployed jar $f"
  rm -f "$f"
done

# --- Optional purge ----------------------------------------------------------
# Note: the service user/group is a shared, pre-existing account and is never
# removed, not even with PURGE=1.
if [ "$PURGE" = "1" ]; then
  if [ -d "$APP_HOME" ]; then
    echo "PURGE: removing $APP_HOME"
    rm -rf "$APP_HOME"
  fi
fi

echo ""
echo "=== Uninstall complete ==="
if [ "$PURGE" = "1" ]; then
  echo "Service, deployed jars and $APP_HOME removed. User '$SERVICE_USER' kept (shared account)."
else
  echo "Service and deployed jars removed. $APP_HOME and logs kept."
  echo "Re-run with PURGE=1 to remove $APP_HOME as well."
fi

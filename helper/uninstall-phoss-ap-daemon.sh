#!/bin/sh
#
# Uninstall script for the phoss-ap Peppol Access Point systemd daemon.
#
# What it does:
#   1. Stops the service if running and disables it (no start on boot).
#   2. Removes /etc/systemd/system/$SERVICE_NAME.service and reloads systemd.
#   3. Removes the deployed jar(s) and the stable symlink from $APP_HOME.
#
# By default it leaves $APP_HOME, the logs and the service user intact.
# Set PURGE=1 to also remove $APP_HOME entirely and delete the service user.
#
# Must be run as root. Idempotent - safe to run even if nothing is installed.
# Counterpart: install-phoss-ap-daemon.sh
#

set -e

# --- Configuration (must match install-phoss-ap-daemon.sh) ------------------
APP_HOME="${APP_HOME:-/opt/tomcat}"
SERVICE_NAME="${SERVICE_NAME:-phoss-ap}"
SERVICE_USER="${SERVICE_USER:-tomcat}"
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
if [ "$PURGE" = "1" ]; then
  if [ -d "$APP_HOME" ]; then
    echo "PURGE: removing $APP_HOME"
    rm -rf "$APP_HOME"
  fi
  if id "$SERVICE_USER" >/dev/null 2>&1; then
    echo "PURGE: deleting service user '$SERVICE_USER'"
    userdel "$SERVICE_USER" 2>/dev/null || true
  fi
fi

echo ""
echo "=== Uninstall complete ==="
if [ "$PURGE" = "1" ]; then
  echo "Service, deployed jars, $APP_HOME and user '$SERVICE_USER' removed."
else
  echo "Service and deployed jars removed. $APP_HOME, logs and user '$SERVICE_USER' kept."
  echo "Re-run with PURGE=1 to remove those as well."
fi

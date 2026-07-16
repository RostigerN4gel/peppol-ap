#!/bin/sh
#
# Install script for the phoss-ap Peppol Access Point as a systemd daemon.
#
# What it does:
#   1. Resolves the runnable fat jar (arg, $APP_JAR, or newest phoss-ap-webapp-*.jar
#      found next to this script / in ../dist / in the current directory).
#   2. Creates a dedicated system user/group (default: tomcat) if missing.
#   3. Copies the jar into $APP_HOME and points a stable symlink
#      "$APP_HOME/$SERVICE_NAME.jar" at it.
#   4. Writes /etc/systemd/system/$SERVICE_NAME.service.
#   5. Runs "systemctl daemon-reload" and "systemctl enable" (start on boot).
#
# It deliberately does NOT start the service - start it manually with
#   systemctl start phoss-ap
#
# Must be run as root (systemd unit, /opt/tomcat, user creation).
# Counterpart: uninstall-phoss-ap-daemon.sh
#

set -e

# --- Locate repo/helper dir (this script lives in <repo>/helper) ------------
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# --- Configuration (override via environment) -------------------------------
APP_HOME="${APP_HOME:-/opt/tomcat}"
SERVICE_NAME="${SERVICE_NAME:-phoss-ap}"
SERVICE_USER="${SERVICE_USER:-tomcat}"
SERVICE_GROUP="${SERVICE_GROUP:-$SERVICE_USER}"
# Spring profile whose "application-<profile>.properties" gets loaded
SPRING_PROFILE="${SPRING_PROFILE:-dev}"
JAVA_OPTS="${JAVA_OPTS:--Djava.security.egd=file:///dev/urandom -XX:MaxRAMPercentage=80}"
# Explicit jar to install; auto-detected if empty (may also be passed as $1)
APP_JAR="${APP_JAR:-$1}"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

# --- Require root -----------------------------------------------------------
if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: this installer must be run as root (systemd unit + $APP_HOME + user creation)." >&2
  echo "       Retry with: sudo $0" >&2
  exit 1
fi

# --- Resolve Java and require JDK 21+ ---------------------------------------
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java || true)"
fi
if [ -z "$JAVA" ]; then
  echo "ERROR: no Java runtime found (set JAVA_HOME or put java on PATH). phoss-ap requires JDK 21+." >&2
  exit 1
fi
JAVA_VER=$("$JAVA" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/')
if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
  echo "ERROR: JDK 21+ required, but '$JAVA' reports major version '$JAVA_VER'." >&2
  exit 1
fi

# --- Resolve the jar to install ---------------------------------------------
if [ -z "$APP_JAR" ]; then
  # Search, in order: <helper>/../dist, <helper>, current working directory.
  for d in "$SCRIPT_DIR/../dist" "$SCRIPT_DIR" "$PWD"; do
    for f in "$d"/phoss-ap-webapp-*.jar; do
      # Skip the unexpanded glob (no match) and the auxiliary artifacts.
      [ -f "$f" ] || continue
      case "$f" in
        *-sources.jar | *-javadoc.jar | *.jar.original) continue ;;
      esac
      if [ -z "$APP_JAR" ] || [ "$f" -nt "$APP_JAR" ]; then
        APP_JAR="$f"
      fi
    done
    [ -n "$APP_JAR" ] && break
  done
fi
if [ -z "$APP_JAR" ] || [ ! -f "$APP_JAR" ]; then
  echo "ERROR: no runnable jar found. Pass it explicitly:" >&2
  echo "       $0 /path/to/phoss-ap-webapp-<version>.jar" >&2
  exit 1
fi
# Absolutize
APP_JAR=$(CDPATH= cd -- "$(dirname -- "$APP_JAR")" && pwd)/$(basename -- "$APP_JAR")
JAR_BASENAME=$(basename -- "$APP_JAR")

echo "Using Java  : $JAVA (major $JAVA_VER)"
echo "Installing  : $APP_JAR"
echo "Service     : $SERVICE_NAME (user $SERVICE_USER:$SERVICE_GROUP, profile $SPRING_PROFILE)"
echo "App home    : $APP_HOME"

# --- Create service group/user if missing -----------------------------------
if ! getent group "$SERVICE_GROUP" >/dev/null 2>&1; then
  echo "Creating system group '$SERVICE_GROUP'"
  groupadd --system "$SERVICE_GROUP"
fi
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  echo "Creating system user '$SERVICE_USER'"
  NOLOGIN="$(command -v nologin || echo /usr/sbin/nologin)"
  useradd --system --gid "$SERVICE_GROUP" --home-dir "$APP_HOME" \
          --no-create-home --shell "$NOLOGIN" "$SERVICE_USER"
fi

# --- Deploy the jar ----------------------------------------------------------
mkdir -p "$APP_HOME" "$APP_HOME/logs"
cp -f "$APP_JAR" "$APP_HOME/$JAR_BASENAME"
ln -sfn "$APP_HOME/$JAR_BASENAME" "$APP_HOME/$SERVICE_NAME.jar"
chown "$SERVICE_USER:$SERVICE_GROUP" "$APP_HOME" "$APP_HOME/logs" \
      "$APP_HOME/$JAR_BASENAME" "$APP_HOME/$SERVICE_NAME.jar"

# --- Write the systemd unit --------------------------------------------------
echo "Writing $UNIT_FILE"
cat > "$UNIT_FILE" <<EOF
[Unit]
Description=phoss-ap Peppol Access Point
Documentation=https://github.com/phax/phoss-ap
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_GROUP
WorkingDirectory=$APP_HOME
# Optional operator overrides (e.g. PHOSSAP_JDBC_URL=...); '-' => file is optional.
EnvironmentFile=-$APP_HOME/$SERVICE_NAME.env
ExecStart=$JAVA $JAVA_OPTS -jar $APP_HOME/$SERVICE_NAME.jar --spring.profiles.active=$SPRING_PROFILE
# Spring Boot exits with 143 (128+SIGTERM) on a clean shutdown.
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
EOF
chmod 644 "$UNIT_FILE"

# --- Register with systemd (enable on boot, but do NOT start) ----------------
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"

echo ""
echo "=== Install complete ==="
echo "Service '$SERVICE_NAME' is enabled (starts on boot) but NOT started yet."
echo ""
echo "Start it manually:"
echo "  systemctl start $SERVICE_NAME"
echo "Check status / logs:"
echo "  systemctl status $SERVICE_NAME"
echo "  journalctl -u $SERVICE_NAME -f"

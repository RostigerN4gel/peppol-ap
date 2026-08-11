#!/bin/sh
#
# Start script for the phoss-ap Peppol Access Point (Spring Boot daemon).
#
# Replaces the old phase4-peppol-standalone start script. Differences vs. phase4:
#   - phoss-ap is a Spring Boot "fat jar" (phoss-ap-webapp-<version>.jar).
#   - Configuration is NOT passed via -Dconfig.file / --spring.config.location.
#     phoss-ap uses the "dev" Spring profile to load "application-dev.properties"
#     (baked into the jar via SpringProfileConfigIntegration -> ph-config).
#     Individual values can still be overridden through OS environment variables
#     (e.g. PHOSSAP_JDBC_URL) or an application.properties in the working directory.
#

set -e

# --- Configuration (override via environment if needed) ---------------------
APP_HOME="${APP_HOME:-/opt/peppol-ap}"
# Spring profile whose "application-<profile>.properties" gets loaded
SPRING_PROFILE="${SPRING_PROFILE:-dev}"
# Location of the runnable jar. Auto-detected if not set explicitly.
APP_JAR="${APP_JAR:-}"
PID_DIR="${PID_DIR:-$APP_HOME/pid}"
PID_FILE="${PID_FILE:-$PID_DIR/phoss-ap.pid}"
LOG_DIR="${LOG_DIR:-$APP_HOME/logs}"
LOG_FILE="${LOG_FILE:-$LOG_DIR/phoss-ap.out}"
JAVA_OPTS="${JAVA_OPTS:--Djava.security.egd=file:///dev/urandom -XX:MaxRAMPercentage=80}"

# --- Resolve Java -----------------------------------------------------------
# Same probe AND same precedence as install-phoss-ap-daemon.sh, so that a manual
# start runs on the very JVM the systemd service was installed with:
#   $JAVA_HOME -> the service-private JDK in $JDK_DIR -> "java" on the PATH.
# The installer only provisions $JDK_DIR when the host has no suitable system
# Java, so its presence means "this service is meant to run on it" - it therefore
# outranks a java that may have appeared on the PATH later (which would otherwise
# silently diverge from the absolute path baked into the systemd unit).
# Set PREFER_PRIVATE_JDK=0 for the plain $JAVA_HOME -> PATH -> $JDK_DIR order.
# The system Java is never modified; this script only picks which binary to exec.
REQUIRED_JAVA_MAJOR="${REQUIRED_JAVA_MAJOR:-21}"
JDK_DIR="${JDK_DIR:-$APP_HOME/jdk}"
PREFER_PRIVATE_JDK="${PREFER_PRIVATE_JDK:-1}"

# True if the given java binary is at least $REQUIRED_JAVA_MAJOR.
java_is_suitable ()
{
  jis_bin="$1"
  [ -n "$jis_bin" ] && [ -x "$jis_bin" ] || return 1
  # Handles "21", "21.0.11" and legacy "1.8.x"
  jis_ver=$("$jis_bin" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/')
  case "$jis_ver" in
    '' | *[!0-9]*) return 1 ;;
  esac
  [ "$jis_ver" -ge "$REQUIRED_JAVA_MAJOR" ]
}

JAVA=""
JAVA_SOURCE=""
if [ -n "$JAVA_HOME" ] && java_is_suitable "$JAVA_HOME/bin/java"; then
  JAVA="$JAVA_HOME/bin/java"
  JAVA_SOURCE="JAVA_HOME"
fi
if [ -z "$JAVA" ] && [ "$PREFER_PRIVATE_JDK" = "1" ] && java_is_suitable "$JDK_DIR/bin/java"; then
  JAVA="$JDK_DIR/bin/java"
  JAVA_SOURCE="service-private JDK"
fi
if [ -z "$JAVA" ]; then
  PATH_JAVA="$(command -v java || true)"
  if java_is_suitable "$PATH_JAVA"; then
    JAVA="$PATH_JAVA"
    JAVA_SOURCE="PATH"
  fi
fi
# Reached only with PREFER_PRIVATE_JDK=0 (the probe above already covered it).
if [ -z "$JAVA" ] && java_is_suitable "$JDK_DIR/bin/java"; then
  JAVA="$JDK_DIR/bin/java"
  JAVA_SOURCE="service-private JDK"
fi
if [ -z "$JAVA" ]; then
  echo "ERROR: no JDK $REQUIRED_JAVA_MAJOR+ found (checked \$JAVA_HOME, PATH and $JDK_DIR)." >&2
  echo "       Set JAVA_HOME to a JDK $REQUIRED_JAVA_MAJOR+, or run install-phoss-ap-daemon.sh" >&2
  echo "       once to provision a service-private JDK in $JDK_DIR." >&2
  exit 1
fi

# --- Keep JAVA_HOME in sync with the JVM we picked ---------------------------
# Mirrors the systemd unit (Environment=JAVA_HOME=$JDK_DIR): anything the JVM
# spawns, and any library that consults JAVA_HOME, must see the JDK we actually
# run on - not a stale JAVA_HOME pointing at the old system Java 17.
# For a java taken from the PATH nothing is exported: /usr/bin/java is typically
# a symlink into the alternatives system, from which no JAVA_HOME can reliably
# be derived - a wrong value would be worse than none.
case "$JAVA_SOURCE" in
  "service-private JDK")
    JAVA_HOME="$JDK_DIR"
    export JAVA_HOME
    ;;
  "JAVA_HOME")
    # Already correct, but may be unexported (e.g. set in a profile as a plain
    # shell variable) - make sure the JVM's children inherit it.
    export JAVA_HOME
    ;;
esac

cd "$APP_HOME"

# --- Resolve jar ------------------------------------------------------------
if [ -z "$APP_JAR" ]; then
  # Pick the newest matching fat jar, excluding -sources / -javadoc artifacts.
  for f in phoss-ap-webapp-*.jar; do
    # Skip the unexpanded glob (no match) and the sources/javadoc artifacts.
    [ -f "$f" ] || continue
    case "$f" in
      *-sources.jar | *-javadoc.jar) continue ;;
    esac
    if [ -z "$APP_JAR" ] || [ "$f" -nt "$APP_JAR" ]; then
      APP_JAR="$f"
    fi
  done
fi
if [ -z "$APP_JAR" ] || [ ! -f "$APP_JAR" ]; then
  echo "ERROR: phoss-ap jar not found in $APP_HOME (looked for phoss-ap-webapp-*.jar). Set APP_JAR." >&2
  exit 1
fi

# --- Refuse to start twice --------------------------------------------------
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "phoss-ap already running (PID $(cat "$PID_FILE")). Stop it first." >&2
  exit 1
fi

mkdir -p "$PID_DIR" "$LOG_DIR"

# --- Launch as background daemon --------------------------------------------
echo "Starting phoss-ap: $APP_JAR (profile=$SPRING_PROFILE)"
echo "  java: $JAVA (from $JAVA_SOURCE)"
# Detach from the controlling terminal: a plain "&" leaves the JVM in the login
# shell's process group, so closing the SSH session sends it a SIGHUP and the
# app dies with it. "nohup" sets SIGHUP to ignore before exec'ing java - and
# because it exec's, $! stays the JVM's own PID (important for the PID file).
# stdin comes from /dev/null so the JVM never blocks on, or is disturbed by, a
# terminal that is going away.
# JAVA_OPTS is intentionally word-split into separate arguments.
if command -v nohup >/dev/null 2>&1; then
  # shellcheck disable=SC2086
  nohup "$JAVA" $JAVA_OPTS \
    -jar "$APP_HOME/$APP_JAR" \
    --spring.profiles.active="$SPRING_PROFILE" \
    < /dev/null >> "$LOG_FILE" 2>&1 &
else
  # Pure-shell fallback: an ignored signal disposition survives exec, and the
  # exec keeps the subshell's PID, so $! is again the JVM itself.
  # shellcheck disable=SC2086
  ( trap '' HUP
    exec "$JAVA" $JAVA_OPTS \
      -jar "$APP_HOME/$APP_JAR" \
      --spring.profiles.active="$SPRING_PROFILE" \
      < /dev/null >> "$LOG_FILE" 2>&1
  ) &
fi

PID=$!
echo "$PID" > "$PID_FILE"
echo "phoss-ap started with PID $PID"
echo "  log: $LOG_FILE"
echo "  pid: $PID_FILE"

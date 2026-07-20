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
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java)"
fi
if [ -z "$JAVA" ]; then
  echo "ERROR: no Java runtime found (set JAVA_HOME or put java on PATH). phoss-ap requires JDK 21+." >&2
  exit 1
fi

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
# JAVA_OPTS is intentionally word-split into separate arguments.
# shellcheck disable=SC2086
"$JAVA" $JAVA_OPTS \
  -jar "$APP_HOME/$APP_JAR" \
  --spring.profiles.active="$SPRING_PROFILE" \
  >> "$LOG_FILE" 2>&1 &

PID=$!
echo "$PID" > "$PID_FILE"
echo "phoss-ap started with PID $PID"
echo "  log: $LOG_FILE"
echo "  pid: $PID_FILE"

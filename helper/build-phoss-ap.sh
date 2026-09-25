#!/bin/sh
#
# Build script for the phoss-ap Peppol Access Point.
#
# Produces the runnable Spring Boot "fat jar" (phoss-ap-webapp-<version>.jar)
# and exports it into an output directory, ready to be deployed next to
# start-phoss-ap.sh / stop-phoss-ap.sh (default APP_HOME=/opt/peppol-ap).
#
# Tests are skipped by default: the full "verify" phase needs a running
# PostgreSQL (webapp context test) and Docker (Testcontainers S3 IT), which are
# usually not available on a build box. Set RUN_TESTS=1 to include them.
#
# Local profile configs (phoss-ap-webapp/src/main/resources/application-*.properties,
# git-ignored, may contain secrets) are moved aside during the build and are
# NOT packaged: ph-config loads a baked-in application-<profile>.properties at
# the same priority as the external one on the server and finds it first, so a
# stale local copy would silently override the server configuration.
# Set INCLUDE_PROFILE_CONFIG=1 to package them anyway.
#

set -e

# --- Locate repo root (this script lives in <repo>/helper) ------------------
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)

# --- Configuration (override via environment) -------------------------------
OUTPUT_DIR="${OUTPUT_DIR:-$REPO_ROOT/dist}"
MODULE="phoss-ap-webapp"
RUN_TESTS="${RUN_TESTS:-0}"
INCLUDE_PROFILE_CONFIG="${INCLUDE_PROFILE_CONFIG:-0}"
MVN="${MVN:-mvn}"

# --- Resolve Maven ----------------------------------------------------------
if ! command -v "$MVN" >/dev/null 2>&1; then
  echo "ERROR: Maven ('$MVN') not found on PATH. Install Maven 3.x or set MVN." >&2
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
# Extract the major version (handles "21", "21.0.11", and legacy "1.8.x")
JAVA_VER=$("$JAVA" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/')
if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
  echo "ERROR: JDK 21+ required, but '$JAVA' reports major version '$JAVA_VER'." >&2
  echo "       Point JAVA_HOME to a JDK 21 installation and retry." >&2
  exit 1
fi

echo "Using Maven : $("$MVN" -v 2>/dev/null | head -n 1)"
echo "Using Java  : $JAVA (major $JAVA_VER)"
echo "Repo root   : $REPO_ROOT"

# --- Build ------------------------------------------------------------------
# -am also builds the modules that phoss-ap-webapp depends on within the reactor.
if [ "$RUN_TESTS" = "1" ]; then
  TEST_ARGS=""
  echo "Building $MODULE WITH tests ..."
else
  TEST_ARGS="-DskipTests"
  echo "Building $MODULE (tests skipped) ..."
fi

cd "$REPO_ROOT"

# --- Keep local profile configs out of the jar ------------------------------
RES_DIR="$REPO_ROOT/$MODULE/src/main/resources"
STASH_DIR=""
restore_profile_config() {
  if [ -n "$STASH_DIR" ] && [ -d "$STASH_DIR" ]; then
    for f in "$STASH_DIR"/application-*.properties; do
      [ -f "$f" ] || continue
      mv -f "$f" "$RES_DIR/"
    done
    rmdir "$STASH_DIR" 2>/dev/null || true
    STASH_DIR=""
  fi
}

if [ "$INCLUDE_PROFILE_CONFIG" != "1" ]; then
  for f in "$RES_DIR"/application-*.properties; do
    [ -f "$f" ] || continue
    if [ -z "$STASH_DIR" ]; then
      STASH_DIR=$(mktemp -d)
      # Restore on success, failure and Ctrl+C
      trap restore_profile_config EXIT
      trap 'restore_profile_config; exit 130' INT TERM
    fi
    echo "Not packaging : $(basename "$f")"
    mv "$f" "$STASH_DIR/"
  done
fi

# shellcheck disable=SC2086
"$MVN" -B -pl "$MODULE" -am clean package $TEST_ARGS

restore_profile_config

# --- Locate and export the fat jar ------------------------------------------
TARGET_DIR="$REPO_ROOT/$MODULE/target"
# Pick the newest runnable jar, excluding -sources / -javadoc / .original artifacts.
JAR=""
for f in "$TARGET_DIR"/phoss-ap-webapp-*.jar; do
  # Skip the unexpanded glob (no match) and the auxiliary artifacts.
  [ -f "$f" ] || continue
  case "$f" in
    *-sources.jar | *-javadoc.jar | *.jar.original) continue ;;
  esac
  if [ -z "$JAR" ] || [ "$f" -nt "$JAR" ]; then
    JAR="$f"
  fi
done

if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "ERROR: build succeeded but no runnable jar found in $TARGET_DIR" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
cp -f "$JAR" "$OUTPUT_DIR/"
JAR_NAME=$(basename "$JAR")

echo ""
echo "=== Build complete ==="
echo "Exported: $OUTPUT_DIR/$JAR_NAME"
echo ""
echo "Deploy:"
echo "  cp \"$OUTPUT_DIR/$JAR_NAME\" /opt/peppol-ap/"
echo "  ./helper/start-phoss-ap.sh    # or: APP_HOME=/opt/peppol-ap ./helper/start-phoss-ap.sh"

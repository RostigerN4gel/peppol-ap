#!/bin/sh
#
# Repoint the "$LINK_NAME" symlink (the jar the systemd unit starts) to a jar
# that already sits in $APP_HOME - e.g. to switch to a self-built jar or to roll
# back to an older release kept by update-phoss-ap-release.sh.
#
# RUNS ON THE TARGET SERVER. It does NOT download, copy or (re)start anything;
# the symlink is read at service startup only.
#
# Usage:
#   ./switch-phoss-ap-jar.sh phoss-ap-webapp-0.11.0.jar
#   ./switch-phoss-ap-jar.sh                            # list available jars
#
# The argument is a file name inside $APP_HOME (a path is accepted too, but the
# file must live directly in $APP_HOME so the link can stay relative).
#
# Environment overrides:
#   APP_HOME    deployment directory (default: /opt/peppol-ap)
#   LINK_NAME   symlink the systemd unit starts (default: phoss-ap.jar)
#   MIN_SIZE    plausibility floor in bytes for the fat jar (default: 10000000)
#

set -e

# --- Configuration (override via environment) -------------------------------
APP_HOME="${APP_HOME:-/opt/peppol-ap}"
LINK_NAME="${LINK_NAME:-phoss-ap.jar}"
MIN_SIZE="${MIN_SIZE:-10000000}"

[ -d "$APP_HOME" ] || {
  echo "ERROR: APP_HOME '$APP_HOME' does not exist." >&2
  exit 1
}

cd "$APP_HOME"

list_jars() {
  echo "Jars in $APP_HOME:"
  for f in ./*.jar; do
    [ -e "$f" ] || continue
    f="${f#./}"
    [ "$f" = "$LINK_NAME" ] && continue
    echo "  $f"
  done
}

if [ $# -ne 1 ] || [ -z "$1" ]; then
  echo "Usage: $0 <jar-file-name>" >&2
  echo "" >&2
  if [ -L "$LINK_NAME" ]; then
    echo "Current: $LINK_NAME -> $(readlink "$LINK_NAME")" >&2
  fi
  list_jars >&2
  exit 1
fi

# Accept a path, but only link by bare file name (relative link inside APP_HOME).
JAR_NAME=$(basename "$1")

if [ "$JAR_NAME" = "$LINK_NAME" ]; then
  echo "ERROR: '$JAR_NAME' is the link itself - pass the target jar." >&2
  exit 1
fi

[ -f "$JAR_NAME" ] && [ ! -L "$JAR_NAME" ] || {
  echo "ERROR: '$JAR_NAME' is not a regular file in $APP_HOME." >&2
  list_jars >&2
  exit 1
}

[ -w "$APP_HOME" ] || {
  echo "ERROR: no write permission for '$APP_HOME' (running as $(id -un))." >&2
  echo "       Run as the owner ($(stat -c %U "$APP_HOME")) or via sudo." >&2
  exit 1
}

# --- Plausibility guard -----------------------------------------------------
SIZE=$(wc -c < "$JAR_NAME")
if [ "$SIZE" -lt "$MIN_SIZE" ]; then
  echo "ERROR: $JAR_NAME is only $SIZE bytes (floor: $MIN_SIZE) - not the fat" >&2
  echo "       jar. Symlink left untouched. Override with MIN_SIZE=0." >&2
  exit 1
fi

# --- Repoint the symlink ----------------------------------------------------
PREVIOUS=""
if [ -L "$LINK_NAME" ]; then
  PREVIOUS=$(readlink "$LINK_NAME")
elif [ -e "$LINK_NAME" ]; then
  echo "ERROR: '$LINK_NAME' exists but is NOT a symlink - refusing to replace it." >&2
  echo "       Move it away first if it is no longer needed." >&2
  exit 1
fi

if [ "$PREVIOUS" = "$JAR_NAME" ]; then
  echo "$LINK_NAME -> $JAR_NAME (unchanged)"
  exit 0
fi

# Relative target, so the link survives a move of $APP_HOME.
ln -sfn "$JAR_NAME" "$LINK_NAME"

# When run via sudo, keep the link owned like the directory (cosmetic only).
if [ "$(id -u)" = "0" ]; then
  chown -h "$(stat -c "%U:%G" "$APP_HOME")" "$LINK_NAME"
fi

cat <<EOF
Switched: ${PREVIOUS:-none} -> $JAR_NAME
  $APP_HOME/$LINK_NAME -> $(readlink "$LINK_NAME")

The running service still uses the old jar. Apply it with:
  sudo systemctl restart phoss-ap
EOF

if [ -n "$PREVIOUS" ]; then
  cat <<EOF

Rollback:
  $0 $PREVIOUS
EOF
fi

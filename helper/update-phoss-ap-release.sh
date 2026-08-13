#!/bin/sh
#
# Update the deployed phoss-ap Peppol Access Point to an UPSTREAM RELEASE jar.
#
# RUNS ON THE TARGET SERVER (no ssh, no scp). Copy it to the host once, e.g.
#   scp helper/update-phoss-ap-release.sh dev-as4:/opt/peppol-ap/helper/
# and run it there.
#
# What it does, and nothing else:
#   1. determine the latest phoss-ap-webapp release on Maven Central
#   2. download that fat jar into $APP_HOME and verify its SHA-256
#   3. repoint the "$LINK_NAME" symlink - the target the systemd unit starts
#
# It does NOT touch any configuration and does NOT (re)start the service.
# Configuration is expected to be deployed manually, e.g. as
# $APP_HOME/application-<profile>.properties next to the jar.
#
# Why Maven Central and not the GitHub releases: the GitHub release assets are
# only the auto-generated source archives. The runnable fat jar is published to
#   https://repo1.maven.org/maven2/com/helger/phoss/ap/phoss-ap-webapp/
#
# Usage:
#   ./update-phoss-ap-release.sh              # latest release
#   ./update-phoss-ap-release.sh 0.11.0       # pin a version
#
# Environment overrides:
#   APP_HOME    deployment directory (default: /opt/peppol-ap)
#   LINK_NAME   symlink the systemd unit starts (default: phoss-ap.jar)
#   ARTIFACT    Maven artifactId (default: phoss-ap-webapp)
#   GROUP_PATH  Maven groupId as a path (default: com/helger/phoss/ap)
#   BASE_URL    Maven repository base (default: https://repo1.maven.org/maven2)
#   MIN_SIZE    plausibility floor in bytes for the fat jar (default: 10000000)
#

set -e

# --- Configuration (override via environment) -------------------------------
APP_HOME="${APP_HOME:-/opt/peppol-ap}"
LINK_NAME="${LINK_NAME:-phoss-ap.jar}"
ARTIFACT="${ARTIFACT:-phoss-ap-webapp}"
GROUP_PATH="${GROUP_PATH:-com/helger/phoss/ap}"
BASE_URL="${BASE_URL:-https://repo1.maven.org/maven2}"
MIN_SIZE="${MIN_SIZE:-10000000}"

VERSION="${1:-}"

ARTIFACT_URL="$BASE_URL/$GROUP_PATH/$ARTIFACT"

# --- Prerequisites ----------------------------------------------------------
for cmd in curl sha256sum ln readlink; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "ERROR: '$cmd' not found on PATH." >&2
    exit 1
  }
done

[ -d "$APP_HOME" ] || {
  echo "ERROR: APP_HOME '$APP_HOME' does not exist." >&2
  exit 1
}
[ -w "$APP_HOME" ] || {
  echo "ERROR: no write permission for '$APP_HOME' (running as $(id -un))." >&2
  echo "       Run as the owner ($(stat -c %U "$APP_HOME")) or via sudo." >&2
  exit 1
}

cd "$APP_HOME"

echo "Deployment  : $APP_HOME"
echo "Artifact    : $ARTIFACT"

# --- Resolve the version ----------------------------------------------------
if [ -z "$VERSION" ]; then
  echo "Resolving latest release from Maven Central ..."
  # The <release> element of maven-metadata.xml is the newest non-snapshot
  # version. Parsed without an XML tool: split on angle brackets, then take the
  # line following the "release" tag.
  VERSION=$(curl -fsS --max-time 60 "$ARTIFACT_URL/maven-metadata.xml" |
    tr '<>' '\n\n' | grep -A1 '^release$' | tail -n 1)
  [ -n "$VERSION" ] || {
    echo "ERROR: could not determine the latest release from" >&2
    echo "       $ARTIFACT_URL/maven-metadata.xml" >&2
    exit 1
  }
  echo "Latest      : $VERSION"
else
  echo "Pinned      : $VERSION"
fi

JAR_NAME="$ARTIFACT-$VERSION.jar"
JAR_URL="$ARTIFACT_URL/$VERSION/$JAR_NAME"

echo ""
echo "--- Before ---"
if [ -L "$LINK_NAME" ]; then
  echo "$LINK_NAME -> $(readlink "$LINK_NAME")"
elif [ -e "$LINK_NAME" ]; then
  echo "WARNING: '$LINK_NAME' exists but is NOT a symlink - it will be replaced"
  echo "         by one. Keep a copy if you still need that file."
  ls -l "$LINK_NAME"
else
  echo "(no $LINK_NAME yet)"
fi

# --- Expected checksum ------------------------------------------------------
# Maven Central serves the bare hex digest without a filename, so the checkfile
# for sha256sum cannot be used directly - compare the digests instead.
EXPECTED=$(curl -fsS --max-time 60 "$JAR_URL.sha256" | awk '{print $1}')
[ -n "$EXPECTED" ] || {
  echo "ERROR: could not fetch the SHA-256 for $JAR_NAME" >&2
  echo "       $JAR_URL.sha256" >&2
  exit 1
}

# --- Download (idempotent) --------------------------------------------------
need_download=1
if [ -f "$JAR_NAME" ]; then
  ACTUAL=$(sha256sum "$JAR_NAME" | awk '{print $1}')
  if [ "$ACTUAL" = "$EXPECTED" ]; then
    echo ""
    echo "$JAR_NAME already present, checksum matches - skipping download."
    need_download=0
  else
    echo ""
    echo "$JAR_NAME present but checksum differs - re-downloading."
  fi
fi

if [ "$need_download" = "1" ]; then
  echo ""
  echo "Downloading $JAR_URL ..."
  # Download to a temp name first, so an aborted transfer can never be linked.
  curl -fL --max-time 1800 -o "$JAR_NAME.part" "$JAR_URL"
  ACTUAL=$(sha256sum "$JAR_NAME.part" | awk '{print $1}')
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    rm -f "$JAR_NAME.part"
    echo "ERROR: checksum mismatch for $JAR_NAME - download discarded." >&2
    echo "       expected $EXPECTED" >&2
    echo "       actual   $ACTUAL" >&2
    exit 1
  fi
  mv -f "$JAR_NAME.part" "$JAR_NAME"
  echo "SHA-256 OK  : $EXPECTED"
fi

chmod 644 "$JAR_NAME"

# When run via sudo, hand the file to the directory owner so the service user
# keeps consistent ownership across all deployed jars.
if [ "$(id -u)" = "0" ]; then
  OWNER=$(stat -c "%U:%G" "$APP_HOME")
  chown "$OWNER" "$JAR_NAME"
fi

# --- Plausibility guard -----------------------------------------------------
# Protects against linking an HTML error page or a thin jar. Only reached when
# the checksum already matched, so this is a belt-and-braces check.
SIZE=$(wc -c < "$JAR_NAME")
if [ "$SIZE" -lt "$MIN_SIZE" ]; then
  echo "ERROR: $JAR_NAME is only $SIZE bytes (floor: $MIN_SIZE) - not the fat" >&2
  echo "       jar. Symlink left untouched." >&2
  exit 1
fi

# --- Repoint the symlink ----------------------------------------------------
PREVIOUS=""
[ -L "$LINK_NAME" ] && PREVIOUS=$(readlink "$LINK_NAME")

# Relative target, so the link survives a move of $APP_HOME.
ln -sfn "$JAR_NAME" "$LINK_NAME"

echo ""
echo "--- After ---"
echo "$LINK_NAME -> $(readlink "$LINK_NAME")"
echo "size        : $SIZE bytes"
echo ""
echo "--- Jars in $APP_HOME ---"
ls -1 ./*.jar 2>/dev/null || true

# --- Next steps -------------------------------------------------------------
if [ "$PREVIOUS" = "$JAR_NAME" ]; then
  echo ""
  echo "=== Nothing changed - already on $VERSION ==="
  exit 0
fi

cat <<EOF

=== Update complete: ${PREVIOUS:-none} -> $JAR_NAME ===

The running service still uses the old jar; the symlink is read at startup
only. Apply and watch it manually:

  sudo systemctl restart phoss-ap
  journalctl -u phoss-ap -f -o cat

Configuration is NOT managed by this script. Make sure the matching
application-<profile>.properties sits in $APP_HOME and that the unit
activates that profile.
EOF

if [ -n "$PREVIOUS" ]; then
  cat <<EOF

Rollback (older jars are kept on purpose):
  ln -sfn $PREVIOUS $APP_HOME/$LINK_NAME
EOF
fi

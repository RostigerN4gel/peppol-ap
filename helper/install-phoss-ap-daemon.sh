#!/bin/sh
#
# Install script for the phoss-ap Peppol Access Point as a systemd daemon.
#
# What it does:
#   1. Resolves a JDK 21+ (see "Java resolution" below).
#   2. Resolves the runnable fat jar (arg, $APP_JAR, or newest phoss-ap-webapp-*.jar
#      found next to this script / in ../dist / in the current directory).
#   3. Copies the jar into $APP_HOME and points a stable symlink
#      "$APP_HOME/$SERVICE_NAME.jar" at it.
#   4. Writes /etc/systemd/system/$SERVICE_NAME.service.
#   5. Runs "systemctl daemon-reload" and "systemctl enable" (start on boot).
#
# Java resolution (phoss-ap requires JDK 21+):
#   The system Java is never modified. Candidates are probed in this order and the
#   first one that reports major version >= $REQUIRED_JAVA_MAJOR wins:
#     a) $JAVA_HOME/bin/java
#     b) a private JDK previously provisioned at $JDK_DIR (default $APP_HOME/jdk)
#     c) "java" on the PATH
#   (b) before (c) keeps a re-install - and start-phoss-ap.sh, which uses the same
#   precedence - on the JDK this service was provisioned with; set
#   PREFER_PRIVATE_JDK=0 to probe the PATH first instead.
#   If none qualifies (e.g. the host only has Java 17), a private Temurin JDK is
#   downloaded into $JDK_DIR and used *only* by this service - it is referenced by
#   absolute path in the systemd unit, is not added to the PATH and does not touch
#   /usr/bin/java or the alternatives system. Set JDK_DOWNLOAD=0 to turn the
#   download off, or JDK_ARCHIVE=/path/to/jdk.tar.gz to install from a local
#   tarball (air-gapped hosts).
#
# The service user/group (default: ec2-user) is expected to already exist; this
# script does NOT create or delete it. It can be installed alongside other
# services (e.g. a tomcat-based one) without conflict.
#
# It deliberately does NOT start the service - start it manually with
#   systemctl start phoss-ap
#
# Must be run as root (systemd unit, /opt/peppol-ap).
# Counterpart: uninstall-phoss-ap-daemon.sh
#

set -e

# --- Locate repo/helper dir (this script lives in <repo>/helper) ------------
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# --- Configuration (override via environment) -------------------------------
APP_HOME="${APP_HOME:-/opt/peppol-ap}"
SERVICE_NAME="${SERVICE_NAME:-phoss-ap}"
SERVICE_USER="${SERVICE_USER:-ec2-user}"
SERVICE_GROUP="${SERVICE_GROUP:-$SERVICE_USER}"
# Spring profile whose "application-<profile>.properties" gets loaded
SPRING_PROFILE="${SPRING_PROFILE:-dev}"
JAVA_OPTS="${JAVA_OPTS:--Djava.security.egd=file:///dev/urandom -XX:MaxRAMPercentage=80}"
# Explicit jar to install; auto-detected if empty (may also be passed as $1)
APP_JAR="${APP_JAR:-$1}"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

# --- Java / private JDK configuration (override via environment) -------------
# Minimum Java major version phoss-ap needs.
REQUIRED_JAVA_MAJOR="${REQUIRED_JAVA_MAJOR:-21}"
# Where a service-private JDK is kept. Used only if no suitable system Java exists.
JDK_DIR="${JDK_DIR:-$APP_HOME/jdk}"
# 1 = an existing private JDK outranks "java" on the PATH (keeps re-installs and
# start-phoss-ap.sh on the JVM this service was provisioned with), 0 = PATH first.
PREFER_PRIVATE_JDK="${PREFER_PRIVATE_JDK:-1}"
# Temurin feature release to download when provisioning the private JDK.
JDK_FEATURE="${JDK_FEATURE:-21}"
# 1 = download a private JDK if no suitable Java is found, 0 = fail instead.
JDK_DOWNLOAD="${JDK_DOWNLOAD:-1}"
# Explicit download URL (overrides the Adoptium API URL built from JDK_FEATURE).
JDK_URL="${JDK_URL:-}"
# Local JDK .tar.gz to install instead of downloading (air-gapped hosts).
JDK_ARCHIVE="${JDK_ARCHIVE:-}"
# Expected SHA-256 of the archive. Read from the Adoptium asset list when the URL
# is resolved automatically; set explicitly to pin a known value (required to get
# an integrity check when JDK_URL or JDK_ARCHIVE is used).
JDK_SHA256="${JDK_SHA256:-}"

# --- Require root -----------------------------------------------------------
if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: this installer must be run as root (systemd unit + $APP_HOME)." >&2
  echo "       Retry with: sudo $0" >&2
  exit 1
fi

# --- Require the service user/group to already exist ------------------------
# This script does not create (nor delete) the account - the operator is
# expected to provide it (e.g. the pre-existing 'ec2-user').
# Checked up front: it must fail before a (potentially large) JDK download.
if ! getent group "$SERVICE_GROUP" >/dev/null 2>&1; then
  echo "ERROR: service group '$SERVICE_GROUP' does not exist. Create it first, or set SERVICE_GROUP." >&2
  exit 1
fi
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  echo "ERROR: service user '$SERVICE_USER' does not exist. Create it first, or set SERVICE_USER." >&2
  exit 1
fi

# --- Java helpers -------------------------------------------------------------
# Echo the Java major version of the given binary (handles "21", "21.0.11" and
# legacy "1.8.x"). Echoes nothing and fails if it is not a usable java binary.
java_major_version ()
{
  jmv_bin="$1"
  [ -n "$jmv_bin" ] && [ -x "$jmv_bin" ] || return 1
  jmv_out=$("$jmv_bin" -version 2>&1 | head -n 1) || return 1
  jmv_ver=$(echo "$jmv_out" | sed -E 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/')
  case "$jmv_ver" in
    '' | *[!0-9]*) return 1 ;;
  esac
  echo "$jmv_ver"
}

# True if the given java binary satisfies REQUIRED_JAVA_MAJOR.
java_is_suitable ()
{
  jis_ver=$(java_major_version "$1") || return 1
  [ "$jis_ver" -ge "$REQUIRED_JAVA_MAJOR" ] 2>/dev/null
}

# Map "uname -m" to the Adoptium architecture name.
jdk_arch ()
{
  case "$(uname -m)" in
    x86_64 | amd64) echo "x64" ;;
    aarch64 | arm64) echo "aarch64" ;;
    *) return 1 ;;
  esac
}

# Download $1 into the file $2 using curl or wget. Returns 127 if neither exists.
http_get ()
{
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 --retry-delay 2 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$2" "$1"
  else
    echo "ERROR: neither curl nor wget is available to download the JDK." >&2
    echo "       Install one of them, or pass a local archive via JDK_ARCHIVE=/path/to/jdk.tar.gz" >&2
    return 127
  fi
}

# Provision the private JDK into $JDK_DIR (download, or extract $JDK_ARCHIVE).
install_private_jdk ()
{
  ipj_tmp=$(mktemp -d "${TMPDIR:-/tmp}/phoss-ap-jdk.XXXXXX")
  # shellcheck disable=SC2064
  trap "rm -rf '$ipj_tmp'" EXIT INT TERM
  ipj_tgz="$ipj_tmp/jdk.tar.gz"

  if [ -n "$JDK_ARCHIVE" ]; then
    [ -f "$JDK_ARCHIVE" ] || { echo "ERROR: JDK_ARCHIVE '$JDK_ARCHIVE' not found." >&2; return 1; }
    echo "Using local JDK archive: $JDK_ARCHIVE"
    cp -f "$JDK_ARCHIVE" "$ipj_tgz"
  else
    ipj_arch=$(jdk_arch) || {
      echo "ERROR: unsupported CPU architecture '$(uname -m)' for the automatic JDK download." >&2
      echo "       Install a JDK $REQUIRED_JAVA_MAJOR+ manually and re-run with JAVA_HOME=... or JDK_ARCHIVE=..." >&2
      return 1
    }

    ipj_url="$JDK_URL"
    if [ -z "$ipj_url" ]; then
      # Ask the Adoptium API for the exact asset URL *and* its SHA-256. This is
      # more reliable than the /v3/binary/... redirect, whose final URL is a
      # signed CDN link from which no checksum can be derived.
      ipj_api="https://api.adoptium.net/v3/assets/latest/${JDK_FEATURE}/hotspot?architecture=${ipj_arch}&image_type=jdk&os=linux&vendor=eclipse"
      echo "Querying Adoptium for Temurin JDK $JDK_FEATURE ($ipj_arch) ..."
      if http_get "$ipj_api" "$ipj_tmp/assets.json"; then
        # Cheap, dependency-free JSON scraping: one value per line, then filter.
        ipj_url=$(tr ',{}' '\n\n\n' < "$ipj_tmp/assets.json" |
                  grep '"link"' | grep -oE 'https://[^"]*\.tar\.gz' | head -n 1)
        if [ -z "$JDK_SHA256" ]; then
          JDK_SHA256=$(tr ',{}' '\n\n\n' < "$ipj_tmp/assets.json" |
                       grep '"checksum"' | grep -oE '[0-9a-f]{64}' | head -n 1)
        fi
      fi
      if [ -z "$ipj_url" ]; then
        # Fall back to the redirecting binary endpoint (no checksum available).
        ipj_url="https://api.adoptium.net/v3/binary/latest/${JDK_FEATURE}/ga/linux/${ipj_arch}/jdk/hotspot/normal/eclipse"
        echo "WARNING: could not read the asset list - falling back to $ipj_url" >&2
      fi
    fi

    echo "Downloading JDK ..."
    echo "  from: $ipj_url"
    http_get "$ipj_url" "$ipj_tgz" || { echo "ERROR: JDK download failed." >&2; return 1; }
  fi

  # --- Integrity check (best effort: only when a checksum is known) ----------
  if [ -n "$JDK_SHA256" ] && command -v sha256sum >/dev/null 2>&1; then
    ipj_actual=$(sha256sum "$ipj_tgz" | awk '{print $1}')
    if [ "$ipj_actual" != "$JDK_SHA256" ]; then
      echo "ERROR: JDK archive checksum mismatch." >&2
      echo "       expected: $JDK_SHA256" >&2
      echo "       actual  : $ipj_actual" >&2
      return 1
    fi
    echo "Checksum OK (sha256 $ipj_actual)"
  elif [ -n "$JDK_ARCHIVE" ]; then
    echo "WARNING: JDK_SHA256 not set - the local archive was installed unverified." >&2
  else
    echo "WARNING: no SHA-256 available - the download was only verified by HTTPS transport." >&2
  fi

  # --- Extract ---------------------------------------------------------------
  mkdir -p "$ipj_tmp/x"
  tar -xzf "$ipj_tgz" -C "$ipj_tmp/x" || { echo "ERROR: failed to extract the JDK archive." >&2; return 1; }
  # Temurin tarballs contain exactly one top-level directory.
  ipj_top=""
  for d in "$ipj_tmp"/x/*; do
    [ -d "$d" ] || continue
    ipj_top="$d"
    break
  done
  if [ -z "$ipj_top" ] || [ ! -x "$ipj_top/bin/java" ]; then
    echo "ERROR: the archive does not look like a JDK (no bin/java found)." >&2
    return 1
  fi

  # --- Swap into place (keep the previous one until the new one is in) -------
  mkdir -p "$(dirname -- "$JDK_DIR")"
  rm -rf "$JDK_DIR.new" "$JDK_DIR.old"
  mv "$ipj_top" "$JDK_DIR.new"
  [ -d "$JDK_DIR" ] && mv "$JDK_DIR" "$JDK_DIR.old"
  mv "$JDK_DIR.new" "$JDK_DIR"
  rm -rf "$JDK_DIR.old"

  rm -rf "$ipj_tmp"
  trap - EXIT INT TERM
  return 0
}

# --- Resolve Java (never touches the system Java) ----------------------------
# Order: $JAVA_HOME -> already provisioned private JDK -> PATH -> download.
# An existing $JDK_DIR outranks the PATH on purpose: it was provisioned because
# this host had no suitable system Java, so a re-install must not silently move
# the service onto a java that appeared on the PATH in the meantime. It also
# keeps start-phoss-ap.sh (same precedence) on exactly the binary that ends up in
# the unit's ExecStart. PREFER_PRIVATE_JDK=0 restores the plain PATH-first order.
JAVA=""
JAVA_SOURCE=""
JAVA_PRIVATE=0

if [ -n "$JAVA_HOME" ] && java_is_suitable "$JAVA_HOME/bin/java"; then
  JAVA="$JAVA_HOME/bin/java"
  JAVA_SOURCE="JAVA_HOME"
fi
if [ -z "$JAVA" ] && [ "$PREFER_PRIVATE_JDK" = "1" ] && java_is_suitable "$JDK_DIR/bin/java"; then
  JAVA="$JDK_DIR/bin/java"
  JAVA_SOURCE="private JDK (already installed)"
  JAVA_PRIVATE=1
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
  JAVA_SOURCE="private JDK (already installed)"
  JAVA_PRIVATE=1
fi

if [ -z "$JAVA" ]; then
  # Report what IS there, so the operator sees why it was rejected.
  FOUND_JAVA="$(command -v java || true)"
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    FOUND_JAVA="$JAVA_HOME/bin/java"
  fi
  if [ -n "$FOUND_JAVA" ]; then
    FOUND_VER=$(java_major_version "$FOUND_JAVA" || echo "unknown")
    echo "No suitable Java: '$FOUND_JAVA' reports major version $FOUND_VER, but JDK $REQUIRED_JAVA_MAJOR+ is required."
  else
    echo "No Java runtime found on this host, but JDK $REQUIRED_JAVA_MAJOR+ is required."
  fi

  if [ "$JDK_DOWNLOAD" != "1" ] && [ -z "$JDK_ARCHIVE" ]; then
    echo "ERROR: automatic JDK provisioning is disabled (JDK_DOWNLOAD=$JDK_DOWNLOAD)." >&2
    echo "       Either install a JDK $REQUIRED_JAVA_MAJOR+ and re-run with JAVA_HOME=/path/to/jdk," >&2
    echo "       or re-run with JDK_DOWNLOAD=1, or provide JDK_ARCHIVE=/path/to/jdk.tar.gz." >&2
    exit 1
  fi

  echo "Provisioning a service-private JDK in $JDK_DIR (the system Java is left untouched) ..."
  install_private_jdk || exit 1

  if ! java_is_suitable "$JDK_DIR/bin/java"; then
    PRIVATE_VER=$(java_major_version "$JDK_DIR/bin/java" || echo "unknown")
    echo "ERROR: the provisioned JDK in $JDK_DIR reports major version '$PRIVATE_VER'," >&2
    echo "       but JDK $REQUIRED_JAVA_MAJOR+ is required." >&2
    exit 1
  fi
  JAVA="$JDK_DIR/bin/java"
  JAVA_SOURCE="private JDK (newly installed)"
  JAVA_PRIVATE=1
fi

JAVA_VER=$(java_major_version "$JAVA")

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

echo "Using Java  : $JAVA (major $JAVA_VER, from $JAVA_SOURCE)"
echo "Installing  : $APP_JAR"
echo "Service     : $SERVICE_NAME (user $SERVICE_USER:$SERVICE_GROUP, profile $SPRING_PROFILE)"
echo "App home    : $APP_HOME"

# --- Deploy the jar ----------------------------------------------------------
mkdir -p "$APP_HOME" "$APP_HOME/logs"
cp -f "$APP_JAR" "$APP_HOME/$JAR_BASENAME"
ln -sfn "$APP_HOME/$JAR_BASENAME" "$APP_HOME/$SERVICE_NAME.jar"
chown "$SERVICE_USER:$SERVICE_GROUP" "$APP_HOME" "$APP_HOME/logs" \
      "$APP_HOME/$JAR_BASENAME" "$APP_HOME/$SERVICE_NAME.jar"

# --- Write the systemd unit --------------------------------------------------
# The unit references the JDK by absolute path, so the service uses it regardless
# of what "java" resolves to for interactive users.
if [ "$JAVA_PRIVATE" = "1" ]; then
  UNIT_JAVA_HOME="Environment=JAVA_HOME=$JDK_DIR"
else
  UNIT_JAVA_HOME="# Using the system Java - JAVA_HOME intentionally not set."
fi

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
$UNIT_JAVA_HOME
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
if [ "$JAVA_PRIVATE" = "1" ]; then
  echo ""
  echo "Java: the service uses the private JDK $JAVA_VER in $JDK_DIR."
  echo "      The system Java was left untouched ('java' on the PATH is unchanged)."
fi
echo ""
echo "Start it manually:"
echo "  systemctl start $SERVICE_NAME"
echo "Check status / logs:"
echo "  systemctl status $SERVICE_NAME"
echo "  journalctl -u $SERVICE_NAME -f"

# Installing phoss-ap on a Linux development system

Step-by-step installation of this fork on a **Linux dev box**, from a bare machine to a running
Access Point that answers on `http://localhost:8080`.

Scope: development / test only. It uses the Peppol **test** network, the bundled dummy AS4
keystore and a local PostgreSQL. Production notes are called out where they differ, but this is
not a production hardening guide.

Related docs: [CUSTOMIZATIONS.md](CUSTOMIZATIONS.md) (what this fork changes),
[communication-flows.md](communication-flows.md) (message flows), [../CLAUDE.md](../CLAUDE.md)
(repo conventions).

---

## 0. Overview

| Step | What | Result |
|------|------|--------|
| 1 | Provide a JDK 21 (side-by-side is fine), Maven, Git (+ Docker, optional) | Build toolchain |
| 2 | Clone the repository | Sources in `~/git/peppol-ap` |
| 3 | Start PostgreSQL | DB `phoss-ap` on `localhost:5432` |
| 4 | Provide the AS4 keystore | `.p12` with a private key |
| 5 | Write the dev configuration | `application-dev.properties` |
| 6 | Build the fat jar | `dist/phoss-ap-webapp-<version>.jar` |
| 7 | Run it | AP on `http://localhost:8080` |
| 8 | Verify | Health, status, API, AS4 endpoint respond |
| 9 | *(optional)* Install as a systemd service | `systemctl start phoss-ap` |

Ports used: **8080** (the AP), **5432** (PostgreSQL), **8888** (the middleware `receiver` this
fork forwards inbound documents to — see [step 7.4](#74-a-forwarding-target-for-inbound-documents)).

> **Host already on Java 17?** phoss-ap needs JDK 21, but it never has to become the system
> default. For building, point `JAVA_HOME` at a side-by-side JDK 21
> ([1.2](#12-hosts-that-already-run-another-java-eg-java-17)); for running as a service, the
> installer provisions a JDK used by phoss-ap alone
> ([9.1](#91-hosts-without-a-jdk-21-private-jdk)).

---

## 1. Prerequisites

### 1.1 Hard requirements

- **JDK 21 or later** — enforced by `maven.compiler.release=21` in the root [pom.xml](../pom.xml).
  Building or running with an older JDK fails. It does **not** have to be the system default: a
  JDK 21 installed side-by-side with an existing Java (17, 11, …) is enough — see
  [1.2](#12-hosts-that-already-run-another-java-eg-java-17).
- **Maven 3.x**
- **Git**
- **PostgreSQL 13+** (or MySQL 8.4) reachable from the machine
- ~2 GB free RAM for the JVM, ~1 GB disk for the Maven repository and the build

Optional: **Docker** + Compose (easiest way to get PostgreSQL; also required for the
Testcontainers-based S3 integration test in `phoss-ap-basic`).

Check what the host currently has:

```sh
java -version                      # the default JVM
ls /usr/lib/jvm                    # JDKs the distro knows about
update-alternatives --display java # Debian/Ubuntu: which one is default and why
```

### 1.2 Hosts that already run another Java (e.g. Java 17)

This is the common case: the box runs other applications on Java 17 and its default must not
change. Pick one of three routes.

**A — Let the phoss-ap installer provide the JDK (run-only hosts).**
If the host only *runs* the prebuilt jar and never builds it, do nothing here.
[install-phoss-ap-daemon.sh](../helper/install-phoss-ap-daemon.sh) detects the too-old Java and
provisions a JDK 21 used exclusively by the service — see
[9.1](#91-hosts-without-a-jdk-21-private-jdk). The system Java stays untouched.

**B — Install a JDK 21 tarball under `/opt` (build hosts, no distro involvement).**
Nothing is registered with `alternatives`, so `java -version` keeps reporting 17:

```sh
ARCH=$(uname -m); case "$ARCH" in x86_64|amd64) A=x64 ;; aarch64|arm64) A=aarch64 ;; esac
curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/$A/jdk/hotspot/normal/eclipse" \
  | sudo tar -xz -C /opt
sudo ln -sfn "$(ls -d /opt/jdk-21.*/ | tail -1)" /opt/jdk-21   # stable path across patch releases
export JAVA_HOME=/opt/jdk-21
"$JAVA_HOME/bin/java" -version                  # 21.x
java -version                                   # still 17.x - unchanged
```

**C — Install the distro package, then pin the default back.**
Convenient, but on Debian/Ubuntu `alternatives` runs in *auto* mode and picks the highest-priority
JVM, so installing JDK 21 can silently make it the system default. Verify and, if needed, force the
old one back:

```sh
sudo apt-get install -y openjdk-21-jdk
update-alternatives --display java              # check who won
sudo update-alternatives --set java  /usr/lib/jvm/java-17-openjdk-amd64/bin/java
sudo update-alternatives --set javac /usr/lib/jvm/java-17-openjdk-amd64/bin/javac
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

With routes B and C, everything phoss-ap-related is driven by `JAVA_HOME`
([1.6](#16-pin-java_home-for-phoss-ap)) — the rest of the system keeps its Java 17.

### 1.3 Debian / Ubuntu (JDK 21 may become the default)

```sh
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk maven git curl
```

### 1.4 RHEL / Fedora / Rocky

```sh
sudo dnf install -y java-21-openjdk-devel maven git curl
```

### 1.5 Amazon Linux 2023

```sh
sudo dnf install -y java-21-amazon-corretto-devel git curl
```

Amazon Linux 2023 has no Maven package. Install it from the Apache binary distribution:

```sh
MAVEN_VERSION=3.9.9
curl -fsSL "https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
  | sudo tar -xz -C /opt
sudo ln -sfn "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn
```

### 1.6 Pin JAVA_HOME for phoss-ap

Maven and every helper script honour `JAVA_HOME`, so pointing it at the JDK 21 is all that is
needed — regardless of what `java` on the `PATH` resolves to.

If the JDK 21 *is* the default, derive `JAVA_HOME` from `javac`:

```sh
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
```

If the default is an older Java, set it explicitly to the side-by-side JDK:

```sh
export JAVA_HOME=/opt/jdk-21                          # route B
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # route C
```

Persist it and verify:

```sh
echo "export JAVA_HOME=$JAVA_HOME" >> ~/.bashrc

"$JAVA_HOME/bin/java" -version   # must be 21+
mvn -v                           # the "Java version:" line must be 21+
```

`mvn -v` is the one that matters for building: Maven runs on `JAVA_HOME`, not on the `PATH` java.
A shell without `JAVA_HOME` exported (cron, `sudo`, a fresh SSH session) falls back to the system
Java — pass it explicitly there, e.g. `sudo JAVA_HOME=/opt/jdk-21 ./helper/install-phoss-ap-daemon.sh`.

---

## 2. Get the sources

```sh
mkdir -p ~/git && cd ~/git
git clone https://github.com/RostigerN4gel/peppol-ap.git
cd peppol-ap
```

To keep the fork mergeable with upstream, add the upstream remote as well:

```sh
git remote add upstream https://github.com/phax/phoss-ap.git
```

---

## 3. Database

The AP stores its transaction state in a relational DB (**JDBC only** — there is no MongoDB
support). The schema is created and migrated by **Flyway** at application startup; you only need an
empty database and a user that may create schemas.

### 3.1 Option A — PostgreSQL via Docker (recommended for dev)

The repository ships a compose file for exactly this:

```sh
docker compose -f unittest-db-docker-compose.yml up -d
```

This starts PostgreSQL on `5432` **and** MySQL on `3306` with database `phoss-ap` and user
`peppol` / password `peppol`. To skip MySQL:

```sh
docker compose -f unittest-db-docker-compose.yml up -d postgres
```

Stop / remove later with `docker compose -f unittest-db-docker-compose.yml down`
(add `-v` to also drop the data volumes).

### 3.2 Option B — native PostgreSQL

```sh
# Debian/Ubuntu
sudo apt-get install -y postgresql
# RHEL/Fedora/Amazon Linux
sudo dnf install -y postgresql-server && sudo postgresql-setup --initdb

sudo systemctl enable --now postgresql

sudo -u postgres psql <<'SQL'
CREATE USER peppol WITH PASSWORD 'peppol';
CREATE DATABASE "phoss-ap" OWNER peppol;
SQL
```

The user must own the database (or hold `CREATE` on it): with
`phossap.flyway.jdbc.schema-create=true` the application creates the schemas `ap`, `reporting` and
`report` itself on first start.

### 3.3 Check connectivity

```sh
psql "postgresql://peppol:peppol@localhost:5432/phoss-ap" -c "select version();"
```

> **MySQL instead of PostgreSQL:** use the `phossap.jdbc.*` MySQL block from
> [application.properties](../phoss-ap-webapp/src/main/resources/application.properties), set
> `phossap.jdbc.schema=` (empty) and `phossap.flyway.jdbc.schema-create=false`. Both JDBC drivers
> are bundled in the fat jar.

---

## 4. AS4 keystore

At startup the AP loads the configured PKCS#12 keystore and **aborts** if the keystore, the
private key or the Peppol CA check fails (see
[APServletInit.java:288-333](../phoss-ap-core/src/main/java/com/helger/phoss/ap/core/servlet/APServletInit.java#L288-L333)).

### 4.1 With a real Peppol test certificate (preferred)

Put your Peppol **test** AP certificate (`.p12`) somewhere readable, e.g.
`~/peppol/ap-test.p12`, and configure it in step 5. `*.p12` is git-ignored, so it can also live
inside the working copy without risk of being committed.

### 4.2 Without a Peppol certificate (dev fallback)

The repository ships a self-signed placeholder,
`phoss-ap-webapp/src/main/resources/invalid-keystore-pw-peppol.p12` (alias
`private_key_for_pkcs12_certificate`, password `peppol`). It loads fine, but it is *not* issued by
a Peppol CA, so the CA check fails and startup aborts — unless you set the escape hatch used by the
project's own Spring-context test:

```sh
-Dphossap.internal.skip-peppol-certificate-check=true
```

> **Development only.** With this flag the AP runs with a certificate no Peppol partner will
> accept: real AS4 exchange is impossible and the flag must never be set outside a dev box. It only
> skips the *CA* check — a loadable keystore with a private key entry is still mandatory.

---

## 5. Configuration

### 5.1 The two configuration layers

Both layers read the same properties files, but different keys:

| Layer | Reads | Examples |
|-------|-------|----------|
| **Spring Boot** | `server.*`, `management.*`, `spring.*`, `logging.*`, `springdoc.*`, `sentry.*` | `server.port` |
| **ph-config** (`com.helger.config`) | everything phoss-ap specific | `phossap.*`, `peppol.*`, `phase4.*`, `forwarding.*`, `storage.*`, `retry.*`, `org.apache.wss4j.*` |

Property keys are centralized in
[APConfigurationProperties.java](../phoss-ap-api/src/main/java/com/helger/phoss/ap/api/config/APConfigurationProperties.java)
— consult it before inventing a key.

### 5.2 Precedence (ph-config)

Higher number wins. Verified against ph-config 12.3.3:

| Priority | Source | Notes |
|---------:|--------|-------|
| 400 | Java system properties (`-Dphossap.jdbc.url=...`) | highest |
| 300 | OS environment variables (`PHOSSAP_JDBC_URL=...`) | key uppercased, `.` and `-` → `_` |
| 200 | `-Dconfig.file=/path/file.properties` (also `config.url`, `config.resource`) | **filesystem**, no rebuild needed |
| 190 | `private-application.properties` | classpath |
| 185 | `application-<profile>.properties` | classpath, loaded per active Spring profile |
| 180 | `application.properties` | classpath |
| 1 | `reference.properties` | library defaults |

Two consequences that trip people up:

- `application.properties`, `private-application.properties` and `application-<profile>.properties`
  are resolved on the **classpath only** — inside a fat jar that means *baked in at build time*.
  Dropping such a file next to the jar has **no** effect on ph-config.
- To change ph-config values **without rebuilding**, use `-Dconfig.file=...`, environment variables
  or `-D` system properties. *Spring Boot* keys (e.g. `server.port`) are unaffected by
  `config.file` — Spring does read an `application.properties` from the working directory, or use
  `--server.port=8081` on the command line.

### 5.3 Spring profiles

[SpringProfileConfigIntegration.java](../phoss-ap-webapp/src/main/java/com/helger/phoss/ap/webapp/config/SpringProfileConfigIntegration.java)
bridges the active Spring profiles into ph-config: starting with `--spring.profiles.active=dev`
additionally loads **`application-dev.properties`** from the classpath.

`application-dev.properties` and `application-prod.properties` are **git-ignored** (they hold
secrets) and live in `phoss-ap-webapp/src/main/resources/`. They are baked into the jar, so
**changing them requires a rebuild**.

### 5.4 Write the dev configuration

`application.properties` is the committed template; every value marked `[CHANGEME]` must be
reviewed. Create the dev overlay:

```sh
cd ~/git/peppol-ap
$EDITOR phoss-ap-webapp/src/main/resources/application-dev.properties
```

A complete, working dev file:

```properties
# === Paths ===
global.datapath=/home/<user>/phoss-ap-data/

# === Database ===
phossap.jdbc.database-type=postgresql
phossap.jdbc.driver=org.postgresql.Driver
phossap.jdbc.url=jdbc:postgresql://localhost:5432/phoss-ap
phossap.jdbc.user=peppol
phossap.jdbc.password=peppol
phossap.jdbc.schema=ap
phossap.flyway.enabled=true
phossap.flyway.jdbc.schema-create=true

# === Peppol identity ===
peppol.stage=test
peppol.owner.seatid=POP000306
peppol.owner.countrycode=AT
peppol.receiver-check.mode=none

# === AS4 keystore ===
org.apache.wss4j.crypto.merlin.keystore.type=pkcs12
org.apache.wss4j.crypto.merlin.keystore.file=/home/<user>/peppol/ap-test.p12
org.apache.wss4j.crypto.merlin.keystore.password=<keystore-password>
org.apache.wss4j.crypto.merlin.keystore.alias=<key-alias>
org.apache.wss4j.crypto.merlin.keystore.private.password=<key-password>
org.apache.wss4j.crypto.merlin.truststore.type=pkcs12
org.apache.wss4j.crypto.merlin.truststore.file=truststore/2025/ap-test-truststore.p12
org.apache.wss4j.crypto.merlin.truststore.password=peppol

# === SMP client ===
smpclient.truststore.type=PKCS12
smpclient.truststore.path=truststore/2025/smp-test-truststore.p12
smpclient.truststore.password=peppol

# === API security ===
phase4.api.requiredtoken=<a-long-random-dev-token>

# === Forwarding (this fork) ===
forwarding.mode=spi
forwarding.spi.id=middleware-data
forwarding.middleware.url=http://localhost:8888/receiver
forwarding.middleware.insecure-tls=false
```

Notes on the mandatory values:

| Key | Rule |
|-----|------|
| `global.datapath` | Absolute path, must be writable; inbound/outbound payloads and AS4 dumps go here. A relative value resolves against the process working directory. |
| `peppol.owner.seatid` | Syntax `P` + `O`/`A` + `P` + 6 digits (e.g. `POP000306`); startup aborts otherwise. |
| `peppol.owner.countrycode` | Valid ISO country code, e.g. `AT`, `DE`. |
| `peppol.stage` | `test` on a dev box — it selects the test CA and test SML. |
| `truststore` paths | Keep the `truststore/2025/*-test-*.p12` classpath values; they ship with `peppol-commons`. Use the `-prod-` variants only with `peppol.stage=prod`. |
| `phase4.api.requiredtoken` | Guards every `/api/**` endpoint via the `X-Token` header. Do not keep the template default. |

Leave `peppol.receiver-check.mode=none` for local work. `smp` and `sml` additionally require
`phase4.endpoint.address` (this AP's publicly reachable AS4 URL), which a dev box normally has not.

---

## 6. Build

```sh
cd ~/git/peppol-ap
JAVA_HOME="$JAVA_HOME" ./helper/build-phoss-ap.sh
```

[build-phoss-ap.sh](../helper/build-phoss-ap.sh) verifies that Maven and a JDK 21+ are present,
runs `mvn -B -pl phoss-ap-webapp -am clean package -DskipTests` and copies the resulting fat jar to
`dist/`. Overridable: `OUTPUT_DIR`, `RUN_TESTS=1`, `MVN`.

The plain Maven equivalents:

```sh
mvn clean install -DskipTests     # build everything + install into the local repo
mvn clean package -DskipTests -pl phoss-ap-webapp -am
```

Result: `dist/phoss-ap-webapp-<version>.jar` (currently `0.10.4-SNAPSHOT`) — a Spring Boot fat jar
with main class `com.helger.phoss.ap.webapp.PhossAPApplication`. Every other module is a library.

> `dist/` is git-ignored: the jar embeds `application-dev.properties` and therefore your secrets.
> Never commit or share it.

### 6.1 Tests need infrastructure

The full `mvn clean verify` requires external services:

- **PostgreSQL** on `localhost:5432` (db `phoss-ap`, user/password `peppol`, schema `ap`) — the
  webapp Spring-context test runs the Flyway migrations against it.
- **Docker** — `phoss-ap-basic` has a Testcontainers-based S3 integration test (`*S3IT`).

Without them use `-DskipTests` (all tests) or `-DskipITs` (integration tests only). Unit tests and
compilation pass on JDK 21 regardless.

### 6.2 Building where the default Java is older

`build-phoss-ap.sh` checks the JDK version up front and refuses to run below 21; it does **not**
download a JDK (unlike the systemd installer — build hosts are expected to have one). Point
`JAVA_HOME` at the side-by-side JDK 21 for the build only:

```sh
JAVA_HOME=/opt/jdk-21 ./helper/build-phoss-ap.sh
JAVA_HOME=/opt/jdk-21 mvn clean install -DskipTests
```

If the host already runs the service and therefore has the installer's private JDK, that one works
just as well for building:

```sh
JAVA_HOME=/opt/peppol-ap/jdk ./helper/build-phoss-ap.sh
```

`mvn` itself never needs to be reinstalled — it runs on whatever `JAVA_HOME` points to.

---

## 7. Run

### 7.1 Foreground (the normal dev loop)

```sh
cd ~/git/peppol-ap
java -jar dist/phoss-ap-webapp-*.jar --spring.profiles.active=dev
```

With the placeholder keystore from [step 4.2](#42-without-a-peppol-certificate-dev-fallback):

```sh
java -Dphossap.internal.skip-peppol-certificate-check=true \
     -jar dist/phoss-ap-webapp-*.jar --spring.profiles.active=dev
```

Startup is complete when the log shows the Tomcat port and the phase4 servlet registration.
`Ctrl+C` stops it. Useful ad-hoc overrides (they beat the baked-in file, see
[5.2](#52-precedence-ph-config)):

```sh
PHOSSAP_JDBC_URL=jdbc:postgresql://otherhost:5432/phoss-ap \
java -Dconfig.file=/etc/phoss-ap/local.properties \
     -jar dist/phoss-ap-webapp-*.jar --spring.profiles.active=dev --server.port=8081
```

> **If the default Java is older than 21**, a plain `java -jar` fails with
> `UnsupportedClassVersionError … class file version 65.0`. Call the JDK 21 explicitly:
>
> ```sh
> "$JAVA_HOME/bin/java" -jar dist/phoss-ap-webapp-*.jar --spring.profiles.active=dev
> /opt/peppol-ap/jdk/bin/java -jar dist/phoss-ap-webapp-*.jar --spring.profiles.active=dev
> ```

### 7.2 Background via the helper scripts

[start-phoss-ap.sh](../helper/start-phoss-ap.sh) / [stop-phoss-ap.sh](../helper/stop-phoss-ap.sh)
run the jar as a PID-file managed daemon. They default to `APP_HOME=/opt/peppol-ap`; on a dev box
point them at a user-owned directory:

```sh
export APP_HOME=~/phoss-ap
mkdir -p "$APP_HOME"
cp dist/phoss-ap-webapp-*.jar "$APP_HOME/"

./helper/start-phoss-ap.sh      # log: $APP_HOME/logs/phoss-ap.out, pid: $APP_HOME/pid/phoss-ap.pid
tail -f "$APP_HOME/logs/phoss-ap.out"
./helper/stop-phoss-ap.sh       # SIGTERM, escalating to SIGKILL after STOP_TIMEOUT (default 30s)
```

Environment overrides: `APP_HOME`, `APP_JAR`, `SPRING_PROFILE` (default `dev`), `JAVA_OPTS`,
`PID_FILE`, `LOG_FILE`, `STOP_TIMEOUT`.

The start script picks its JVM in the same order as the installer — `$JAVA_HOME`, then a
service-private JDK in `$APP_HOME/jdk`, then `java` on the `PATH` — prints which binary it chose,
and refuses to start on anything below JDK 21 ([9.1](#91-hosts-without-a-jdk-21-private-jdk)).
When it runs on the private JDK it also exports `JAVA_HOME=$JDK_DIR`, exactly like the systemd unit,
so a stale `JAVA_HOME` from the login shell cannot leak into the JVM's children.

The JVM is detached from the terminal via `nohup` (stdin from `/dev/null`), so closing the SSH
session does **not** kill the app — the PID file still holds the JVM's own PID.

### 7.3 Data directories

On first start the AP creates, below `global.datapath`:

```
inbound/          received StandardBusinessDocuments
outbound/         documents submitted for sending
phase4-dumps/     raw AS4 message dumps (phase4.dump.mode=grouped)
```

Payloads are stored on the filesystem (or S3, via `storage.*`), **not** in the database.

### 7.4 A forwarding target for inbound documents

This fork forwards every inbound document to the middleware `receiver` webservice
(`forwarding.mode=spi`, `forwarding.spi.id=middleware-data`, see
[CUSTOMIZATIONS.md](CUSTOMIZATIONS.md)). Without a listener on `forwarding.middleware.url` the AS4
reception still succeeds, but forwarding fails and is retried by the scheduler.

For local work either point `forwarding.middleware.url` at a stub that answers with a
`ProcessResult` XML, or switch to a built-in mode that needs no backend:

```properties
forwarding.mode=filesystem
forwarding.filesystem.directory=/home/<user>/phoss-ap-data/forwarded/
```

---

## 8. Verify the installation

```sh
# Spring Boot health (exposed endpoints: health, info)
curl -s http://localhost:8080/actuator/health
# -> {"status":"UP"}

# phoss-ap status (no token required)
curl -s http://localhost:8080/management/status

# OpenAPI document (no UI is bundled)
curl -s http://localhost:8080/openapi/v3/api-docs

# A token-protected API endpoint
curl -s -H "X-Token: <a-long-random-dev-token>" \
     http://localhost:8080/api/inbound/in-processing
```

Endpoint map:

| Path | Purpose | Auth |
|------|---------|------|
| `/as4` | AS4 reception (phase4 servlet, POST) | AS4/WSS |
| `/api/inbound/**` | Inbound status and reporting | `X-Token` |
| `/api/outbound/**` | Document submission for sending | `X-Token` |
| `/api/mls/**`, `/api/reporting/**` | MLS and Peppol reporting | `X-Token` |
| `/management/status` | Runtime status | none |
| `/actuator/health`, `/actuator/info` | Spring Boot actuator | none |

Also confirm the schema migration ran:

```sh
psql "postgresql://peppol:peppol@localhost:5432/phoss-ap" -c "\dn"
# expects the schemas: ap, report, reporting
```

---

## 9. Optional: install as a systemd service

For a long-running dev box, [install-phoss-ap-daemon.sh](../helper/install-phoss-ap-daemon.sh)
registers the jar as a systemd unit. It requires root, deploys the jar to `$APP_HOME` (default
`/opt/peppol-ap`) behind a stable `phoss-ap.jar` symlink, writes
`/etc/systemd/system/phoss-ap.service`, and runs `systemctl enable` — it **does not start** the
service.

The service user (default `ec2-user`) must already exist; the script verifies but never creates it.

```sh
sudo SERVICE_USER="$USER" ./helper/install-phoss-ap-daemon.sh
sudo systemctl start phoss-ap
systemctl status phoss-ap
journalctl -u phoss-ap -f
```

Overrides: `APP_HOME`, `SERVICE_NAME`, `SERVICE_USER`, `SERVICE_GROUP`, `SPRING_PROFILE`,
`JAVA_OPTS`, `JAVA_HOME`, or the jar as `$1`.

Operator overrides go into `$APP_HOME/phoss-ap.env` (read via `EnvironmentFile=-`), e.g.:

```sh
PHOSSAP_JDBC_URL=jdbc:postgresql://db.internal:5432/phoss-ap
PHOSSAP_JDBC_PASSWORD=...
```

### 9.1 Hosts without a JDK 21 (private JDK)

If the host runs an older Java — a Java 17 box is the typical case — the installer does **not**
touch the system Java. It probes, in order:

1. `$JAVA_HOME/bin/java`
2. a private JDK already provisioned in `$JDK_DIR` (default `$APP_HOME/jdk`)
3. `java` on the `PATH`

The first candidate reporting major version ≥ 21 wins. Step 2 comes before step 3 on purpose: a
`$JDK_DIR` only exists because this host had no suitable system Java, so a later `PATH` java must
not silently take over on a re-install — and `start-phoss-ap.sh`, which applies the same precedence,
would otherwise run the app on a different JVM than the systemd unit. `PREFER_PRIVATE_JDK=0` gives
you the old `PATH`-before-`$JDK_DIR` order; an explicit `$JAVA_HOME` always wins. If none does, the installer downloads an
**Eclipse Temurin JDK 21** into `$JDK_DIR` and uses it *only* for this service: the systemd unit
references it by absolute path (`ExecStart=$APP_HOME/jdk/bin/java …` plus
`Environment=JAVA_HOME=$APP_HOME/jdk`). Nothing is added to the `PATH`, `/usr/bin/java` and the
`alternatives` system are untouched, and every other application keeps using Java 17.

```sh
sudo SERVICE_USER="$USER" ./helper/install-phoss-ap-daemon.sh
# No suitable Java: '/usr/bin/java' reports major version 17, but JDK 21+ is required.
# Provisioning a service-private JDK in /opt/peppol-ap/jdk (the system Java is left untouched) ...
```

The download URL and its SHA-256 come from the Adoptium asset API
(`/v3/assets/latest/21/hotspot?...`); the archive is rejected on checksum mismatch. Re-running the
installer reuses an existing private JDK instead of downloading again.

| Variable | Default | Purpose |
|----------|---------|---------|
| `REQUIRED_JAVA_MAJOR` | `21` | Minimum acceptable Java major version |
| `JDK_DIR` | `$APP_HOME/jdk` | Where the private JDK is installed |
| `JDK_FEATURE` | `21` | Temurin feature release to fetch |
| `JDK_DOWNLOAD` | `1` | `0` = never download; fail with instructions instead |
| `JDK_ARCHIVE` | — | Local `jdk*.tar.gz` to install instead of downloading (air-gapped hosts) |
| `JDK_URL` | — | Explicit download URL (mirror); bypasses the Adoptium API |
| `JDK_SHA256` | — | Pin the expected checksum (required for a check with `JDK_URL`/`JDK_ARCHIVE`) |
| `PREFER_PRIVATE_JDK` | `1` | `0` = probe the `PATH` before an existing `$JDK_DIR` |

Air-gapped install from a tarball you copied over yourself:

```sh
sudo JDK_ARCHIVE=/tmp/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz \
     JDK_SHA256=e4446ff0...7370 \
     ./helper/install-phoss-ap-daemon.sh
```

Prefer a JDK you installed yourself? Point `JAVA_HOME` at it and no download happens:

```sh
sudo JAVA_HOME=/usr/lib/jvm/temurin-21 ./helper/install-phoss-ap-daemon.sh
```

`start-phoss-ap.sh` uses the same three-step probe (without the download), so the non-systemd path
picks up the private JDK too. Only `build-phoss-ap.sh` still requires a JDK 21+ of its own — build
machines are expected to have one.

Removal:

```sh
sudo ./helper/uninstall-phoss-ap-daemon.sh                # stop, disable, remove unit + jars
sudo REMOVE_JDK=1 ./helper/uninstall-phoss-ap-daemon.sh   # also delete the private JDK
sudo PURGE=1 ./helper/uninstall-phoss-ap-daemon.sh        # drop $APP_HOME entirely; the service user is kept
```

---

## 10. Redeploy after a change

`application-dev.properties` is baked into the jar, so config changes need the same cycle as code
changes:

```sh
cd ~/git/peppol-ap
JAVA_HOME="$JAVA_HOME" ./helper/build-phoss-ap.sh
sudo systemctl restart phoss-ap        # or: ./helper/stop-phoss-ap.sh && ./helper/start-phoss-ap.sh
```

To iterate on configuration **without** rebuilding, keep the value in an external file and start
with `-Dconfig.file=/etc/phoss-ap/local.properties`, or export it as an environment variable.

---

## 11. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `ERROR: JDK 21+ required, but ... reports major version '11'` | `JAVA_HOME` points at an old JDK | [1.6](#16-pin-java_home-for-phoss-ap) |
| `UnsupportedClassVersionError: ... class file version 65.0 ... up to 61.0` | The jar (built for Java 21 = 65.0) was started with Java 17 (= 61.0) | Start it with the JDK 21: `$JAVA_HOME/bin/java -jar …`, or use the helper scripts ([7.1](#71-foreground-the-normal-dev-loop), [9.1](#91-hosts-without-a-jdk-21-private-jdk)) |
| `No suitable Java: '/usr/bin/java' reports major version 17` | Host Java is older than 21 | The installer provisions a private JDK automatically ([9.1](#91-hosts-without-a-jdk-21-private-jdk)); with `JDK_DOWNLOAD=0` set `JAVA_HOME` or `JDK_ARCHIVE` instead |
| `ERROR: JDK archive checksum mismatch` | Corrupted or tampered download | Re-run; if it persists, download the tarball manually and pass `JDK_ARCHIVE` + `JDK_SHA256` |
| Service fails with `Exec format error` / `no such file` on the JDK path | `$APP_HOME/jdk` deleted while the unit still points at it | Re-run the installer, or `sudo systemctl edit --full phoss-ap` to point `ExecStart` at another JVM |
| `maven.compiler.release` / `invalid target release: 21` | Maven runs on an old JDK | Export `JAVA_HOME` before `mvn` |
| `InitializationException: Failed to load configured AS4 Key store` | Wrong keystore path, type or password | Check `org.apache.wss4j.crypto.merlin.keystore.*`; test with `keytool -list -keystore <file> -storetype pkcs12` |
| `Failed to load configured AS4 private key with the alias '...'` | Alias or key password wrong | Read the alias from the `keytool -list` output |
| `The provided certificate is not a Peppol AP certificate` | Self-signed / non-Peppol cert | Use a Peppol test cert, or [4.2](#42-without-a-peppol-certificate-dev-fallback) |
| `The configured Peppol Seat ID '...' does not match the syntactial requirements` | Wrong format | `P[OA]P` + 6 digits, e.g. `POP000306` |
| `Connection to localhost:5432 refused` | PostgreSQL not running / wrong URL | `docker compose -f unittest-db-docker-compose.yml ps`, `systemctl status postgresql` |
| Flyway `permission denied for database` | DB user may not create schemas | Make `peppol` the database owner ([3.2](#32-option-b--native-postgresql)) |
| `Port 8080 was already in use` | Another service on 8080 | `--server.port=8081`, or find the owner with `ss -lntp` |
| Config change has no effect | Value only in the classpath file of an already built jar | Rebuild, or override via `-Dconfig.file` / env var ([5.2](#52-precedence-ph-config)) |
| `No active Spring profiles` in the log | `--spring.profiles.active=dev` missing | Add it; without it `application-dev.properties` is never read |
| Inbound documents pile up as "forwarding failed" | No listener on `forwarding.middleware.url` | [7.4](#74-a-forwarding-target-for-inbound-documents) |

Raise the log level for a specific area with a Spring Boot key, e.g.
`--logging.level.com.helger.phoss=DEBUG`.

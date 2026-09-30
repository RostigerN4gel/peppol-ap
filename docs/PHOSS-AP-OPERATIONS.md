# Phoss-ap – Operations and Troubleshooting

This page describes how to **operate** and **troubleshoot** the Peppol Access Point based on **phoss-ap**. It is intended for everyone who deploys or monitors the AP or analyzes incidents.

- **Software:** [phax/phoss-ap](https://github.com/phax/phoss-ap) – open-source Peppol AP built on phase4 (Spring Boot). The runnable jar `phoss-ap-webapp-<version>.jar` is published on [Maven Central](https://repo1.maven.org/maven2/com/helger/phoss/ap/phoss-ap-webapp/).
- **Wiki:** [Home · phax/phoss-ap Wiki](https://github.com/phax/phoss-ap/wiki)
- **Role in the Peppol network:** C3 (receiving, forwarding to the middleware on the C4 side) and C2 (sending documents and MLS).
- **Helper scripts** for installation, jar switching and updates are part of this page (chapter 7).

---

## 1. System overview

| What                                | Value                                                                                                                         |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Host                                | `AS4-Dev` (Amazon Linux 2023)                                                                                  |
|                                     | `peppol-user`                                                                                                                    |
| Installation directory (`APP_HOME`) | `/opt/peppol-ap`                                                                                                              |
| Helper scripts on the server        | `/opt/peppol-ap/helper/`                                                                                                      |
| systemd service                     | `phoss-ap` (note: not `peppol-ap`)                                                                                            |
| Running jar                         | symlink `/opt/peppol-ap/phoss-ap.jar` → `phoss-ap-webapp-<version>.jar`                                                       |
| Java                                | service-private JDK 21 in `/opt/peppol-ap/jdk` (the system Java is left untouched)                                            |
| Configuration                       | `/opt/peppol-ap/application-dev.properties` (profile `dev`), optional `/opt/peppol-ap/phoss-ap.env` – see chapter 3           |
| Log file                            | `/opt/peppol-ap/logs/phoss-ap.log` (rotated and gzipped daily, 14 days)                                                       |
| AS4 message dumps                   | `/opt/peppol-ap/generated/phase4-dumps/grouped/YYYY/MM/DD/<ID>/`                                                              |
| Stored documents                    | `/opt/peppol-ap/generated/inbound/` and `.../outbound/`                                                                       |
| AS4 endpoint                        | `https://Middlewareserver/as4` (port 443)                                                                                     |
| SMP                                 | `https://smp-server.de`                                                                                                        |
| Peppol stage / Seat ID              | `test` / `PDE...`                                                                                                          |
| Database                            | PostgreSQL (AWS RDS), schemas `phossdev-ap`, `phossdev-reporting`, `phossdev-report` – schema migration via Flyway at startup |
| Forwarding to the middleware        | `forwarding.mode=http_post_sync` (see chapter 4)                                                                              |

---

## 2. Running the daemon

phoss-ap runs as the systemd service `phoss-ap` under the user `peppol-user`. The unit is created by the install script (chapter 7.1) and starts `/opt/peppol-ap/phoss-ap.jar` with the profile `dev`, the working directory `/opt/peppol-ap` and the private JDK. The service is *enabled*, i.e. it starts automatically at boot, and systemd restarts it after a crash (`Restart=on-failure`).

### 2.1 First installation

1. Copy the three scripts from chapter 7 to `/opt/peppol-ap/helper/` and make them executable: `chmod +x /opt/peppol-ap/helper/*.sh`
2. Download the jar (or use `update-phoss-ap-release.sh` later), e.g. into `/tmp`:  
`curl -fLO https://repo1.maven.org/maven2/com/helger/phoss/ap/phoss-ap-webapp/<version>/phoss-ap-webapp-<version>.jar`
3. Install the service (as root): `sudo /opt/peppol-ap/helper/install-phoss-ap-daemon.sh /tmp/phoss-ap-webapp-<version>.jar`  
The script copies the jar to `/opt/peppol-ap`, sets the `phoss-ap.jar` symlink, provisions a private JDK 21 if the host has none, writes `/etc/systemd/system/phoss-ap.service` and enables it. It does **not** start the service.
4. Put the configuration into `/opt/peppol-ap/application-dev.properties` (chapter 3.2 has the complete dev file) and copy the AS4 and TLS keystores to `/opt/peppol-ap`.
5. Start the service (chapter 2.2).

Overrides for the install script via environment: `APP_HOME`, `SERVICE_NAME`, `SERVICE_USER`, `SPRING_PROFILE`, `JAVA_OPTS`, `JAVA_HOME`, `JDK_DOWNLOAD=0`, `JDK_ARCHIVE=/path/to/jdk.tar.gz` (air-gapped hosts). The service user must already exist.

### 2.2 Start, stop, status

```bash
sudo systemctl start phoss-ap       # start
sudo systemctl stop phoss-ap        # shut down (graceful, max. 30 s)
sudo systemctl restart phoss-ap     # restart, e.g. after a config change or update
sudo systemctl status phoss-ap      # state, PID, last log lines
journalctl -u phoss-ap -f -o cat    # follow the console output
tail -f /opt/peppol-ap/logs/phoss-ap.log

sudo systemctl disable phoss-ap     # no longer start at boot
sudo systemctl enable phoss-ap      # start at boot again
```

**Startup succeeded** when the log shows:

- `Starting PhossAPApplication v<version> ... (/opt/peppol-ap/phoss-ap-webapp-<version>.jar ...)`
- `Loaded document forwarder: [HttpDocumentForwarder ... Mode=HTTP_POST_SYNC ...]`
- the Tomcat port and the phase4 servlet registration

A clean shutdown ends with exit code 143 – systemd treats that as success.

### 2.3 Update to a new phoss-ap release

1. Download the release and repoint the symlink (on the server):  
`cd /opt/peppol-ap && ./helper/update-phoss-ap-release.sh` – latest release  
`./helper/update-phoss-ap-release.sh 0.13.0` – a specific version  
The script verifies the SHA-256 from Maven Central, keeps the old jars and does not touch the configuration.
2. Read the release notes of the new version ([GitHub releases](https://github.com/phax/phoss-ap/releases)) for new or changed configuration keys and adjust `application-dev.properties` if needed. Comparing the template of the new version with chapter 3.5 shows new keys quickly.
3. Restart: `sudo systemctl restart phoss-ap` and check the startup log (chapter 2.2). New Flyway migrations run automatically at the first start.

> [!WARNING]
> **Rollback:** old jars stay in `/opt/peppol-ap`. Point the symlink back with `./helper/switch-phoss-ap-jar.sh phoss-ap-webapp-<old-version>.jar` and restart. Caution: Flyway migrations of the newer version are **not** rolled back – an older version may refuse to start against an already migrated database.

### 2.4 Switch to another jar

To run a jar that already lies in `/opt/peppol-ap` (older release, special build):

```bash
cd /opt/peppol-ap
./helper/switch-phoss-ap-jar.sh                                 # list jars and the current target
./helper/switch-phoss-ap-jar.sh phoss-ap-webapp-0.13.0.jar      # repoint the symlink
sudo systemctl restart phoss-ap
```

The script refuses files that do not exist, are smaller than 10 MB (not a fat jar) or would replace a non-symlink `phoss-ap.jar`. It never restarts the service itself.

### 2.5 Uninstall

```bash
sudo systemctl stop phoss-ap
sudo systemctl disable phoss-ap
sudo rm /etc/systemd/system/phoss-ap.service
sudo systemctl daemon-reload
# optional: remove jars, JDK, logs and data
# sudo rm -rf /opt/peppol-ap
```

---

## 3. Configuration (application properties)

### 3.1 Files, precedence and syntax

phoss-ap reads its configuration from several sources. A value from a higher source overrides the same key in all lower ones:

| Priority | Source | Where / how |
| --- | --- | --- |
| 1 (highest) | Java system properties | `-Dkey=value` in `JAVA_OPTS` of the systemd unit |
| 2 | Environment variables | `/opt/peppol-ap/phoss-ap.env` (read by the unit). Name = key in upper case, `.` and `-` replaced by `_`, e.g. `phossap.jdbc.url` → `PHOSSAP_JDBC_URL` |
| 3 | `application-dev.properties` | `/opt/peppol-ap/application-dev.properties` – loaded because the unit starts with `--spring.profiles.active=dev` and the working directory is `/opt/peppol-ap` |
| 4 (lowest) | `application.properties` | inside the jar – the upstream defaults (chapter 3.5) |

- **Changes need a restart** (`sudo systemctl restart phoss-ap`), but no new jar.
- **Two layers:** Spring Boot reads `server.*`, `spring.*`, `logging.*` and `management.*`; phoss-ap itself (ph-config) reads all other keys (`phossap.*`, `peppol.*`, `phase4.*`, `forwarding.*`, `retry.*`, `mls.*`, …). Both use the same files.
- **Variables:** `${other.key}` inserts the value of another key, e.g. `storage.inbound.path=${global.datapath}inbound/`.
- **Durations** use suffixes `ns`, `us`, `ms`, `s`, `m`, `h`, `d`, also combined: `1m 30s`, `2d 12h`.
- **Relative paths** (e.g. `generated/`) are relative to the working directory `/opt/peppol-ap`. Truststores like `truststore/2025/ap-test-truststore.p12` are loaded from the classpath (part of the jar).
- **Check the effective values:** the startup log lists every source with its values (search for `ConfigurationSourceProperties`, passwords are masked); `GET /management/status` shows the non-sensitive values of the running instance.

> [!NOTE]
> Only use jars from Maven Central. A jar that contains its own `application-dev.properties` overrides the file in `/opt/peppol-ap` silently, because both have the same priority and the copy inside the jar wins.

### 3.2 Dev instance: application-dev.properties

Effective configuration of the dev instance (as loaded at startup). Secrets are replaced by `****`, host names and keystore names by `<placeholders>` – the real values are only in the file on the server.

```properties
# --- General / Spring Boot ---
spring.application.name=phoss AP
global.debug=true
global.production=false
global.nostartupinfo=true
global.datapath=generated/

# HTTPS directly in the embedded Tomcat (port 443)
server.port=443
server.ssl.enabled=true
server.ssl.port=443
server.ssl.key-store=/opt/peppol-ap/<tls-keystore>.p12
server.ssl.key-password=****
server.ssl.key-store-password=****

# --- Logging ---
logging.file.name=/opt/peppol-ap/logs/phoss-ap.log
logging.logback.rollingpolicy.max-file-size=100MB
logging.logback.rollingpolicy.max-history=14
logging.logback.rollingpolicy.total-size-cap=2GB

# --- Document storage ---
storage.inbound.path=${global.datapath}inbound/
storage.outbound.path=${global.datapath}outbound/

# --- Database (PostgreSQL on AWS RDS) ---
phossap.jdbc.database-type=postgresql
phossap.jdbc.driver=org.postgresql.Driver
phossap.jdbc.url=jdbc:postgresql://<rds-host>:5432/peppol-ap
phossap.jdbc.user=<db-user>
phossap.jdbc.password=****
phossap.jdbc.schema=phossdev-ap
phossap.flyway.enabled=true
phossap.flyway.jdbc.url=${phossap.jdbc.url}
phossap.flyway.jdbc.user=${phossap.jdbc.user}
phossap.flyway.jdbc.password=${phossap.jdbc.password}
phossap.flyway.jdbc.schema-create=true

# --- Peppol identity ---
peppol.stage=test
peppol.owner.seatid=PDE....
peppol.owner.countrycode=DE

# --- API security (header X-Token for /api/**) ---
phase4.api.requiredtoken=****

# --- Receiver check / SMP ---
peppol.receiver-check.mode=sml
peppol.smp.url=https://smp-server.com

# --- AS4 / phase4 ---
phase4.endpoint.address=https://middleware-server/as4
phase4.dump.path=${global.datapath}phase4-dumps/
phase4.dump.mode=grouped

# AS4 keystore (Peppol AP certificate)
org.apache.wss4j.crypto.provider=org.apache.wss4j.common.crypto.Merlin
org.apache.wss4j.crypto.merlin.keystore.type=PKCS12
org.apache.wss4j.crypto.merlin.keystore.file=/opt/peppol-ap/<as4-keystore>.p12
org.apache.wss4j.crypto.merlin.keystore.password=****
org.apache.wss4j.crypto.merlin.keystore.private.password=****
org.apache.wss4j.crypto.merlin.keystore.alias=<key-alias>
org.apache.wss4j.crypto.merlin.load.cacerts=false
# Peppol AP truststore (test)
org.apache.wss4j.crypto.merlin.truststore.type=pkcs12
org.apache.wss4j.crypto.merlin.truststore.file=truststore/2025/ap-test-truststore.p12
org.apache.wss4j.crypto.merlin.truststore.password=****

# SMP truststore (test)
smpclient.truststore.type=PKCS12
smpclient.truststore.path=truststore/2025/smp-test-truststore.p12
smpclient.truststore.password=****

# --- Peppol reporting ---
peppol.reporting.schedule.day-of-month=2
peppol.reporting.schedule.hour=6
peppol.reporting.schedule.minute=7
peppol.reporting.jdbc.database-type=${phossap.jdbc.database-type}
peppol.reporting.jdbc.driver=${phossap.jdbc.driver}
peppol.reporting.jdbc.url=${phossap.jdbc.url}
peppol.reporting.jdbc.user=${phossap.jdbc.user}
peppol.reporting.jdbc.password=${phossap.jdbc.password}
peppol.reporting.jdbc.schema=phossdev-reporting
peppol.reporting.flyway.enabled=${phossap.flyway.enabled}
peppol.reporting.flyway.jdbc.url=${phossap.flyway.jdbc.url}
peppol.reporting.flyway.jdbc.user=${phossap.flyway.jdbc.user}
peppol.reporting.flyway.jdbc.password=${phossap.flyway.jdbc.password}
peppol.reporting.flyway.jdbc.schema-create=${phossap.flyway.jdbc.schema-create}
peppol.report.jdbc.database-type=${phossap.jdbc.database-type}
peppol.report.jdbc.driver=${phossap.jdbc.driver}
peppol.report.jdbc.url=${phossap.jdbc.url}
peppol.report.jdbc.user=${phossap.jdbc.user}
peppol.report.jdbc.password=${phossap.jdbc.password}
peppol.report.jdbc.schema=phossdev-report
peppol.report.flyway.enabled=${phossap.flyway.enabled}
peppol.report.flyway.jdbc.url=${phossap.flyway.jdbc.url}
peppol.report.flyway.jdbc.user=${phossap.flyway.jdbc.user}
peppol.report.flyway.jdbc.password=${phossap.flyway.jdbc.password}
peppol.report.flyway.jdbc.schema-create=${phossap.flyway.jdbc.schema-create}

# --- Duplicate detection ---
duplicate.detection.as4.mode=reject
duplicate.detection.sbdh.mode=reject

# --- Forwarding to the middleware ---
forwarding.mode=http_post_sync
forwarding.http.endpoint=https://<middleware-host>/dw/request/peppol/v2/inbound
forwarding.c4countrycode.modes=receiver_pid,business_card

# --- Retry ---
retry.forwarding.max-attempts=0
retry.sending.max-attempts=0
retry.scheduler.interval=1m
retry.scheduler.batch-size=50

# --- MLS ---
mls.sending.enabled=true
mls.sending.trigger=api
mls.sending.api.timeout=1h
mls.sending.api.timeout.code=AB

# --- Archival / cleanup ---
archival.scheduler.enabled=true
archival.scheduler.interval=24h
cleanup.scheduler.enabled=false
cleanup.scheduler.interval=24h
cleanup.scheduler.retention=90d
cleanup.scheduler.batch-size=100

# --- Monitoring / misc ---
sentry.send-default-pii=true
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB
springdoc.api-docs.path=/openapi/v3/api-docs
management.endpoints.web.exposure.include=*
management.endpoint.shutdown.enabled=true
endpoints.shutdown.enabled=true
```

### 3.3 Key reference

The most important keys, grouped by topic. "Default" is the value phoss-ap uses when the key is not set (upstream template or built-in default), "Dev" the value of the dev instance.

#### General, web server and logging

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `server.port` | `8080` | `443` | HTTP(S) port of the embedded Tomcat (AS4 endpoint and REST API) |
| `server.ssl.enabled`, `server.ssl.key-store`, `…key-store-password`, `…key-password` | off | on | TLS directly in Tomcat with the given PKCS12 keystore (server certificate, not the Peppol AP certificate) |
| `global.datapath` | `generated/` | `generated/` | base directory for documents and dumps (relative to `/opt/peppol-ap`) |
| `global.debug` / `global.production` | – | `true` / `false` | ph-commons runtime flags; production instances should run with `debug=false`, `production=true` |
| `logging.file.name` | console only | `/opt/peppol-ap/logs/phoss-ap.log` | log file |
| `logging.logback.rollingpolicy.*` | – | 100MB / 14 days / 2GB | rotation: max. file size, days kept, total size cap |
| `logging.level.<package>` | `INFO` | – | log level per package, e.g. `logging.level.com.helger.phoss=DEBUG` |

#### Storage and database

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `storage.mode` | `filesystem` | – | `filesystem` or `s3` (then `storage.s3.*`) |
| `storage.inbound.path` / `storage.outbound.path` | `${global.datapath}inbound/` / `outbound/` | same | where received and sent documents (SBD) are stored |
| `phossap.jdbc.database-type`, `.driver`, `.url`, `.user`, `.password` | local PostgreSQL | AWS RDS | main database; `postgresql` or `mysql` |
| `phossap.jdbc.schema` | `ap` | `phossdev-ap` | schema of the transaction tables (PostgreSQL only) |
| `phossap.jdbc.pooling.*` | 8 connections | – | connection pool; change with care |
| `phossap.flyway.enabled`, `phossap.flyway.jdbc.*` | `true` | `true` | automatic schema migration at startup; `schema-create=true` creates missing schemas |
| `peppol.reporting.jdbc.*` / `peppol.report.jdbc.*` | schemas `reporting` / `report` | `phossdev-reporting` / `phossdev-report` | databases of the Peppol reporting (usually the same connection, own schemas) |

#### Peppol identity, AS4 and certificates

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `peppol.stage` | `test` | `test` | `test` or `prod` – must match certificate, truststores and SML |
| `peppol.owner.seatid` | example | `PDE...` | own Peppol Seat ID (`P[OA]P` + 6 digits); must match the certificate |
| `peppol.owner.countrycode` | example | `DE` | country of the AP operator (reporting) |
| `phase4.endpoint.address` | – | `https://dev-as4-…/as4` | public AS4 URL of this AP, as registered in the SMP |
| `org.apache.wss4j.crypto.merlin.keystore.*` | example keystore | AP keystore | Peppol AP certificate and private key (`type`, `file`, `password`, `alias`, `private.password`) |
| `org.apache.wss4j.crypto.merlin.truststore.*` | `truststore/2025/ap-test-truststore.p12` | same | Peppol AP CA; for production `ap-prod-truststore.p12` |
| `smpclient.truststore.*` | `truststore/2025/smp-test-truststore.p12` | same | Peppol SMP CA; for production `smp-prod-truststore.p12` |
| `peppol.revocation.soft-fail` | `false` | – | `true` = accept certificates if the CRL/OCSP check cannot be performed |
| `phase4.dump.mode` / `phase4.dump.path` | `grouped` / `${global.datapath}phase4-dumps/` | same | dump of all AS4 messages per message (chapter 5.2) |
| `phase4.send.timeout.connection.ms`, `.request.ms`, `.response.ms` | 5000 / 10000 / 30000 | – | timeouts for outbound AS4 sending |
| `phase4.api.requiredtoken` | example | set | value of the header `X-Token` required for all `/api/**` calls |

#### Receiver check and SMP

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `peppol.receiver-check.mode` | `none` | `sml` | check whether the receiver of an inbound message is serviced: `none`, `smp` (fixed SMP from `peppol.smp.url`) or `sml` (lookup per participant). Not serviced → AS4 error `PEPPOL:NOT_SERVICED` |
| `peppol.smp.url` | – | Dev SMP | SMP for `receiver-check.mode=smp` |
| `peppol.smp.cache.enabled`, `.ttl`, `.max-size` | `true`, `15m`, `1000` | – | cache for outbound SMP lookups |
| `peppol.smp.timeout.connect` / `.response` | `5s` / `10s` | – | timeouts of all SMP queries |
| `peppol.dns.servers` | system DNS | – | DNS servers for SML lookups |

#### Forwarding to the middleware

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `forwarding.mode` | `http_post_async` | `http_post_sync` | `http_post_sync`, `http_post_async`, `s3_link`, `sftp`, `filesystem` or `spi` (chapter 4) |
| `forwarding.http.endpoint` | example | middleware URL | target URL of the HTTP forwarding |
| `forwarding.http.headers.<n>.name` / `.value` | – | – | additional HTTP headers for the forwarding request (e.g. authentication), numbered from 1 |
| `forwarding.http.verification-details` | `false` | – | also send the verification findings in `X-Verification-Details` |
| `forwarding.c4countrycode.modes` | – | `receiver_pid,business_card` | how the C4 country code for Peppol reporting is determined if the middleware does not return `countryCodeC4`: from the receiver participant ID and/or the Peppol business card |
| `forwarding.mls-copy.*` | disabled | – | send a copy of every MLS this AP generates to a separate sink |
| `forwarding.secondary.<n>.*` | – | – | additional fire-and-forget forwarders |

#### Retry, circuit breaker, duplicates

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `retry.forwarding.max-attempts` | `5` | `0` | forwarding retries; `0` = the first failure is final (`permanently_failed`, MLS `AB`) |
| `retry.forwarding.initial-backoff`, `.backoff-multiplier`, `.max-backoff` | `1m`, `2.0`, `1h` | – | exponential backoff between forwarding retries |
| `retry.sending.max-attempts` | `5` | `0` | retries for outbound AS4 sending (documents and MLS) |
| `retry.sending.initial-backoff`, `.backoff-multiplier`, `.max-backoff` | `3m`, `2.0`, `1h` | – | backoff between sending retries |
| `retry.scheduler.interval` / `.batch-size` | `1m` / `50` | same | how often the retry scheduler runs and how many transactions it takes per run |
| `circuit-breaker.failure-threshold` | `5` | – | consecutive failures until a remote system (middleware, SMP, remote AP) is suspended |
| `circuit-breaker.open-duration` | `1m` | – | how long a suspended system is not called |
| `circuit-breaker.failure-executions`, `.failure-period`, `.failure-rate` | – | – | alternative thresholding (failures over the last N calls / time window, in percent) |
| `duplicate.detection.as4.mode` / `.sbdh.mode` | `store_and_flag` | `reject` | duplicate AS4 message ID / SBDH instance ID: `reject` = AS4 error to C2, `store_and_flag` = accept and mark |

#### MLS

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `mls.sending.enabled` | `true` | `true` | send MLS at all |
| `mls.type` | `failure_only` | – | when an MLS is requested: `failure_only` or `always_send` (the sender's SBDH can override it) |
| `mls.sending.trigger` | `auto` | `api` | positive MLS immediately after forwarding (`auto`) or only when the middleware calls `POST /api/mls/send` (`api`). RE and AB after failures are always sent automatically |
| `mls.sending.api.timeout` | `5m` | `1h` | with `api`: fallback MLS if the middleware has not reported within this time after reception |
| `mls.sending.api.timeout.code` | `AB` | `AB` | response code of the fallback MLS: `AB` or `AP` |

#### Verification, reporting, archival

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `verification.inbound.enabled` / `verification.outbound.enabled` | `false` | – | validate documents via the phorm Validation Service (`verification.phorm.url`, `.token`) |
| `verification.verifier-fail-mode` | `closed` | – | behavior if the validator is unavailable: `closed`, `open`, `deferred` |
| `peppol.reporting.schedule.day-of-month`, `.hour`, `.minute` | 2 / 6 / 7 | same | when TSR and EUSR are created and sent |
| `peppol.reporting.exclude.participant-ids` | – | – | participants excluded from reporting (e.g. monitoring traffic) |
| `archival.scheduler.enabled` / `.interval` | `true` / `24h` | same | move completed and reported transactions into the `_archive` tables |
| `cleanup.scheduler.enabled`, `.retention` | `false`, `90d` | `false` | delete archived transactions and their documents after the retention |

#### Monitoring and management

| Key | Default | Dev | Meaning |
| --- | --- | --- | --- |
| `management.endpoints.web.exposure.include` | `health,info` | `*` | which Spring Boot actuator endpoints are reachable under `/actuator/…` |
| `management.endpoint.shutdown.enabled` | `true` | `true` | actuator shutdown endpoint |
| `management.status.enabled` | `true` | – | `GET /management/status` |
| `startup.recovery.enabled` | `true` | – | at startup, reset transactions that were interrupted in status `forwarding` |
| `otel.enabled` | off | – | OpenTelemetry; everything else via the standard `OTEL_*` environment variables |
| `sentry.dsn` | off | – | error reporting to Sentry |
| `springdoc.api-docs.path` | `/openapi/v3/api-docs` | same | OpenAPI description of the REST API |

### 3.4 Remarks on the dev configuration

> [!WARNING]
> **Actuator endpoints:** `management.endpoints.web.exposure.include=*` together with `management.endpoint.shutdown.enabled=true` exposes all actuator endpoints – possibly including shutdown – on the public port 443. They are **not** protected by the `X-Token` (it only applies to `/api/**`). Recommendation: `health,info` as in the upstream default, at least outside of dev.

- **MLS timeout:** `mls.sending.api.timeout=1h` is longer than the Peppol MLS-1 SLA (99.5 % of all MLS within 20 minutes after reception). If the middleware does not call `POST /api/mls/send` in time, the fallback MLS is late. Upstream default: `5m`.
- **No retries** (`retry.forwarding.max-attempts=0`, `retry.sending.max-attempts=0`): every temporary error of the middleware or a remote AP is final at once. Intended for tests; for production the defaults (5 attempts with backoff) are more robust.
- **Production:** `peppol.stage=prod`, the `*-prod-truststore.p12` files, a production AP certificate, `global.debug=false` and `global.production=true`.

### 3.5 Upstream template: application.properties

The `application.properties` inside the jar (version 0.13.x) with all keys and their descriptions. Values set here are the defaults, commented keys show optional settings. Use it as reference when a new version is installed.

```properties
# phoss-ap application configuration
spring.application.name=phoss AP
server.port=8080
# [CHANGEME] Set your own base path
global.datapath=generated/

# === Logging ===

# Write to file
#logging.file.name=${user.home}/Library/Logs/phoss-ap/app.log
# Daily rotation with date in filename, gzip compressed
#logging.logback.rollingpolicy.file-name-pattern=${user.home}/Library/Logs/phoss-ap/app.%d{yyyy-MM-dd}.%i.log.gz
# Rotate within a day if a single file exceeds this (safety net)
#logging.logback.rollingpolicy.max-file-size=100MB
# Keep 90 days of history
#logging.logback.rollingpolicy.max-history=90
# Hard cap across all archived files combined
#logging.logback.rollingpolicy.total-size-cap=20GB

# === Document Storage ===
# Storage backend: "filesystem" (default) or "s3"
#storage.mode=s3
storage.inbound.path=${global.datapath}inbound/
storage.outbound.path=${global.datapath}outbound/

# S3 document storage backend (only used when storage.mode=s3)
#storage.s3.bucket=my-ap-storage
#storage.s3.region=eu-central-1
#storage.s3.access-key-id=
#storage.s3.secret-access-key=
# Custom endpoint for S3-compatible providers (MinIO, Garage, etc.)
#storage.s3.endpoint=https://s3.example.com
# Enable path-style access (required by most S3-compatible providers)
#storage.s3.path-style-access=true

# === Database (PostgreSQL) ===
phossap.jdbc.database-type=postgresql
phossap.jdbc.driver=org.postgresql.Driver
phossap.jdbc.url=jdbc:postgresql://localhost:5432/phoss-ap
phossap.jdbc.user=peppol
phossap.jdbc.password=peppol
# Only required for PostgreSQL
# Use empty String for MySQL
phossap.jdbc.schema=ap

# === Database (MySQL) ===
#phossap.jdbc.database-type=mysql
#phossap.jdbc.driver=com.mysql.cj.jdbc.Driver
#phossap.jdbc.url=jdbc:mysql://localhost:3306/phoss-ap?useUnicode=true&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false
#phossap.jdbc.user=peppol
#phossap.jdbc.password=peppol

# Generic JDBC parameters
# Duration values use suffixes: ns, us, ms, s, m, h, d (e.g. "1s", "1m 30s")
#phossap.jdbc.execution-time-warning.enabled=true
#phossap.jdbc.execution-time-warning=1s
#phossap.jdbc.debug.connections=false
#phossap.jdbc.debug.transactions=false
#phossap.jdbc.debug.sql=false

# JDBC Connection pooling - Handle with care
#phossap.jdbc.pooling.max-connections=8
#phossap.jdbc.pooling.max-wait=10s
#phossap.jdbc.pooling.between-evictions-runs=5m
#phossap.jdbc.pooling.min-evictable-idle=30m
#phossap.jdbc.pooling.remove-abandoned-timeout=5m

# Flyway JDBC configuration
phossap.flyway.enabled=true
phossap.flyway.jdbc.url=${phossap.jdbc.url}
phossap.flyway.jdbc.user=${phossap.jdbc.user}
phossap.flyway.jdbc.password=${phossap.jdbc.password}
# Only required for PostgreSQL
# Set to "false" for MySQL
phossap.flyway.jdbc.schema-create=true

# === Peppol Identity ===
peppol.stage=test
# [CHANGEME] Set your own Seat ID
peppol.owner.seatid=POP000306
# [CHANGEME] Set your own country code
peppol.owner.countrycode=AT

# === API Security ===
# [CHANGEME] Set your own token
phase4.api.requiredtoken=phoss-ap-development-token

# === Peppol Receiver Check ===
# Mode: "none" (disabled), "smp" (fixed SMP URL), "sml" (dynamic per-participant via SML)
#peppol.receiver-check.mode=none
# Required when mode=smp:
#peppol.smp.url=http://localhost:8080

# === Peppol DNS ===
#peppol.dns.servers=8.8.8.8,8.8.4.4

# === Peppol SMP Client Cache === (since 0.11.0)
# Outbound SMP lookups are cached in memory and shared between all SMP clients.
# Set enabled to false to query the SMP for every single message.
#peppol.smp.cache.enabled=true
#peppol.smp.cache.ttl=15m
#peppol.smp.cache.max-size=1000

# === Certificate Revocation === (since 0.9.0)
# When true, certificate validation succeeds even if the CRL/OCSP status cannot
# be determined (e.g. network error). Default is false.
#peppol.revocation.soft-fail=false

# === AS4 / phase4 ===
phase4.dump.path=${global.datapath}phase4-dumps/
phase4.dump.mode=grouped

# [CHANGEME] Set your own AS4 keystore
org.apache.wss4j.crypto.merlin.keystore.type=pkcs12
org.apache.wss4j.crypto.merlin.keystore.file=invalid-keystore-pw-peppol.p12
org.apache.wss4j.crypto.merlin.keystore.password=peppol
org.apache.wss4j.crypto.merlin.keystore.alias=private_key_for_pkcs12_certificate
org.apache.wss4j.crypto.merlin.keystore.private.password=peppol

org.apache.wss4j.crypto.merlin.truststore.type=pkcs12
# All these truststores are predefined, and are part of the peppol-commons library
#   See https://github.com/phax/peppol-commons/tree/master/peppol-commons/src/main/resources/truststore
#
# For Test only use:       truststore/2025/ap-test-truststore.p12
# For Production only use: truststore/2025/ap-prod-truststore.p12
org.apache.wss4j.crypto.merlin.truststore.file=truststore/2025/ap-test-truststore.p12
org.apache.wss4j.crypto.merlin.truststore.password=peppol

# === SMP ===
smpclient.truststore.type=PKCS12
# All these truststores are predefined, and are part of the peppol-commons library
#   See https://github.com/phax/peppol-commons/tree/master/peppol-commons/src/main/resources/truststore
#
# For Test only use:       truststore/2025/smp-test-truststore.p12
# For Production only use: truststore/2025/smp-prod-truststore.p12
smpclient.truststore.path=truststore/2025/smp-test-truststore.p12
smpclient.truststore.password=peppol

# === Peppol Reporting ===
peppol.reporting.schedule.day-of-month=2
peppol.reporting.schedule.hour=6
peppol.reporting.schedule.minute=7

# Comma separated list of participant IDs that are excluded from Peppol Reporting - e.g. for synthetic monitoring transactions
#peppol.reporting.exclude.participant-ids=iso6523-actorid-upis::9915:test,0088:1234567890128

peppol.reporting.jdbc.database-type=${phossap.jdbc.database-type}
peppol.reporting.jdbc.driver=${phossap.jdbc.driver}
peppol.reporting.jdbc.url=${phossap.jdbc.url}
peppol.reporting.jdbc.user=${phossap.jdbc.user}
peppol.reporting.jdbc.password=${phossap.jdbc.password}
# Only required for PostgreSQL
# Use empty String for MySQL
peppol.reporting.jdbc.schema=reporting

peppol.reporting.flyway.enabled=${phossap.flyway.enabled}
peppol.reporting.flyway.jdbc.url=${phossap.flyway.jdbc.url}
peppol.reporting.flyway.jdbc.user=${phossap.flyway.jdbc.user}
peppol.reporting.flyway.jdbc.password=${phossap.flyway.jdbc.password}
peppol.reporting.flyway.jdbc.schema-create=${phossap.flyway.jdbc.schema-create}

peppol.report.jdbc.database-type=${phossap.jdbc.database-type}
peppol.report.jdbc.driver=${phossap.jdbc.driver}
peppol.report.jdbc.url=${phossap.jdbc.url}
peppol.report.jdbc.user=${phossap.jdbc.user}
peppol.report.jdbc.password=${phossap.jdbc.password}
# Only required for PostgreSQL
# Use empty String for MySQL
peppol.report.jdbc.schema=report

peppol.report.flyway.enabled=${phossap.flyway.enabled}
peppol.report.flyway.jdbc.url=${phossap.flyway.jdbc.url}
peppol.report.flyway.jdbc.user=${phossap.flyway.jdbc.user}
peppol.report.flyway.jdbc.password=${phossap.flyway.jdbc.password}
peppol.report.flyway.jdbc.schema-create=${phossap.flyway.jdbc.schema-create}


# === Circuit Breaker ===
# Duration values use suffixes: ns, us, ms, s, m, h, d (e.g. "1m", "1m 30s")
#circuit-breaker.failure-threshold=5
#circuit-breaker.open-duration=1m
#circuit-breaker.half-open-max-attempts=3
# A rejection by a circuit breaker does not consume a retry attempt, because nothing was tried at
# all. This is the maximum age of a transaction for which that deferral is applied - older
# transactions fall back to the regular attempt counting, so that a permanently unreachable SMP or
# AP cannot defer a transaction forever.
#circuit-breaker.defer-max-duration=12h
#
# Failure thresholding. By default the circuit breaker opens after
# "circuit-breaker.failure-threshold" CONSECUTIVE failures, which can already be reached during a
# short load peak on an otherwise healthy SMP or AP. Two optional alternatives are available:
#
# 1. Count the failures over the last N executions instead of requiring them to be consecutive.
#    Must be >= "circuit-breaker.failure-threshold".
#circuit-breaker.failure-executions=20
#
# 2. Count them over a rolling time window instead. Note that this alone makes the circuit breaker
#    MORE sensitive, because the failures no longer have to be consecutive.
#circuit-breaker.failure-period=5m
#
# Only in combination with a failure rate in percent does the thresholding become tolerant of
# isolated failures: with the three values below the circuit breaker opens if at least half of the
# last 20 or more executions within 5 minutes failed.
#circuit-breaker.failure-rate=50

# === Peppol SMP client ===
# Timeouts for all SMP queries. The defaults equal the values that were hardcoded before 0.13.0.
#peppol.smp.timeout.connect=5s
#peppol.smp.timeout.response=10s

# === Duplicate Detection ===
#duplicate.detection.as4.mode=store_and_flag
#duplicate.detection.sbdh.mode=store_and_flag

# === Outbound Sending ===
#phase4.send.timeout.connection.ms=5000
#phase4.send.timeout.request.ms=10000
#phase4.send.timeout.response.ms=30000

# === Retry (Sending) ===
# Duration values use suffixes: ns, us, ms, s, m, h, d (e.g. "3m", "1h 30m")
#retry.sending.enabled=true
#retry.sending.interval.ms=30000
#retry.sending.batch-size=50
#retry.sending.max-attempts=5
#retry.sending.initial-backoff=3m
#retry.sending.backoff-multiplier=2.0
#retry.sending.max-backoff=1h

# === Outbound S3 Submission (sender uploads to S3, AP fetches) ===
#outbound.s3.enabled=true
#outbound.s3.bucket=sender-documents
#outbound.s3.region=eu-central-1
#outbound.s3.access-key-id=
#outbound.s3.secret-access-key=
# Custom endpoint for S3-compatible providers (MinIO, Garage, etc.)
#outbound.s3.endpoint=https://s3.example.com
# Enable path-style access (required by most S3-compatible providers)
#outbound.s3.path-style-access=true

# === Forwarding ===
#forwarding.mode=http_post_sync
#forwarding.http.endpoint=http://localhost:8888/forwarding/url/sync

forwarding.mode=http_post_async
forwarding.http.endpoint=http://localhost:8888/forwarding/url/async
# HTTP forwarding always sends "X-Verification-Result" (passed, rejected or unverified), as soon as
# an inbound verification produced a verdict. Set the following property to additionally send the
# findings as base64 encoded JSON in "X-Verification-Details". A long issue list is truncated to fit
# into the header - "X-Verification-Details-Truncated: true" marks that case, and the REST API stays
# the authoritative source for the complete list.
#forwarding.http.verification-details=true
# S3 forwarding endpoint override
#forwarding.s3.endpoint=https://s3.example.com
#forwarding.s3.path-style-access=true
# Name under which a forwarded document is stored - so the ".xml" of the document and the ".json" of
# the optional metadata sidecar are appended to it. Every default results in the same names as
# before, so nothing changes unless the property is set.
# Supported placeholders - only in the "{name}" syntax, because "${name}" is the variable syntax of
# the configuration itself and is already replaced before the forwarder sees the value:
#   {datetime}          Reception timestamp, formatted as yyyyMMddHHmmss
#   {incoming-id}       phase4 Incoming ID - the transaction ID for a self-generated MLS copy
#   {sbdh-instance-id}  SBDH Instance Identifier
#   {receiver-id}       Receiver participant ID incl. the scheme, e.g. iso6523-actorid-upis__0151_35747532810
#   {receiver-value}    Receiver participant ID without the scheme, e.g. 0151_35747532810
#   {sender-id}         Sender participant ID incl. the scheme
#   {sender-value}      Sender participant ID without the scheme
#   {doctype-id}        Document Type ID - note that this one alone is longer than 150 characters
#   {process-id}        Process ID
# Every character that is not a letter, a digit, '.', '-' or '_' is replaced with '_', so that the
# resulting name is the same on every operating system and can never point outside of the upload
# directory. Names longer than 200 characters are truncated. A pattern with an unknown placeholder,
# with unbalanced braces, with a "${name}" or with a path separator aborts the startup; a pattern that contains
# neither {incoming-id} nor {sbdh-instance-id} is accepted but logs a warning, because two documents
# may then be stored under the same name. Only for S3 the literal part of the pattern may contain
# '/', so that an object key can span "folders"; a placeholder value never contributes one.
#forwarding.sftp.filename-pattern={datetime}_{incoming-id}
#forwarding.filesystem.filename-pattern={sbdh-instance-id}
#forwarding.s3.filename-pattern={sbdh-instance-id}

# === Forwarding of the MLS copies ===
# A copy of every MLS this AP generates and sends itself (AP, AB and RE alike) can be handed to a
# separate sink - e.g. a reporting integration that builds a Tax Data Summary from it. It is a
# separate sink, because that is normally not the same endpoint as the invoice inbox.
# Disabled by default. The dispatch is fire-and-forget: a failure is logged and affects neither the
# MLS sending to C2 nor the inbound transaction status.
#forwarding.mls-copy.enabled=true
# Without an own mode the complete primary forwarder configuration above is reused
#forwarding.mls-copy.mode=http_post_async
#forwarding.mls-copy.http.endpoint=http://localhost:8888/forwarding/url/mls-copy

# === Retry (Forwarding) ===
# Duration values use suffixes: ns, us, ms, s, m, h, d (e.g. "1m", "1h 30m")
#retry.forwarding.enabled=true
#retry.forwarding.interval.ms=30000
#retry.forwarding.batch-size=50
#retry.forwarding.max-attempts=5
#retry.forwarding.initial-backoff=1m
#retry.forwarding.backoff-multiplier=2.0
#retry.forwarding.max-backoff=1h

# === Retry Scheduler ===
#retry.scheduler.interval=1m
#retry.scheduler.batch-size=50

# === Verification ===
# Enable optional document validation via the phorm Validation Service.
# Outbound documents are validated before AS4 sending; inbound documents
# before forwarding.
#verification.inbound.enabled=false
#verification.outbound.enabled=false
# What to do, if an inbound document verifier backend service is unavailable:
#   closed   - reject the document and send a negative MLS to C2 (default)
#   open     - forward the unverified document to C4
#   deferred - keep the document in status "verification_deferred" and re-verify it later
#verification.verifier-fail-mode=closed
# Only used with "deferred": the interval between two re-verification attempts and the maximum
# duration, relative to the reception of the document, after which it is rejected anyway
#verification.deferred.retry-interval=5m
#verification.deferred.max-duration=12h
# What to do with an inbound document that did not pass the verification. The rejection itself is
# always recorded and the negative MLS (RE) is always sent to C2 - this only decides if the
# rejected document nevertheless reaches C4:
#   none        - never forward it; the transaction ends in status "rejected" (default)
#   best-effort - fire-and-forget copy to the primary and all secondary forwarders, no retry
#   retry       - regular forwarding incl. retries; "verification_result" stays "rejected"
# In the two forwarding modes C2 receives exactly one MLS, namely the RE of the rejection.
#verification.inbound.rejection-forwarding=none
# Base URL of the phorm Validation Service (the built-in verifier calls its
# "/api/dd_and_validate/" endpoint). Required when verification is enabled.
#verification.phorm.url=http://localhost:8080
# The "X-Token" header value required by the phorm Validation Service for
# authentication. If empty, no token is sent.
#verification.phorm.token=

# === MLS ===
#mls.sending.enabled=true
#mls.type=failure_only
# When does phoss-ap send the *positive* MLS for a successfully forwarded document?
#   auto = immediately after successful forwarding (default)
#   api  = only when the Receiver Backend calls POST /api/mls/send
# Only the success path is deferred: the RE of a failed inbound verification and the AB of
# exhausted forwarding retries are always sent automatically.
#mls.sending.trigger=auto
# Only used with "api": if the backend has not reported a status within this duration after the
# RECEPTION of the document, phoss-ap sends a fallback MLS on its own. The check runs in the retry
# scheduler cycle, so the effective delay is up to one "retry.scheduler.interval" longer.
# MLS-1 of the Peppol Network Policy requires 99.5% of all MLS responses within 20 minutes and
# that clock starts when the document was received - keep enough room for the MLS sending itself.
# The window is measured from the reception and not from the forwarding on purpose: a forwarding
# that itself took long shortens the window of the backend instead of extending the SLA budget.
# Duration values use suffixes: ns, us, ms, s, m, h, d
#mls.sending.api.timeout=5m
# Response code used for the fallback: AB (acknowledging) or AP (acceptance)
#mls.sending.api.timeout.code=AB

# === Archival ===
# Duration values use suffixes: ns, us, ms, s, m, h, d (e.g. "1h", "1d", "24h").
archival.scheduler.enabled=true
archival.scheduler.interval=24h
#archival.scheduler.batch-size=100

# === Cleanup of archived transactions === (since 0.9.0)
# Periodically deletes archived transactions (and their document files) whose
# completed_dt is older than the retention. Operates ONLY on archive tables.
# Requires archival.scheduler.enabled=true. Minimum retention is 2 days.
# Retention must also be > 2 x archival.scheduler.interval.
# Duration values use suffixes: ns, us, ms, s, m, h, d (e.g. "90d", "2d 12h").
#cleanup.scheduler.enabled=false
#cleanup.scheduler.interval=24h
#cleanup.scheduler.retention=90d
#cleanup.scheduler.batch-size=100

# === Management ===
#management.status.enabled=true

# === Startup Recovery ===
#startup.recovery.enabled=true

# === OpenTelemetry Monitoring === (since 0.9.0)
# Set to true to bootstrap the OpenTelemetry SDK at application startup.
# All other OTel settings (endpoint, headers, sampling, resource attributes) come from
# the standard OTel environment variables / system properties, e.g.
#   OTEL_SERVICE_NAME=phoss-ap
#   OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
#   OTEL_EXPORTER_OTLP_PROTOCOL=grpc
#   OTEL_LOGS_EXPORTER=otlp
#   OTEL_METRICS_EXPORTER=otlp
#   OTEL_TRACES_EXPORTER=otlp
#   OTEL_RESOURCE_ATTRIBUTES=deployment.environment=test,peppol.seat.id=POP000306
##  OTEL_LOG_LEVEL=debug
# See https://opentelemetry.io/docs/languages/java/configuration/ for the full reference.
#otel.enabled=true

# === Sentry Monitoring ===
# Sentry is fully disabled if this is not present
#sentry.dsn=https://<key>@sentry.io/<project>

# Add data like request headers and IP for users,
# see https://docs.sentry.io/platforms/java/guides/spring-boot/data-management/data-collected/ for more info
sentry.send-default-pii=true

#sentry.logging.minimum-event-level=ERROR
#sentry.logging.minimum-breadcrumb-level=INFO

# === Spring Boot ===
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB

# === OpenAPI ===
# springdoc-openapi serves the spec at this base path (JSON) and "<path>.yaml" (YAML).
# No UI is bundled — only the raw OpenAPI document is exposed.
springdoc.api-docs.path=/openapi/v3/api-docs

# === Actuator ===
management.endpoints.web.exposure.include=health,info
management.endpoint.shutdown.enabled=true
endpoints.shutdown.enabled=true
```

---

## 4. Forwarding to the middleware (`http_post_sync`)

phoss-ap POSTs the stored Standard Business Document unchanged as `application/xml` to `forwarding.http.endpoint`, with the header `X-SBDH-Instance-ID` (and `X-Verification-Result` if inbound verification is active). The middleware answers synchronously with JSON.

| Middleware answer | Result |
| --- | --- |
| HTTP 2xx, JSON, optionally `{"countryCodeC4":"DE"}` | status `forwarded`; the C4 country code is used for Peppol reporting |
| HTTP 2xx with `{"retry":"none","errorMessage":"..."}` | status `permanently_failed` immediately, MLS `AB` |
| HTTP status ≥ 300 (body is not evaluated) | retry according to `retry.forwarding.*`; with `max-attempts=0` immediately `permanently_failed`, MLS `AB` |
| connection error, timeout, no JSON | same as HTTP error |
| circuit breaker open (no call made) | `forward_failed`, retried later |

**Important:** C2 always receives an AS4 receipt as soon as the document is stored – a forwarding failure is reported to the sender only via MLS. If the sender cannot receive an MLS, it does not learn about the failure.

**Circuit breaker:** after 5 consecutive failures (`circuit-breaker.failure-threshold`) the middleware is not called for 1 minute (`circuit-breaker.open-duration`). Check and reset via the REST API (chapter 5.3).

### 4.1 Status of an inbound message

| Status | Meaning | Next |
| --- | --- | --- |
| `received` | received and stored | being processed |
| `verification_deferred` | verification deferred (verifier unavailable) | automatic, or `reverify-and-forward` |
| `rejected` | content verification failed, MLS `RE` sent | end |
| `forwarding` | forwarding to the middleware in progress | – |
| `forwarded` | successfully handed over to the middleware | end |
| `forward_failed` | forwarding failed, retry scheduled | retry scheduler |
| `permanently_failed` | forwarding failed permanently, MLS `AB` | end (manually: `replay`) |

---

## 5. Monitoring

### 5.1 Logs

- Log file: `/opt/peppol-ap/logs/phoss-ap.log`. Every incoming AS4 message has an incoming ID as log prefix (`[d031621f-...]`); use it to find all lines belonging to one message.
- Debug logging for an area: set `logging.level.com.helger.phoss=DEBUG` in the configuration and restart.

### 5.2 AS4 message dumps

With `phase4.dump.mode=grouped`, incoming and outgoing AS4 messages are stored per message under `generated/phase4-dumps/grouped/YYYY/MM/DD/<incoming ID or AS4 message ID>/` (`*.as4in`, `*.as4out`, `metadata.json`). This is where you see, for example, the exact content of an EBMS error. The files are single-line:

```bash
tr -d '\n' < signalmessage.as4out | grep -o '<eb:Error .*</eb:Error>'
```

### 5.3 REST API

All `/api/**` calls require the header `X-Token: <phase4.api.requiredtoken>` (value from the configuration). The OpenAPI description is available at `/openapi/v3/api-docs`.

| Call | Purpose |
| --- | --- |
| `GET /management/status` | version, runtime data, non-sensitive configuration |
| `GET /actuator/health` | health check |
| `GET /api/inbound/status/{sbdhInstanceID}` | status of a received message |
| `GET /api/inbound/in-processing` | all inbound messages not yet completed |
| `GET /api/outbound/status/{sbdhInstanceID}` / `GET /api/outbound/in-transmission` | status of outbound messages |
| `GET /api/mls/missing` | inbound messages for which no MLS has been sent yet |
| `POST /api/mls/send` | trigger the MLS for an inbound message (middleware, with `mls.sending.trigger=api`) |
| `GET /api/ops/inbound/{sbdhInstanceID}/payload` | download the stored document |
| `POST /api/ops/inbound/{sbdhInstanceID}/replay` | re-trigger the forwarding of an inbound message |
| `POST /api/ops/inbound/{sbdhInstanceID}/reverify-and-forward` | catch up on a deferred verification |
| `GET /api/ops/circuit-breakers` / `POST /api/ops/circuit-breakers/{key}/reset` | show / reset circuit breakers (forwarding: `phoss-ap-forwarder`) |
| `GET /api/reporting/create-tsr/{year}/{month}` / `create-eusr/...` | create Peppol reports (sent automatically on the 2nd of each month) |

```bash
curl -s -H "X-Token: <token>" https://middleware-server.com/api/inbound/status/<sbdhInstanceID>
```

### 5.4 Background jobs

- **Retry scheduler** (every minute): retries failed forwardings and sendings according to `retry.*`.
- **Archival** (every 24 h): moves completed and reported transactions into the `_archive` tables.
- **Peppol reporting:** TSR/EUSR monthly (day 2, 06:07).

---

## 6. Troubleshooting

### 6.1 Procedure for an incident

1. **Is the service running?** `sudo systemctl status phoss-ap`
2. **Which version and which forwarder?** `grep -n 'Starting PhossAPApplication\|Loaded document forwarder:' /opt/peppol-ap/logs/phoss-ap.log | tail -4`
3. **Which jar is linked?** `ls -l /opt/peppol-ap/phoss-ap.jar`
4. **Find the message:** grep for the SBDH instance ID, AS4 message ID or transaction ID, then look at all lines of the message via the incoming ID (`[...]` prefix).
5. **Query the status:** `GET /api/inbound/status/{sbdhInstanceID}`
6. **What exactly did C2 receive?** Dump under `generated/phase4-dumps/grouped/<date>/<incoming ID>/`
7. **Show Accesspoint logs:** `tail -f /opt/peppol-ap/logs/phoss-ap.log`

Useful searches:

```bash
cd /opt/peppol-ap/logs
grep -n 'HTTP forwarding failed' phoss-ap.log | tail          # errors calling the middleware
grep -n 'Receiver indicated no retry' phoss-ap.log | tail     # middleware answered retry:none
grep -n 'Rejecting duplicate' phoss-ap.log | tail             # duplicates
grep -n 'Receiver not serviced' phoss-ap.log | tail           # receiver not serviced
grep -n 'could not be sent with phase4' phoss-ap.log | tail   # failed outbound sendings / MLS
grep -n ' ERROR ' phoss-ap.log | tail -30
zgrep -n '<sbdhInstanceID>' phoss-ap.log.2026-*.gz            # older days
```

### 6.2 Known symptoms

| Symptom | Cause | Fix |
| --- | --- | --- |
| Service does not start, `systemctl status` shows `failed` | see the last lines of `journalctl -u phoss-ap -n 100` and `phoss-ap.log` | depending on the error, see below |
| Configuration change has no effect | service not restarted, value overridden by `phoss-ap.env`/system property, or a jar with its own `application-dev.properties` | restart; check the startup log for `ConfigurationSourceProperties`; use the Maven Central jar (chapter 3.1) |
| Inbound messages end up `permanently_failed`, MLS `AB` sent | middleware returned an HTTP error, `retry:none`, or was unreachable | check `HTTP forwarding failed` in the log (contains the HTTP status and response body); after fixing: `replay` |
| Middleware is not called at all | circuit breaker open after several failures | check the middleware; `GET /api/ops/circuit-breakers`, reset if needed |
| C2 reports `Rejecting duplicate SBDH instance` | a document with the same SBDH ID was already received | intended; the sender must use a new SBDH instance ID |
| AS4 error `PEPPOL:NOT_SERVICED` | receiver/document type not registered in the SMP (`receiver-check.mode=sml`) | check the SMP registration |
| Outbound MLS `permanently_failed`, `AS4_ERROR_MESSAGE_RECEIVED` | the remote AP has no MLS document type in its SMP or rejects it | behavior of the remote side |
| MLS retry without log output | the reason is only stored in the DB (`error_details` of the outbound transaction) | check the DB or `GET /api/outbound/status/...` |
| `UnsupportedClassVersionError ... class file version 65.0` | jar started with Java 17 | the service uses `/opt/peppol-ap/jdk` (JDK 21); start manually only with this JDK |
| Service fails with `Exec format error` / `no such file` on the JDK path | `/opt/peppol-ap/jdk` deleted while the unit still points at it | re-run the install script |
| `Failed to load configured AS4 Key store` / `private key with the alias` | keystore path, type, password or alias wrong | check `org.apache.wss4j.crypto.merlin.keystore.*`, `keytool -list -keystore <file> -storetype pkcs12` |
| `The configured Peppol Seat ID '...' does not match the syntactial requirements` | wrong format of `peppol.owner.seatid` | `P[OA]P` + 6 digits, e.g. `PDE....` |
| `No active Spring profiles` in the log | `--spring.profiles.active=dev` missing | check the unit: `systemctl cat phoss-ap` |
| `Port 443 was already in use` / permission denied on port 443 | another service on the port, or missing permission for a privileged port | find the owner with `ss -lntp`; check `server.port` |
| Flyway error at startup after an update | new migrations of the new version, missing DB permissions | the DB user needs permission to create/alter objects in the schemas |
| Older version refuses to start after a rollback | the database was already migrated by the newer version | go forward again, or restore the DB backup |
| `switch-phoss-ap-jar.sh`: `not a regular file` / `only ... bytes` | wrong file name or incomplete upload | call the script without a parameter to list the jars; upload again |

### 6.3 Interventions

- **Repeat the forwarding** after the middleware is fixed: `POST /api/ops/inbound/{sbdhInstanceID}/replay`
- **Catch up on a deferred verification:** `POST /api/ops/inbound/{sbdhInstanceID}/reverify-and-forward`
- **Trigger an MLS manually:** `POST /api/mls/send`
- **Reset a circuit breaker:** `POST /api/ops/circuit-breakers/{key}/reset`, key from `GET /api/ops/circuit-breakers`

---

## 7. Helper scripts

POSIX `sh` scripts for the Linux server. Store them in `/opt/peppol-ap/helper/` and make them executable (`chmod +x`). All paths and names can be overridden via environment variables (see the script headers).

### 7.1 install-phoss-ap-daemon.sh

Installs phoss-ap as systemd service (chapter 2.1). Run as root: `sudo ./install-phoss-ap-daemon.sh /path/to/phoss-ap-webapp-<version>.jar`. Safe to re-run, e.g. to rewrite the unit.

```bash
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
# The service user/group (default: peppol-user) is expected to already exist; this
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
SERVICE_USER="${SERVICE_USER:-peppol-user}"
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
# expected to provide it (e.g. the pre-existing 'peppol-user').
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
```

### 7.2 switch-phoss-ap-jar.sh

Repoints the `phoss-ap.jar` symlink to a jar that already lies in `/opt/peppol-ap` (chapter 2.4). Does not restart the service.

```bash
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
```

### 7.3 update-phoss-ap-release.sh

Downloads the latest (or a given) phoss-ap release from Maven Central into `/opt/peppol-ap`, verifies its SHA-256 and repoints the symlink (chapter 2.3). Does not touch the configuration and does not restart the service.

```bash
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
```

---

## 8. Further reading

- <https://github.com/phax/phoss-ap> – source, README, configuration reference
- <https://github.com/phax/phoss-ap/releases> – release notes
- <https://repo1.maven.org/maven2/com/helger/phoss/ap/phoss-ap-webapp/> – runnable jars

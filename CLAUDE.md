# CLAUDE.md

Guidance for AI coding agents working in this repository.

## What this is

A **fork** of [phax/phoss-ap](https://github.com/phax/phoss-ap) (`upstream` remote) — an
open-source Peppol Access Point built on phase4, as a Spring Boot application. The fork adds a
custom inbound document forwarder; see [CUSTOMIZATIONS.md](docs/CUSTOMIZATIONS.md) for the full delta.
Keep fork-specific changes documented there.

## Prerequisites (hard requirements)

- **JDK 21+** — enforced via `maven.compiler.release=21` in the root `pom.xml`. Building with an
  older JDK fails. On this dev machine JDK 21 is at `C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot`;
  the default shell's `JAVA_HOME` may still point at JDK 11, so set it explicitly when building.
- **Maven 3.x**.

## Build & run

```bash
# Full build + install to local repo (tests skipped)
mvn clean install -DskipTests

# Build + export the runnable fat jar into dist/ (recommended)
JAVA_HOME=/path/to/jdk21 ./helper/build-phoss-ap.sh

# Start / stop the app as a daemon (see helper/ scripts; default APP_HOME=/opt/tomcat)
./helper/start-phoss-ap.sh
./helper/stop-phoss-ap.sh
```

The runnable artifact is the Spring Boot fat jar **`phoss-ap-webapp/target/phoss-ap-webapp-<version>.jar`**
(main class `com.helger.phoss.ap.webapp.PhossAPApplication`). The other modules are libraries.

### Tests need infrastructure

`mvn clean verify` (the full test phase) requires external services that are usually absent:

- **PostgreSQL** on `localhost:5432` (db `phoss-ap`, user/password `peppol`, schema `ap`) — the
  webapp Spring-context test runs Flyway migrations against it.
- **Docker** — `phoss-ap-basic` has a Testcontainers-based S3 integration test (`*S3IT`).

Without them, use `-DskipTests` (all tests) or `-DskipITs` (integration tests only). Unit tests and
compilation pass on JDK 21 regardless.

## Module layout & dependency direction

Maven reactor build order (each depends on those above it):

```
phoss-ap-api  →  phoss-ap-basic  →  phoss-ap-db / phoss-ap-forwarding  →  phoss-ap-core  →  phoss-ap-webapp
                                     (+ sentry, otel, dirsender, validation, testbackend, testsender)
```

**Dependency direction matters.** `phoss-ap-forwarding` depends only on `api` + `basic` and sits
*below* `phoss-ap-core`. Code that needs core types (e.g. `InboundHttpHeaderContext`) therefore
**cannot** live in `forwarding` — that is why the fork's forwarder lives in `phoss-ap-webapp` (the
top module that sees everything). Config property keys are centralized in
`phoss-ap-api/.../config/APConfigurationProperties.java` — consult it before inventing a key.

## Configuration model (two layers — important)

There are **two** configuration systems, both fed from the same properties files:

1. **Spring Boot** reads `server.*`, `management.*`, `spring.*`, `logging.*`.
2. **ph-config** (`com.helger.config`) reads the phoss-ap keys: `phossap.*`, `peppol.*`, `phase4.*`,
   `forwarding.*`, `storage.*`, `retry.*`, etc. Overridable by OS env vars (e.g. `PHOSSAP_JDBC_URL`)
   and system properties.

**Profile-specific config:** `SpringProfileConfigIntegration` bridges Spring profiles into ph-config.
Activating profile `dev` (`--spring.profiles.active=dev`) loads **`application-dev.properties`** in
addition to `application.properties`. The filename convention is `application-<profile>.properties`
(resolved by `ConfigFactory.addProfilePropertiesSources` in ph-config). `application-dev.properties`
lives in `phoss-ap-webapp/src/main/resources/`, is **git-ignored** (contains secrets), and is **baked
into the jar** at build time — so changing it requires a rebuild. `application.properties` is the
committed template with `[CHANGEME]` markers.

## Persistence

Relational **JDBC only** — PostgreSQL (default), MySQL (alternative), H2 (tests). Schema via
**Flyway**. There is **no MongoDB support** (relevant when migrating from phase4-peppol-standalone,
which used Mongo for reporting). Document *payloads* (the SBD bytes) are stored separately via
`DocumentPayloadManager` on filesystem or S3 (`storage.*`), not in the DB.

## Forwarding

Inbound documents are delivered via a forwarder selected by `forwarding.mode`
(`EForwardingMode`: `http_post_sync`, `http_post_async`, `s3_link`, `sftp`, `filesystem`, `spi`).
This fork uses `forwarding.mode=spi` with the custom `MiddlewareReceiverForwarder`
(`phoss-ap-webapp/.../webapp/middleware/`, SPI id `middleware-data`), registered via
`META-INF/services/com.helger.phoss.ap.api.spi.IDocumentForwarderProviderSPI`. It speaks a different
wire contract than the built-in HTTP forwarder (an `InboundPeppolRequest` XML envelope + AS4 headers,
answered by a `ProcessResult` XML). See [CUSTOMIZATIONS.md](docs/CUSTOMIZATIONS.md) for the rationale.

## Code conventions (helger style — match the surrounding code)

- Apache 2.0 license header on **every** source file (copy an existing header).
- **Space before `(`** in method declarations and calls: `foo (arg)`, `LOGGER.info (...)`.
- Field prefix `m_`; local/param Hungarian-ish prefixes: `s`=String, `a`=object/array, `b`=boolean,
  `e`=enum, `n`=int (e.g. `sUrl`, `aConfig`, `bInsecureTls`, `eMode`).
- Nullness via jspecify `@NonNull` / `@Nullable`; argument checks via `ValueEnforcer`; `toString()`
  via `ToStringGenerator`.
- Prefer the `com.helger.*` utility libraries already on the classpath over reinventing helpers.

## Repo-specific notes

- `helper/` holds the build/start/stop scripts plus `install-phoss-ap-daemon.sh` /
  `uninstall-phoss-ap-daemon.sh`, which register the jar as a **systemd** service (enabled on boot,
  not auto-started). POSIX `sh`, target a Linux `/opt/tomcat` daemon. See
  [CUSTOMIZATIONS.md](docs/CUSTOMIZATIONS.md#deployment-helper-scripts-fork-specific).
- `dist/` receives the exported jar from `build-phoss-ap.sh`. The jar embeds `application-dev.properties`
  secrets — do not commit `dist/`.
- Commit messages / PRs: this is a fork; keep upstream-mergeable changes minimal and isolate fork
  changes where practical.

# Fork customizations

This is a fork of [phax/phoss-ap](https://github.com/phax/phoss-ap) (`upstream` remote).

It adds a **custom inbound document forwarder** that forwards the Message to a Middleware `receiver` over a
webservice. The forwarding is plugged into phoss-ap's `IDocumentForwarderProviderSPI` mechanism.

## What was added / changed

| File | Type | Purpose |
|------|------|---------|
| `phoss-ap-core/.../core/inbound/InboundHttpHeaderContext.java` | **new** | Thread-local carrier that exposes the original AS4 HTTP request headers to the forwarder. |
| `phoss-ap-core/.../core/inbound/Phase4InboundMessageProcessorSPI.java` | **patched** | Populates/clears `InboundHttpHeaderContext` around inbound processing. |
| `phoss-ap-webapp/.../webapp/middleware/MiddlewareReceiverForwarder.java` | **new** | The `IDocumentForwarder` that builds the `InboundPeppolRequest` XML, POSTs it to `receiver` and parses `ProcessResult`. |
| `phoss-ap-webapp/.../webapp/middleware/MiddlewareReceiverForwarderProvider.java` | **new** | `IDocumentForwarderProviderSPI` with ID `middleware-data`. |
| `phoss-ap-webapp/.../resources/META-INF/services/com.helger.phoss.ap.api.spi.IDocumentForwarderProviderSPI` | **new** | Registers the provider for `ServiceLoader`. |
| `phoss-ap-webapp/.../resources/application.properties` | **patched** | Forwarding section switched to `mode=spi`, `spi.id=middleware-data`. |

## Why a custom SPI forwarder (vs. the built-in HTTP forwarder)

phoss-ap already ships several built-in forwarders, selected via `forwarding.mode`
(see `com.helger.phoss.ap.api.codelist.EForwardingMode`):

| Mode | Built-in behaviour |
|------|--------------------|
| `http_post_sync` | POSTs the raw stored SBD as `application/xml`; reads a **JSON** response (`countryCodeC4`, optional `retry:"none"` / `errorMessage`) and returns the C4 country code synchronously. |
| `http_post_async` | POSTs the raw stored SBD as `application/xml`; on HTTP 2xx it just reports success. The response body is **not** parsed; reporting happens asynchronously later. |
| `s3_link` | Stores the SBD in S3 and forwards a link/reference. |
| `sftp` | Uploads the SBD via SFTP. |
| `filesystem` | Writes the SBD to a local directory. |
| `spi` | Delegates to a deployment-provided `IDocumentForwarderProviderSPI` (this fork). |

The built-in HTTP forwarder (`com.helger.phoss.ap.forwarding.http.HttpDocumentForwarder`)
does **not** fit our Middleware `receiver` because the wire contract differs:

| Aspect | Built-in `http_post_*` | This fork's `MiddlewareReceiverForwarder` |
|--------|------------------------|-------------------------------------------|
| Request body | The **raw** StandardBusinessDocument bytes, as-is | A wrapping `InboundPeppolRequest` **XML envelope** (see contract below) |
| AS4 HTTP request headers | Not forwarded | Forwarded inside `<HttpRequestHeaders>` (via `InboundHttpHeaderContext`) |
| Peppol metadata / Base64 SBD | Not included (raw body only) | Explicit elements (`Sender`, `Receiver`, `CountryC1`, `InstanceIdentifier`, …) + Base64-encoded SBD |
| Success/reject signalling | HTTP status (+ JSON for sync) | XML `ProcessResult` with `Status` / `ErrorMessage` / `C4CountryCode` |
| C4 country code for reporting | JSON field `countryCodeC4` (sync only) | XML element `ProcessResult/C4CountryCode` |

In short: if the Middleware receiver could accept the simple "raw SBD body + JSON response"
contract, `forwarding.mode=http_post_sync` (or `_async`) would suffice and no custom code would
be needed. Because the receiver expects the `InboundPeppolRequest` envelope (including the original
AS4 HTTP headers) and answers with a `ProcessResult` XML document, the built-in forwarder cannot be
used and this SPI forwarder replicates that legacy contract instead.

## Configuration

```properties
forwarding.mode=spi
forwarding.spi.id=middleware-data
forwarding.middleware.url=http://your-host/receiver
forwarding.middleware.insecure-tls=false
```

## Request / response contract (unchanged from lobimpl)

Request POSTed as `application/xml`:

```xml
<InboundPeppolRequest>
  <HttpRequestHeaders>
    <HttpRequestHeader key="...">value</HttpRequestHeader>
    ...
  </HttpRequestHeaders>
  <Sender>...</Sender>
  <Receiver>...</Receiver>
  <CountryC1>...</CountryC1>
  <InstanceIdentifier>...</InstanceIdentifier>
  <DocumentTypeInstanceIdentifier>...</DocumentTypeInstanceIdentifier>
  <EBMSMessageID>...</EBMSMessageID>
  <ReceivedBusinessDocument><!-- Base64 of the StandardBusinessDocument --></ReceivedBusinessDocument>
</InboundPeppolRequest>
```

Expected response:

```xml
<ProcessResult>
  <Status>success</Status>
  <ErrorMessage/>
  <C3Id>...</C3Id>
  <EndUserId>...</EndUserId>
  <C4CountryCode>..</C4CountryCode>
  <JobNr>...</JobNr>
</ProcessResult>
```

## Behavioural differences vs. the old phase4-peppol-standalone

These are intrinsic to phoss-ap's architecture, not bugs:

1. **No AS4-level rejection.** In the old code a non-`success` `receiver` response threw and produced an
   AS4 error back to C2. In phoss-ap the AS4 receipt is already positive; forwarding is decoupled.
   A backend rejection is mapped to `ForwardingResult.failureNoRetry(...)` and surfaces via MLS +
   transaction status, **not** as an AS4 error to the sender.
2. **Peppol Reporting end-user ID.** phoss-ap's built-in inbound reporting uses
   `endUserID = receiverID` and the C4 country code from the forwarding response
   (`ProcessResult/C4CountryCode`). The `EndUserId` / `C3Id` / `JobNr` fields returned by `receiver`
   are logged/parsed but are **not** fed into the reporting item (the `ForwardingResult` API only
   carries the C4 country code). If you must use the `receiver`-provided `EndUserId`, extend
   `ForwardingResult` and `APPeppolReportingHelper.createInboundPeppolReportingItem`.
3. **HTTP headers only on first (synchronous) attempt.** `InboundHttpHeaderContext` is a thread-local
   populated on the reception thread. Retried forwards run on the `RetryScheduler` thread and will
   have **no** `<HttpRequestHeaders>` content. Business rejections are therefore marked no-retry;
   only transport errors are retried. Set `retry.forwarding.max-attempts=1` if headers must always be
   present, or persist the headers with the transaction for full retry fidelity.

## Build

Requires **JDK 21+** and Maven 3.x:

```bash
mvn clean verify
```

## Deployment helper scripts (fork-specific)

The `helper/` directory holds POSIX `sh` scripts (target: a Linux host) that are not part of
upstream:

| Script | Purpose |
| ------ | ------- |
| `build-phoss-ap.sh` | Build the runnable fat jar and export it into `dist/` (tests skipped by default; `RUN_TESTS=1` to include). |
| `install-phoss-ap-daemon.sh` | Install the jar as a **systemd** service. Runs as the pre-existing `ec2-user` user/group (verified but **not** created), deploys the jar to `$APP_HOME` (default `/opt/peppol-ap`) with a stable `phoss-ap.jar` symlink, writes `/etc/systemd/system/phoss-ap.service`, and runs `systemctl enable` (start on boot). **Does not start the service** — start it manually with `systemctl start phoss-ap`. Must run as root. Can be installed alongside other services (e.g. a tomcat-based one) without conflict. |
| `uninstall-phoss-ap-daemon.sh` | Stop + disable the service, remove the unit and deployed jars. Keeps `$APP_HOME`/logs unless `PURGE=1`. Never touches the shared service user. Must run as root. |
| `start-phoss-ap.sh` / `stop-phoss-ap.sh` | Lightweight PID-file based start/stop (no systemd) — an alternative to the daemon install for quick/manual runs. |

The systemd unit loads the `dev` Spring profile (so `application-dev.properties`, baked into the jar,
is applied) and reads optional operator overrides from `$APP_HOME/phoss-ap.env` (e.g.
`PHOSSAP_JDBC_URL=...`). Common overrides for the installer: `APP_HOME`, `SERVICE_NAME`,
`SERVICE_USER`, `SPRING_PROFILE`, `JAVA_OPTS`, `JAVA_HOME`.

```sh
sudo ./helper/install-phoss-ap-daemon.sh      # install + enable, not started
sudo systemctl start phoss-ap                  # manual start
sudo ./helper/uninstall-phoss-ap-daemon.sh     # remove (PURGE=1 to also drop $APP_HOME; user is kept)
```

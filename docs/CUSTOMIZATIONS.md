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
| `phoss-ap-webapp/.../webapp/forwarding/AS4RejectingHttpForwarder.java` + `…Provider.java` | **new** | SPI forwarder `http-sync-as4-reject`: the built-in `http_post_sync` forwarder, but an HTTP error status is rejected via AS4. See [AS4 rejection on HTTP error](#as4-rejection-on-http-error-fork-specific). |
| `phoss-ap-core/.../core/inbound/IAS4RejectingDocumentForwarder.java` | **new** | Optional forwarder interface to request an AS4 rejection of a failed synchronous forwarding. |
| `phoss-ap-core/.../core/inbound/InboundOrchestrator.java` | **patched** | `forwardDocument` overload with an AS4-rejection out-parameter; the synchronous path turns it into an EBMS error. |
| `phoss-ap-api/.../api/codelist/EInboundStatus.java` | **patched** | New final status `as4_rejected`. |
| `phoss-ap-db/.../db/InboundTransactionManagerJdbc.java` | **patched** | Duplicate detection ignores `as4_rejected`; lookups by AS4/SBDH ID prefer the retransmission. |
| `phoss-ap-core/.../core/outbound/OutboundOrchestrator.java` | **patched** | [AS4 timestamp in millisecond precision](#as4-timestamp-in-millisecond-precision-fork-specific) (Domibus compatibility). |
| `phoss-ap-webapp/.../webapp/sk/SKTDDService.java` + `SKTDDController.java` | **new** | [Peppol SK TDD](#peppol-sk-tax-data-document-tdd-fork-specific): `POST /api/sk/tdd/{C2\|C3}` builds, validates and sends a Slovak TDD. |
| `phoss-ap-webapp/pom.xml` | **patched** | Dependencies `peppol-sk-tdd` (+ `phive-xml` aligned to `${phive.version}`) and `peppol-sk-testfiles` (test). |

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

Request POSTed as `application/xml`, with these HTTP request headers:

| Header | Value |
|--------|-------|
| `Content-Type` | `application/xml` |
| `X-PHOSS-AP-TRANSACTION-ID` | The phoss-ap inbound transaction ID (`inbound_transaction.id`). **Fork addition**, not part of the lobimpl contract. Stays the same across retry attempts of the same transaction, so the Middleware can recognise a retry (e.g. after a lost response) and answer it idempotently instead of rejecting it as a duplicate. Sent as a header rather than an XML element so that receivers validating the envelope strictly are not affected. |

Body:

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
4. **Inbound MLS is `AB`, not `AP`.** See the section below.

## MLS acknowledging instead of acceptance (fork-specific)

`MiddlewareReceiverForwarder.isWithDeliveryConfirmation()` returns **`false`** — deliberately, even
though the `receiver` call is synchronous.

`InboundOrchestrator` (upstream, lines 504-522) picks the MLS response code for a successfully
forwarded document from exactly that method:

```java
final MlsOutcome aOutcome = APCoreMetaManager.getForwarder ().isWithDeliveryConfirmation ()
                            ? MlsOutcome.acceptance ()      // AP
                            : MlsOutcome.acknowledging ();  // AB
```

**Why `false`:** the Middleware confirms that *it* accepted the document, not that the document
reached C4. Delivery from Middleware to C4 happens afterwards, and its outcome can no longer
influence the MLS because MLS for that message is already closed. With `true` this AP sent `AP`
(acceptance) — a positive delivery confirmation it cannot back up, and a later Middleware→C4 failure
became unreportable. `AB` ("accepted, delivery not confirmed") is the honest statement for a
Middleware deployment.

**Consequence for C1/C2:** they see `RECEIVED_AB` instead of `RECEIVED_AP` for documents delivered
through this AP. This is a valid Peppol MLS response code, not a failure.

**Whether a positive MLS is sent at all** is decided by the sender since upstream 0.13.2
([phax/phoss-ap#110](https://github.com/phax/phoss-ap/issues/110)): an inbound transaction takes
the `MLS_TYPE` of the received SBDH; if it is missing or invalid, the Peppol default
`FAILURE_ONLY` applies. With `FAILURE_ONLY` the `AB` is only recorded, not sent — only negative
MLS (`RE`) go out. `mls.type` in the configuration now only affects the SBDH of **outbound**
documents (before, it was applied to inbound transactions as well).

**Unaffected:** the `AB` after exhausted forwarding retries (`InboundOrchestrator` 744-759) and the
`RE` after failed inbound verification (409-423) are independent of this method and unchanged.

**Not done here:** deferring the MLS until the backend reports the real C4 outcome. That is
[FR-001](https://github.com/RostigerN4gel/peppol-helper/blob/main/docs/feature-requests/FR-001-api-triggered-mls-sending.md); it needs an upstream hook
(a `mls.sending.trigger=api` switch in `InboundOrchestrator`). Emulating it fork-side would require
`mls.sending.enabled=false` plus a ~130-line reimplementation of `MlsHandler`, which was rejected as
a maintenance risk. This one-line change is the cheap part of that feature request and deliberately
does not work around the missing hook.

## AS4 rejection on HTTP error (fork-specific)

Addresses [FR-004](https://github.com/RostigerN4gel/peppol-helper/blob/main/docs/feature-requests/FR-004-as4-rejection-when-mls-undeliverable.md) in its simplest
form (the `always` variant, without an MLS probe). Upstream answers C2 with an AS4 Receipt in every
case and reports forwarding failures only via MLS; if that MLS is undeliverable, the outcome is lost.

```properties
forwarding.mode=spi
forwarding.spi.id=http-sync-as4-reject
# everything else exactly as for http_post_sync
forwarding.http.endpoint=http://your-host/forwarding/url/sync
```

`AS4RejectingHttpForwarder` delegates everything to the upstream `HttpDocumentForwarder` in
`http_post_sync` mode (same config keys, request, JSON response contract, including the
[transaction ID header](#transaction-id-header)). The only difference:

| Middleware answer on the **first, synchronous** attempt | Upstream `http_post_sync` | `http-sync-as4-reject` |
|---|---|---|
| 2xx | Receipt, forwarded | same |
| 2xx with `{"retry":"none"}` | Receipt, `permanently_failed`, MLS `AB` | same |
| IO error / timeout / no JSON | Receipt, retry per `retry.forwarding.*` | same |
| **HTTP status ≥ 300** | Receipt, retry per `retry.forwarding.*` | **EBMS error** (`EBMS_OTHER`, backend `errorMessage`), status `as4_rejected`, no retry, no MLS |
| **Circuit breaker open** (no call made) | Receipt, retry after `retry.forwarding.initial-backoff` | **EBMS error**, as above |

Details:

* The EBMS error detail sent to C2 is the `errorMessage` of the backend's JSON error body
  (`{"retry":"none","errorMessage":"..."}`, trimmed, at most 512 characters). **This text leaves
  the AP** — the backend must not put internal details into it. Without a usable `errorMessage`
  (no JSON, no body, open circuit breaker) C2 gets the generic *"Forwarding to the receiver backend
  failed - please retry later"*. The full HTTP error (status line, headers, body) stays in the log
  and in the transaction's `error_details`.
* `as4_rejected` transactions (and their stored payload) are kept for audit, but ignored by the
  duplicate detection, so a retransmission of the same SBDH instance / AS4 message is processed as a
  first delivery. REST lookups by SBDH instance ID then return the retransmission.
* Only the synchronous first attempt can reject via AS4 — once a retry is scheduled (not the case
  for an HTTP error with this forwarder), the Receipt has already been sent.
* Not applied to a document that already got a negative MLS (`RE`) from the inbound verification
  (`EVerificationRejectionForwarding`), so C2 never gets two contradicting answers.
* The circuit breaker opens after `circuit-breaker.failure-threshold` consecutive failures (HTTP
  errors count). While it is open no HTTP call is made; with this forwarder the message is then
  rejected via AS4 as well, with the same error text, instead of being accepted for a later retry.
  IO errors and timeouts also open the circuit breaker, so during an outage of that kind all messages
  are rejected via AS4 after the threshold is reached.
* `as4_rejected` rows are not archived (`getAllForArchival` only picks upstream final states).
* This deliberately deviates from the Peppol AS4 profile, which expects failures behind C3 to be
  reported via MLS only.

## Transaction ID header

Both HTTP-based forwarders send the phoss-ap transaction ID (`inbound_transaction.id`) as an HTTP
request header:

* `HttpDocumentForwarder` (`http_post_sync`, `http_post_async` and therefore also
  `http-sync-as4-reject`) sends **`X-phoss-AP-Transaction-ID`** next to `X-SBDH-Instance-ID`. This
  is **upstream** since 0.13.2 ([phax/phoss-ap#105](https://github.com/phax/phoss-ap/issues/105),
  requested as FR-006); the former fork patch (`X-PHOSS-AP-TRANSACTION-ID`) was dropped in favour of
  it. HTTP header names are case-insensitive, so receivers reading the old spelling keep working.
* `MiddlewareReceiverForwarder` (`middleware-data`) sends **`X-PHOSS-AP-TRANSACTION-ID`** — fork
  code, see the contract above.

The ID stays the same across retry attempts of a transaction, so the backend can recognise a retry
(e.g. after a lost response) and answer it idempotently instead of rejecting it as a duplicate. A
retransmission by C2 after an `as4_rejected` answer is a new transaction and gets a new ID.

## AS4 timestamp in millisecond precision (fork-specific)

`OutboundOrchestrator` truncates the outbound AS4 `eb:Timestamp` (and the matching
`as4_timestamp` of the sending attempt) to **milliseconds**. Upstream uses the full precision of
`APTimestampManager`, which on Linux / Java 21 yields up to 9 fraction digits. Domibus-based
receivers such as the European Commission AP (`webgate.ec.europa.eu/ec-peppol-ap-domibus`) reject
that with `EBMS:0003` / `[DOM_007]:Invalid xsd:datetime format:[2026-10-06T13:15:07.29147455Z]`,
although it is schema valid. This applies to all outbound sendings (documents, MLS, Peppol
Reporting). Upstream request drafted as
[FR-007](https://github.com/RostigerN4gel/peppol-helper/blob/main/docs/feature-requests/FR-007-as4-timestamp-millisecond-precision.md).

**Additionally set `phase4.compatibility.domibus=true`** on the servers. phase4 serializes the
timestamp without trailing zeros (`.290` → `.29`, `.000` → no fraction at all), which Domibus presumably also
rejects - that is what the flag exists for. It (`AbstractAS4Message.getAsSoapDocument`) rewrites the timestamp to exactly 3
fraction digits, but **only if** the milliseconds end in `0` - a value with more than 3 digits
such as `.29147455` is left unchanged. So neither measure is enough on its own:

| AS4 timestamp | only the flag | only the code fix | both |
|---|---|---|---|
| `.29147455` | `.29147455` ❌ | `.291` ✅ | `.291` ✅ |
| `.29047455` | `.290` ✅ | `.29` ❌ (presumably) | `.290` ✅ |

## Peppol SK Tax Data Document (TDD) (fork-specific)

Slovakia's 5-corner model (from 2027-01-01) requires the AP of a Slovak seller (C2) and buyer (C3)
to report every domestic invoice as a **Tax Data Document (TDD)** to the tax authority AP (C5). This
fork implements the **AP part** of it (stage 1 of FR-008): building, validating and sending a TDD.
The **orchestration** - when a TDD of type `S`, `R` or `D` is needed, combining the MLS of the
invoice and of the TDD (SRA tables 1 and 2), SLA timers and follow-ups - is done by the **Middleware**
(stage 2). The AP keeps no link between an invoice and its TDDs. The APIs and the process the
Middleware follows as C2 and C3 are described in [SK-TDD-MIDDLEWARE-EN.md](SK-TDD-MIDDLEWARE-EN.md)
(German: [SK-TDD-MIDDLEWARE.md](SK-TDD-MIDDLEWARE.md)).

TDD creation and validation come from
[`com.helger.peppol:peppol-sk-tdd`](https://github.com/phax/peppol-sk) (Apache 2.0; still *work
in progress* for the member review of the SK TDD specification, so the data model may change):
mapping of UBL Invoice / Credit Note, Invoice UUID v5 (SRA 9.2.3) and all three Schematron layers.

```properties
sk.tdd.enabled=true
# mandatory: this AP as reporter's representative (SK AP ID, scheme 0242)
sk.tdd.reporters-representative.participant-id=iso6523-actorid-upis::0242:<id>
# defaults
#sk.tdd.tax-authority.participant-id=iso6523-actorid-upis::0242:000357-TaxAuthority.SK
#sk.tdd.tax-authority.id=SK
#sk.tdd.c1-country-code=SK
```

`POST /api/sk/tdd/{reporterRole}` (`X-Token` like every `/api/**` call), body = the UBL 2.1
Invoice or Credit Note:

| Parameter | Default | Meaning |
|---|---|---|
| `reporterRole` (path) | – | `C2` (AP of the seller) or `C3` (AP of the buyer) |
| `type` | `S` | `S` submit, `R` resubmit, `D` disregard |
| `scope` | `D` | `D` domestic, `IC` intra-community, `INTL` international |
| `reportingParty` | seller / buyer `EndpointID` | Peppol participant ID of the reporting party; also the SBDH sender |
| `tddUUID` | random UUID v4 | UUID of the TDD |
| `sbdhInstanceID` | random | SBDH Instance Identifier of the TDD transmission |
| `send` | `true` | `false` = dry run: build and validate only, the TDD is returned as `tddXML` |
| `custom1`..`custom3` | – | stored with the outbound transaction |

The TDD is sent as a **regular outbound transaction** (document type
`busdox-docid-qns::urn:peppol:schema:sk-taxdata:1.0::TaxData##urn:peppol:taxdata:sk-1::1.0`, process
`cenbii-procid-ubl::urn:peppol:taxreporting`), so storage, retry, circuit breaker, status API and
the correlation of the MLS from C5 work as for every document. The JSON response contains
`tddUUID`, `reportedDocumentUUID` (the Invoice UUID v5), `sbdhInstanceID`,
`outboundTransactionID`, `sendingReport` and, if any, `validationWarnings`. A TDD that fails the
Schematron is not sent (HTTP 422 with `issues`). The MLS from C5 is visible via
`GET /api/outbound/status/{sbdhInstanceID}` of the TDD transmission.

Notes:

* A TDD must carry `MLS_TYPE=ALWAYS_SEND`. The AP puts the global `mls.type` into every outbound
  SBDH, so `mls.type` must stay at its default; otherwise the response carries a `warning`.
* With `verification.outbound.enabled=true` the outbound verifier (phorm) is also applied to the
  TDD. Check that the phorm version knows the SK TDD, or the TDD is rejected / handled per
  `verification.verifier-fail-mode`.
* The TDD carries no reference to the SBDH of the invoice transmission (SRA p. 40) - the builder
  of `peppol-sk-tdd` 0.2.2 has no field for it (FR-008, open question 2).
* Accreditation, the central SK SMP, the end-user token and the testbed are not covered here.

## Build

Requires **JDK 21+** and Maven 3.x:

```bash
mvn clean verify
```

`helper/build-phoss-ap.sh` builds the runnable fat jar and exports it into `dist/` (tests skipped by
default; `RUN_TESTS=1` to include). Local `application-*.properties` are **not** packaged
(`INCLUDE_PROFILE_CONFIG=1` to override), because a baked-in profile file wins over the one in
`$APP_HOME` on the server.

## Operations (separate repository)

This repository is kept as a pure fork with the code customizations. The operations scripts
(systemd install/start/stop/update for the Access Point and the phorm Validation Service), the
operations and installation documentation and the feature requests live in the private repository
[RostigerN4gel/peppol-helper](https://github.com/RostigerN4gel/peppol-helper):

- [PHOSS-AP-OPERATIONS.md](https://github.com/RostigerN4gel/peppol-helper/blob/main/docs/PHOSS-AP-OPERATIONS.md) – operations and troubleshooting of the AP
- [PHORM-OPERATIONS.md](https://github.com/RostigerN4gel/peppol-helper/blob/main/docs/PHORM-OPERATIONS.md) – the phorm Validation Service used by `verification.*`
- [INSTALL-LINUX-DEV.md](https://github.com/RostigerN4gel/peppol-helper/blob/main/docs/INSTALL-LINUX-DEV.md) – installation walkthrough on a Linux development system
- [feature-requests/](https://github.com/RostigerN4gel/peppol-helper/tree/main/docs/feature-requests) – feature requests (FR-001 …)

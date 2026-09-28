# FR-005: Optionally reject the inbound AS4 message when synchronous HTTP forwarding fails

**Type:** Feature request
**Affected version:** `0.13.1-SNAPSHOT` (`upstream/main` at `65720f5`)
**Affected modules:** `phoss-ap-api`, `phoss-ap-forwarding`, `phoss-ap-core`, `phoss-ap-db`
**Related:** [FR-004](FR-004-as4-rejection-when-mls-undeliverable.md) (analysis and the broader
`mls-undeliverable` variant)
**Status:** Implemented and running in a fork; this issue proposes an upstream-friendly shape of it.

## Summary

Add an opt-in switch for `forwarding.mode=http_post_sync`: if the Receiver Backend answers the
**synchronous first** forwarding attempt with an HTTP error status, C2 is answered with an AS4/EBMS
error instead of an AS4 Receipt. In that case phoss-ap schedules no retry and sends no MLS, because
C2 is expected to retransmit. The EBMS error detail carries the backend's `errorMessage`, using the
JSON contract `http_post_sync` already has.

Default `false`, so today's behaviour does not change.

## Motivation

Today C2 always gets a Receipt, and a failure behind C3 travels exclusively via MLS. During the MLS
transition many sending APs cannot receive an MLS (no MLS document type in their SMP). For them a
negative outcome is simply lost: C1/C2 believe the document was delivered. FR-004 has concrete
examples from a test AP.

For deployments whose backend decides synchronously (`retry.forwarding.max-attempts=0` or a
backend answering `{"retry":"none"}`), answering on the AS4 level is the only channel that
reliably reaches the sender.

## Current behaviour

* `InboundOrchestrator.forwardDocument` catches every forwarder failure and returns only `ESuccess`
  (`InboundOrchestrator.java:1374` ff.).
* The receive path does not turn a failure into a processing error
  (`InboundOrchestrator.java:1284`). Only processing errors become EBMS errors
  (`Phase4InboundMessageProcessorSPI.java:118`), and these are produced only for duplicates, an
  unserviced receiver and an unparseable inbound MLS.
* `HttpDocumentForwarder` maps a status ≥ 300 to a **retryable** `failure ("http_status", …)` and
  does not evaluate the response body (`HttpDocumentForwarder.java:337-341`). A
  `{"retry":"none","errorMessage":"…"}` is only honoured on a 2xx response.

A deployment-provided forwarder cannot change this, because the orchestrator swallows all results
and exceptions. So the feature needs a core change.

## Proposed solution

### 1. Configuration

```properties
# Only for forwarding.mode=http_post_sync. If the Receiver Backend answers the synchronous first
# attempt with an HTTP error status, answer C2 with an AS4/EBMS error instead of a Receipt - no
# retry, no MLS, C2 is expected to retransmit. Deliberate deviation from the Peppol AS4 profile.
#forwarding.http.as4-reject-on-error=false
```

Read with the forwarder's key prefix like the other `forwarding.http.*` keys, so it also works for
`forwarding.secondary.{n}.` (where it is simply never evaluated, see 3).

### 2. `ForwardingResult` carries the request (`phoss-ap-api`)

`phoss-ap-forwarding` sits below `phoss-ap-core`, so the signal belongs into the shared result type:

```java
/** Failure that asks the AP to reject the inbound message on the AS4 level. */
public static ForwardingResult failureRejectViaAS4 (@Nullable String sErrorCode,
                                                    @Nullable String sErrorDetails,
                                                    @Nullable String sAS4ErrorDetail);

public boolean isRejectViaAS4 ();

/** Text for the EBMS error to C2, or null for a generic default. Leaves the AP. */
@Nullable
public String getAS4ErrorDetail ();
```

### 3. `HttpDocumentForwarder` (`phoss-ap-forwarding`)

In the `ExtendedHttpResponseException` branch, if the switch is on and the mode is
`HTTP_POST_SYNC`: parse `ex.getResponseBodyAsString ()` as JSON, take `errorMessage` (trimmed,
bounded, e.g. 512 characters) and return `failureRejectViaAS4 ("http_status", ex.getMessage (),
sErrorMessage)`. IO errors, timeouts and 2xx responses keep today's handling.

### 4. `InboundOrchestrator` (`phoss-ap-core`)

* Overload `forwardDocument (sLogPrefix, aInboundTx, @Nullable Wrapper <String> aAS4Rejection)`;
  the existing signature delegates with `null`, so `RetryScheduler` and the deferred-verification
  path are unchanged. Only `processIncomingDocument` passes a wrapper.
* In the failure branch, before the retry decision: if a wrapper was passed,
  `aResult.isRejectViaAS4 ()` is set and `!isMlsSuppressedAfterRejection (aInboundTx)`, then set
  the new status `AS4_REJECTED` (no next retry) and store the text for C2 in the wrapper. That
  text is `getAS4ErrorDetail ()` or a generic *"Forwarding to the receiver backend failed - please
  retry later"*. Then return. No MLS and no permanent-failure handling.
* While the circuit breaker is open no HTTP call is made. If the configured forwarder has the
  switch on, the synchronous first attempt is rejected the same way (generic text) instead of
  being accepted for a later retry. Otherwise a backend outage turns into "Receipt + retry" after
  `circuit-breaker.failure-threshold` failures, which defeats the purpose.
* `processIncomingDocument` adds the stored text to `aProcessingErrors`, and the existing SPI code
  turns it into `EBMS_OTHER`.

### 5. New final status `AS4_REJECTED` (`phoss-ap-api`, `phoss-ap-db`)

`EInboundStatus.AS4_REJECTED ("as4_rejected")`, included in `isFinalState ()`. `status` is a
`VARCHAR(50)` without a check constraint, so no Flyway migration is needed.

## The duplicate detection problem (must be solved together)

The transaction is created before forwarding, and duplicate detection rejects by AS4 message ID and
SBDH instance ID (`InboundOrchestrator.java:1120`, `:1146`, default `reject`). Without a change, the
retransmission that the EBMS error is supposed to trigger would be answered with *"Rejecting
duplicate SBDH instance"*, forever.

Proposal:

* `containsByAS4MessageID` / `containsBySbdhInstanceID` ignore rows with status `as4_rejected`
  (`InboundTransactionManagerJdbc.java:181`, `:215`: `… AND status<>?`). The rejected row and its
  payload stay for audit.
* `getByAS4MessageID` / `getBySbdhInstanceID` (used by the REST API, e.g. `POST /api/mls/send`)
  now may see two rows. They should prefer the single non-`as4_rejected` row, and fall back to the
  most recent `as4_rejected` row if there is nothing else (`ORDER BY received_dt`). Otherwise they
  return `null` after a retransmission.
* `as4_rejected` rows are not picked by `getAllForArchival` (only the existing final states are).
  Whether they should be archived is open.

## Interaction matrix (switch on, first synchronous attempt)

| Backend answer | Today | With the switch |
|---|---|---|
| 2xx | Receipt, forwarded | same |
| 2xx with `{"retry":"none"}` | Receipt, `permanently_failed`, MLS `AB` | same |
| IO error / timeout / no JSON | Receipt, retry | same |
| **HTTP status ≥ 300** | Receipt, retry | **EBMS error** with backend `errorMessage`, `as4_rejected`, no retry, no MLS |
| **Circuit breaker open** | Receipt, retry later | **EBMS error** (generic text), `as4_rejected` |
| any, but already answered with MLS `RE` by the verification | — | unchanged (no second, contradicting answer) |

Retries by the `RetryScheduler` never reject via AS4, because the AS4 response was already sent.

## Peppol conformance

The Peppol AS4 profile expects C3 to accept a transport-valid message for a serviced receiver and
to report downstream failures via MLS. This switch is a deliberate deviation. It must therefore be
opt-in, default `false`, and documented as such. The documentation should also say that it makes C2
retransmit, and that the backend's `errorMessage` leaves the AP.

## Acceptance criteria

* [ ] Default `false` reproduces today's behaviour.
* [ ] With the switch on, an HTTP error status on the first synchronous attempt yields an
      `EBMS_OTHER` error to C2, status `as4_rejected`, no retry and no MLS.
* [ ] The EBMS error detail is the backend's `errorMessage` if present, otherwise a generic text.
      The full HTTP error stays in the log and in `error_details`.
* [ ] IO errors, timeouts, 2xx and `retry:none` keep today's behaviour.
* [ ] An open circuit breaker rejects via AS4 only for a forwarder with the switch on.
* [ ] A retransmission of the same SBDH instance / AS4 message ID after a rejection is processed,
      not rejected as a duplicate, and REST lookups return the retransmission.
* [ ] Retries (`RetryScheduler`) and all other forwarding modes are unaffected.

## Reference implementation

A working version exists in a fork. It differs in one point, because the fork keeps upstream modules
untouched:

* Instead of a `ForwardingResult` flag it uses an optional core interface
  `IAS4RejectingDocumentForwarder { boolean isRejectViaAS4 (ForwardingResult); default String
  getAS4ErrorDetail (ForwardingResult) }`, implemented by a thin SPI forwarder
  (`http-sync-as4-reject`) that wraps the unchanged `HttpDocumentForwarder`.
* That wrapper has to recover the response body from the text of
  `ExtendedHttpResponseException.getMessage ()`. In upstream this is unnecessary:
  `HttpDocumentForwarder` can read `ex.getResponseBodyAsString ()` directly, which is why this issue
  proposes the `ForwardingResult` shape.

Everything else (orchestrator hook, `AS4_REJECTED`, duplicate and lookup handling, circuit-breaker
case, tests on H2) is directly transferable. Happy to open a PR if the direction is acceptable.

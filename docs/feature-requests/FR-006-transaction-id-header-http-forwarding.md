# FR-006: Send the transaction ID as an HTTP header when forwarding via HTTP

**Type:** Feature request
**Affected version:** `0.13.2-SNAPSHOT` (`upstream/main` at `d57aa5e`)
**Affected modules:** `phoss-ap-forwarding`
**Status:** Implemented in a fork; a one-line change.

## Summary

`HttpDocumentForwarder` (`http_post_sync`, `http_post_async`) should send the phoss-ap transaction
ID (`IForwardableDocument.id ()`) as an HTTP request header, for example
`X-PHOSS-AP-TRANSACTION-ID`, next to the existing `X-SBDH-Instance-ID`.

## Motivation

The Receiver Backend has no reliable way to learn which phoss-ap transaction a forwarded document
belongs to:

* **The SBDH Instance ID is not a unique key for a transaction.** With
  `duplicate.detection.sbdh.mode=store_and_flag` several transactions can share it. Duplicate
  detection only looks at the active `inbound_transaction` table, not at
  `inbound_transaction_archive`, so the same SBDH Instance ID can come in again after archival.
  A lookup via `GET /api/inbound/status/{sbdhInstanceID}` then needs an extra round trip, needs the
  API token and network access from the backend to the AP, and can still be ambiguous.
* **Retry vs. new delivery.** If the backend processed a document but the response was lost
  (connection reset, proxy timeout, 5xx after commit), phoss-ap retries the same transaction. The
  backend sees what looks like a duplicate and rejects it. With the transaction ID it can tell a
  **retry** (same ID) from a **second delivery** (new ID) and answer the retry idempotently.
* **Correlation.** Backend logs, phoss-ap logs and the REST API (`InboundTransactionResponse.id`)
  can be joined on a single key.
* **Consistency with the other forwarders.** The file-based forwarders (`filesystem`, `s3_link`,
  `sftp`) already write the transaction ID into their metadata sidecar (`metadataJson`, rendered from
  `InboundTransactionResponse.toJson ()`, field `id`). Only the HTTP forwarders do not expose it.

## Current behaviour

`HttpDocumentForwarder` sets exactly one document-related header
(`HttpDocumentForwarder.java:69`, `:287`):

```java
aPost.setHeader (HEADER_SBDH_INSTANCE_ID, aDocument.sbdhInstanceID ());
```

Beyond that there are only the verification headers and the static custom headers
(`forwarding.http.headers.{n}.name/value`). The custom headers are read once at startup and cannot
carry per-document values.

## Proposed solution

```java
private static final String HEADER_SBDH_INSTANCE_ID = "X-SBDH-Instance-ID";
private static final String HEADER_TRANSACTION_ID = "X-PHOSS-AP-TRANSACTION-ID";
...
aPost.setHeader (HEADER_SBDH_INSTANCE_ID, aDocument.sbdhInstanceID ());
aPost.setHeader (HEADER_TRANSACTION_ID, aDocument.id ());
```

* The value is `IForwardableDocument.id ()`. For `INBOUND_DOCUMENT` and `INBOUND_MLS` that is
  `inbound_transaction.id`, for `OUTBOUND_MLS_COPY` it is the outbound transaction ID, exactly what
  the REST API and the metadata sidecar already expose.
* The ID is a UUID, so no encoding is needed and the header length is fixed.
* The ID stays the same across all retry attempts of a transaction (`RetryScheduler`, replay).
* No configuration switch. The header is purely additive, and a backend that does not know it
  ignores it. If preferred, it could be put behind a `forwarding.http.send-transaction-id` switch
  (default `true`).
* The header name is open for discussion. `X-PHOSS-AP-TRANSACTION-ID` is prefixed so it cannot
  clash with generic `X-Transaction-ID` headers that proxies, API gateways or tracing tools may
  already set. HTTP header names are case-insensitive, so any spelling works for the receiver.

Documentation: add the header wherever the `http_post_*` request contract lists
`X-SBDH-Instance-ID`.

## Acceptance criteria

* [ ] `http_post_sync` and `http_post_async` send `X-PHOSS-AP-TRANSACTION-ID` with the value of
      `IForwardableDocument.id ()` on every attempt, including retries and replays.
* [ ] The value matches the `id` returned by `GET /api/inbound/status/{sbdhInstanceID}`.
* [ ] Request body, `X-SBDH-Instance-ID`, the verification headers and the custom headers stay
      unchanged.
* [ ] A unit test asserts the header on the outgoing `HttpPost`.

## Reference implementation

Implemented in a fork. The change is exactly the two lines above in
`HttpDocumentForwarder`, plus documentation. Happy to open a PR if the direction is acceptable.

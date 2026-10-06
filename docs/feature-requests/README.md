# Feature requests

Drafts to be filed as GitHub issues against [phax/phoss-ap](https://github.com/phax/phoss-ap).
Written in English so they can be copied over as-is.

| ID | Title | Core idea |
|---|---|---|
| [FR-001](FR-001-api-triggered-mls-sending.md) | API-triggered MLS sending (deferred MLS for HTTP forwarding) | New `mls.sending.trigger=api` plus `POST /api/mls/send`, so the Receiver Backend decides when and with which status the MLS goes out |
| [FR-002](FR-002-mls-after-successful-retry.md) | Send the positive MLS when a retried or replayed forwarding finally succeeds | The MLS trigger currently only exists in the synchronous receive path |
| [FR-003](FR-003-mls-configuration-robustness.md) | Make MLS configuration parsing robust and observable | `mls.type` fails silently on lower-case values, SBDH `MLS_TYPE` is ignored, dead code in the failure path, `/api/mls/missing` noise |
| [FR-004](FR-004-as4-rejection-when-mls-undeliverable.md) | Reject the inbound AS4 message when the forwarding failure cannot be reported via MLS | Analysis; opt-in `as4-reject` with an MLS deliverability probe |
| [FR-005](FR-005-as4-rejection-on-http-forwarding-error.md) | Optionally reject the inbound AS4 message when synchronous HTTP forwarding fails | Concrete, implemented shape of FR-004 (`always` variant) for `http_post_sync`, incl. duplicate handling |
| [FR-006](FR-006-transaction-id-header-http-forwarding.md) | Send the transaction ID as an HTTP header when forwarding via HTTP | `X-PHOSS-AP-TRANSACTION-ID` next to `X-SBDH-Instance-ID`, so the backend can correlate and tell a retry from a new delivery |

FR-001 is the main request; FR-002 and FR-003 are independent and each valuable on their own.
For the background analysis in German see [../mls-frage-mls-erst-nach-c4.md](../mls-frage-mls-erst-nach-c4.md).

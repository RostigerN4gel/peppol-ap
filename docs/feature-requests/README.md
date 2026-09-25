# Feature requests

Drafts to be filed as GitHub issues against [phax/phoss-ap](https://github.com/phax/phoss-ap).
Written in English so they can be copied over as-is.

| ID | Title | Core idea |
|---|---|---|
| [FR-001](FR-001-api-triggered-mls-sending.md) | API-triggered MLS sending (deferred MLS for HTTP forwarding) | New `mls.sending.trigger=api` plus `POST /api/mls/send`, so the Receiver Backend decides when and with which status the MLS goes out |
| [FR-002](FR-002-mls-after-successful-retry.md) | Send the positive MLS when a retried or replayed forwarding finally succeeds | The MLS trigger currently only exists in the synchronous receive path |
| [FR-003](FR-003-mls-configuration-robustness.md) | Make MLS configuration parsing robust and observable | `mls.type` fails silently on lower-case values, SBDH `MLS_TYPE` is ignored, dead code in the failure path, `/api/mls/missing` noise |

FR-001 is the main request; FR-002 and FR-003 are independent and each valuable on their own.
For the background analysis in German see [../mls-frage-mls-erst-nach-c4.md](../mls-frage-mls-erst-nach-c4.md).

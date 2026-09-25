# FR-003: Make MLS configuration parsing robust and observable

**Type:** Feature request / usability
**Affected version:** current `main` (0.10.x)
**Affected modules:** `phoss-ap-core`, `phoss-ap-webapp` (sample configuration)
**Related:** [FR-001](FR-001-api-triggered-mls-sending.md)

## 1. `mls.type` silently falls back to `ALWAYS_SEND`

`APCoreConfig.getMlsType()` (`APCoreConfig.java:678-683`) resolves the value via
`EPeppolMLSType.getFromIDOrNull()`, which compares with `getID().equals(...)` — case sensitive. The
valid IDs are `FAILURE_ONLY` and `ALWAYS_SEND`.

The shipped sample configuration
(`phoss-ap-webapp/src/main/resources/application.properties`, section `# === MLS ===`) documents the
value in lower case:

```properties
#mls.type=failure_only
```

Anyone copying that line ends up with an unrecognised value, and the code falls back to
`ALWAYS_SEND` **without any log output**. The effect is the exact opposite of the intent: MLS is
sent for every message instead of only on failure. In a C3 deployment this is easy to miss for a
long time, because a positive MLS is not an error condition anywhere in the logs.

Proposal:

* [ ] Fix the sample `application.properties` to `#mls.type=FAILURE_ONLY`.
* [ ] Log a `WARN` when `mls.type` is set but cannot be resolved, naming the configured value and
      the effective fallback.
* [ ] Optionally accept the value case-insensitively (there are `...CaseInsensitive...` helpers in
      `EnumHelper`), so both spellings work.
* [ ] Log the effective MLS configuration once at startup (`mls.sending.enabled`, `mls.type`), the
      way other subsystems log their effective settings.

## 2. Document that the SBDH `MLS_TYPE` is not evaluated

Peppol transports the sender's request in the SBDH scope `MLS_TYPE`
(`PeppolSBDHData.getMLSType()`, `CPeppolSBDH.SCOPE_MLS_TYPE`). phoss-ap ignores it and stores its
own global setting on the inbound transaction instead:

```java
// InboundOrchestrator.java:377
APCoreConfig.getMlsType ()
```

That is a defensible design decision, but it is invisible to operators, who reasonably assume the
per-message value is honoured. Proposal:

* [ ] Document the behaviour in the wiki and in the property comment.
* [ ] Optionally add a third mode, e.g. `mls.type=FROM_SBDH`, that uses
      `aPeppolSBD.getMLSType()` and falls back to `EPeppolMLSType.DEFAULT` (`FAILURE_ONLY`, per the
      final Peppol Network Policy) when the SBDH carries no value.

## 3. Dead code in the permanent-forwarding-failure path

`InboundOrchestrator.java:754` contains a hard-wired condition:

```java
final MlsOutcome aOutcome = true ? MlsOutcome.acknowledging ("Forwarding to C4 failed for now")
                                 : MlsOutcome.rejection ("Forwarding to C4 failed",
                                                         MlsOutcomeIssue.failureOfDelivery (...));
```

The `rejection` branch is unreachable. Two consequences worth addressing:

* The intent ("we assume the failure is temporary, so we send `AB`") is only visible in the comment;
  a reader has to notice the `true`.
* There is no way to express a genuinely permanent inability to deliver, even though the Peppol MLS
  status reason code `FD` (failure of delivery) exists for exactly that and
  `MlsOutcomeIssue.failureOfDelivery` is already implemented.

Proposal:

* [ ] Remove the dead ternary.
* [ ] Make the code selectable, e.g.
      `mls.forwarding-failure.code=AB|RE` (default `AB` to preserve behaviour), so operators whose
      backend failures are genuinely terminal can send `RE`/`FD`.
* [ ] Alternatively derive it from `ForwardingResult.isRetryAllowed()`: `retry` disallowed by the
      receiver (`{"retry":"none"}` in the sync HTTP response) is a permanent statement by the
      backend and maps naturally to `RE` with `FD`, whereas exhausted retries stay `AB`.

## 4. Transactions in `FAILURE_ONLY` mode stay in `/api/mls/missing`

When `mls.type=FAILURE_ONLY` and forwarding succeeds, `InboundOrchestrator.java:505` is not entered
at all, so `updateMlsFields` is never called and `mls_response_code` stays `NULL`. Every
successfully processed document therefore shows up in `GET /api/mls/missing` forever, which makes
that endpoint useless for monitoring in this mode.

Note that the same situation is handled correctly one layer down:
`MlsHandler.java:116-125` explicitly records the response code without sending when `FAILURE_ONLY`
filters an outcome — the success path just never reaches it.

Proposal:

* [ ] In the success path with `FAILURE_ONLY`, either record the determined response code (route
      the outcome through `MlsHandler` so the existing filter does the work) or exclude
      `mls_type = FAILURE_ONLY` transactions from `getAllWithoutMlsResponse()`.
* [ ] Document which of the two semantics `GET /api/mls/missing` implements.

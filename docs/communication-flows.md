# Access Point communication flows (incl. MLS)

This document describes the communication with this Peppol Access Point and the message flow
including **MLS** (Message Level Status). The diagrams reflect the actual code:

- [`InboundOrchestrator.java`](../phoss-ap-core/src/main/java/com/helger/phoss/ap/core/inbound/InboundOrchestrator.java)
- [`OutboundOrchestrator.java`](../phoss-ap-core/src/main/java/com/helger/phoss/ap/core/outbound/OutboundOrchestrator.java)
- [`MlsHandler.java`](../phoss-ap-core/src/main/java/com/helger/phoss/ap/core/mls/MlsHandler.java)
- [`MiddlewareReceiverForwarder.java`](../phoss-ap-webapp/src/main/java/com/helger/phoss/ap/webapp/middleware/MiddlewareReceiverForwarder.java)
- [`OutboundController.java`](../phoss-ap-webapp/src/main/java/com/helger/phoss/ap/webapp/controller/OutboundController.java)
- [`MlsController.java`](../phoss-ap-webapp/src/main/java/com/helger/phoss/ap/webapp/controller/MlsController.java)

> Fork-specific behaviour is documented in [`CUSTOMIZATIONS.md`](../CUSTOMIZATIONS.md).

## 1. Peppol 4-corner model (roles of this AP)

This AP acts as **C2** (when sending) and as **C3** (when receiving).

```mermaid
flowchart LR
    C1["C1<br/>Sender backend"] -->|"REST /api/outbound"| C2["C2<br/>THIS AP (sending)"]
    C2 -->|"AS4 / Peppol"| C3["C3<br/>Remote AP (receiving)"]
    C3 -->|Backend| C4["C4<br/>Receiver"]

    RC2["C2<br/>Remote AP (sending)"] -->|"AS4 / Peppol"| RC3["C3<br/>THIS AP (receiving)"]
    RC3 -->|"InboundPeppolRequest (XML)"| RC4["C4<br/>Middleware receiver"]

    RC3 -.->|"MLS back"| RC2
    C3 -.->|"MLS back"| C2

    classDef me fill:#2563eb,stroke:#1e40af,color:#fff;
    class C2,RC3 me;
```

## 2. Inbound: receiving a business document (this AP = C3) incl. MLS

M1 = reception of the AS4 message, M2 = successfully sending the MLS back to C2.

```mermaid
sequenceDiagram
    autonumber
    participant C2 as Remote AP (C2)
    participant P4 as phase4 (AS4 layer)
    participant IO as InboundOrchestrator
    participant DB as DB / payload store
    participant FW as MiddlewareReceiverForwarder
    participant C4 as Middleware receiver (C4)
    participant MLS as MlsHandler

    C2->>P4: AS4 UserMessage (SBD)
    P4->>P4: Verify signature / decryption
    P4-->>C2: AS4 Receipt (signal) — M1
    P4->>IO: processIncomingDocument(SBD)

    IO->>DB: Duplicate check (AS4 ID / SBDH ID)
    alt Duplicate & mode = REJECT
        IO-->>P4: EBMS error (rejected)
    end
    IO->>IO: Receiver check (isReceiverServiced)
    IO->>DB: Store SBD + create inbound transaction
    opt Inbound verification enabled
        IO->>IO: verifyInboundDocument()
        alt Verification failed
            IO->>MLS: async negative MLS (rejection / RE)
            MLS-->>C2: Send MLS (see diagram 4)
        end
    end

    Note over IO,C4: Forwarding to C4
    IO->>FW: forwardDocument(tx)
    FW->>C4: POST InboundPeppolRequest (XML + AS4 headers + Base64 SBD)
    C4-->>FW: ProcessResult (Status, ErrorMessage, C4CountryCode)

    alt Status = success
        FW-->>IO: ForwardingResult.success(C4CountryCode)
        IO->>DB: Status = FORWARDED + reporting item
        opt MLS type = ALWAYS_SEND & not an MLS doc
            IO->>MLS: async positive MLS<br/>(AP = with confirmation / AB = without)
            MLS-->>C2: Send MLS — M2
        end
    else Status != success (business reject)
        FW-->>IO: failureNoRetry(...)
        IO->>DB: Status = PERMANENTLY_FAILED
        IO->>MLS: async MLS "acknowledging" (AB)
        MLS-->>C2: Send MLS
    else Transport / IO error
        FW-->>IO: failure(...) (retry allowed)
        IO->>DB: Status = FORWARD_FAILED + next retry (backoff)
    end
```

## 3. Outbound: sending a business document (this AP = C2) incl. later MLS

M1 = successful send, M3 = reception of the MLS from C3.

```mermaid
sequenceDiagram
    autonumber
    participant C1 as Sender backend (C1)
    participant OC as OutboundController
    participant OO as OutboundOrchestrator
    participant DB as DB / payload store
    participant SMP as SMP / NAPTR (DNS)
    participant P4 as phase4 (AS4)
    participant C3 as Remote AP (C3)

    C1->>OC: POST /api/outbound/submit... (payload or SBD)
    OC->>OO: submitRawDocument / submitPrebuiltSBD
    opt Outbound verification enabled
        OO->>OO: verifyOutboundDocument()
    end
    OO->>DB: Create outbound transaction (PENDING)
    OO->>OO: processPendingOutbound()

    OO->>DB: Status = SENDING
    OO->>SMP: Dynamic discovery (NAPTR + SMP lookup)
    SMP-->>OO: Endpoint URL + C3 certificate
    OO->>OO: Verify certificate / trusted CA

    OO->>P4: Send AS4 UserMessage (mlsTo, mlsType set)
    P4->>C3: AS4 UserMessage (SBD)
    C3-->>P4: AS4 Receipt (signal)
    P4-->>OO: SendResult + receipt ID — M1

    alt Success
        OO->>DB: Status = SENT + reporting item
        OO-->>OC: Phase4PeppolSendingReport (success)
        OC-->>C1: 200 OK (JSON report)
    else Error & retries left
        OO->>DB: Status = FAILED + next retry (backoff)
        OC-->>C1: 422 (report)
    else Error & no retries left
        OO->>DB: Status = PERMANENTLY_FAILED
        OC-->>C1: 422 (report)
    end

    Note over C3,OO: Later (asynchronous): MLS response from C3
    C3->>P4: AS4 UserMessage (MLS document)
    P4->>OO: processIncomingDocument (MLS) — M3
    OO->>DB: Correlate MLS with outbound tx<br/>(status RECEIVED_AP/AB/RE)
```

## 4. MLS creation & correlation (detail)

```mermaid
sequenceDiagram
    autonumber
    participant IO as InboundOrchestrator
    participant MH as MlsHandler
    participant DB as DB / payload store
    participant OO as OutboundOrchestrator
    participant Peer as Remote AP

    Note over IO,MH: A) SENDING MLS (result of an inbound doc)
    IO->>MH: triggerSendingInboundResultMls(inboundTx, outcome)
    alt MLS globally disabled
        MH-->>IO: SUCCESS (skipped)
    else MLS type FAILURE_ONLY & outcome = success
        MH->>DB: Update MLS fields only (no send)
    else MLS required
        MH->>MH: Build PeppolMLS (response code, sender/receiver PID, referenceId = SBDH ID)
        MH->>DB: Store MLS doc + create outbound tx (MLS_RESPONSE)
        MH->>DB: Update inbound tx MLS fields
        MH->>OO: processPendingOutbound(mlsTx)
        OO->>Peer: Send AS4 MLS — M2
    end

    Note over Peer,MH: B) RECEIVING MLS (correlation)
    Peer->>IO: AS4 MLS document (isMLS = true)
    IO->>IO: Parse PeppolMLS, read referenceId
    IO->>MH: handleIncomingMls(referenceId, responseCode, ...)
    MH->>DB: Look up outbound tx by SBDH ID
    alt Response code
        MH->>DB: ACCEPTANCE → RECEIVED_AP
        MH->>DB: ACKNOWLEDGING → RECEIVED_AB
        MH->>DB: REJECTION → RECEIVED_RE
    end
    MH->>MH: Compute round-trip duration (M3 - M1) → SLA
```

## SLA measurement points

From [`MlsController.java`](../phoss-ap-webapp/src/main/java/com/helger/phoss/ap/webapp/controller/MlsController.java):

| Metric                     | Measurement                                       | Target (Peppol Network Policy) |
|----------------------------|---------------------------------------------------|--------------------------------|
| **MLS-1** (receiving side) | M2 − M1: receiving business doc → MLS sent back   | 99.5 % ≤ 20 min                |
| **MLS-2** (sending side)   | M3 − M1: business doc sent → MLS received from C3 | 99.5 % ≤ 25 min                |

REST endpoints:

- `GET /api/mls/missing` — inbound transactions with no MLS response sent yet
- `GET /api/mls/sla/mls1` — MLS-1 SLA report (receiving side)
- `GET /api/mls/sla/mls2` — MLS-2 SLA report (sending side)

## Fork-specific behaviour (important)

A rejection by the C4 middleware `receiver` does **not** produce an AS4 error back to C2 — at that
point the AS4 receipt has already been sent positively. The rejection is instead signalled via
**MLS + transaction status** (shown as "business reject" in diagram 2). See
[`CUSTOMIZATIONS.md`](../CUSTOMIZATIONS.md), section "Behavioural differences", for details.

# Withdrawal Service

Improvement of the supplied bank-withdrawal snippet. The business capability is unchanged —
debit an account if it can cover the amount, then emit a withdrawal event — but it is now
correct under concurrency, atomic with its event, auditable and observable.

## → [APPROACH.md](APPROACH.md) is the submission

The brief asks for four things. They are all there, in that order:

| Asked for | Where |
|---|---|
| An outline of the approach, business capability unchanged | [§1](APPROACH.md) — including what the original *actually* does, which is not quite what it looks like |
| Elaboration on implementation choices | [§3](APPROACH.md) |
| The fixed code | [§4](APPROACH.md), and this repository |
| Any unclear library usage documented | [§5](APPROACH.md) — the original's, and the rewrite's |

It also covers what I deliberately did **not** build and why ([§6](APPROACH.md)), and what
an independent review found ([§7](APPROACH.md)).

This file is the repository: how to run it, how to check the claims, where things live.

Java 21 · Spring Boot 3.3.5 · PostgreSQL (JdbcClient + Flyway) · AWS SNS · Testcontainers

---

## Design

```mermaid
flowchart LR
    Client(["Client"])

    subgraph SVC["withdrawal-service"]
        direction TB
        API["WithdrawalController<br/>/v1/bank/withdraw"]
        APP["WithdrawalService<br/><i>retry boundary</i>"]
        TXN["WithdrawalTransaction<br/><b>the unit of work</b>"]
        RELAY["OutboxRelay<br/><i>poll 2s</i>"]
        RECON["ReconciliationJob<br/><i>daily full sweep</i>"]
        API --> APP --> TXN
    end

    subgraph DB[("PostgreSQL")]
        direction TB
        ACC["accounts"]
        LED["ledger_entry<br/><i>append-only</i>"]
        OBX["outbox_event"]
        IDK["idempotency_key"]
    end

    SNS(["AWS SNS"])
    CONS["AML · fraud · notification · statements"]

    Client --> API
    TXN --> ACC & LED & OBX & IDK
    RELAY --> OBX
    RELAY --> SNS --> CONS
    RECON --> ACC & LED

    classDef store fill:#f6f8fa,stroke:#8b949e
    class DB store
```

One transaction covers all four writes. The relay and the reconciliation run afterwards, on
their own schedules, and never on the request path.

---

## Running it

```bash
docker compose up -d
```

Postgres plus LocalStack, with the SNS topic and an SQS subscriber auto-provisioned.

```bash
AWS_ENDPOINT_OVERRIDE=http://localhost:4566 \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile adds the demo accounts from `db/seed` and points SNS at LocalStack.
Without it Flyway applies the schema only and the SDK resolves the real AWS endpoint.

```bash
curl -X POST http://localhost:8080/v1/bank/withdraw \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-key-1' \
  -H 'X-Client-Id: demo-client' \
  -d '{"accountId":1001,"amount":500.00}'
```

Repeat it identically: the original response is replayed and the balance does not move
again.

**Demo accounts** (local profile only) — `1001` 1000.00 active · `1002` 250.00 active ·
`1003` 750.00 frozen · `9000` system settlement, ledger-only and closed to the customer API.

OpenAPI at `/swagger-ui.html`. Metrics, health and the outbox operator endpoint are on the
management port: `http://localhost:9090/actuator`.

---

## Verifying it

```bash
./mvnw verify                 # 60 tests — 31 unit, 29 integration
```

Integration tests run against real PostgreSQL via Testcontainers, not H2, because H2 does
not reproduce the row-locking semantics the design depends on — a test passing on H2 would
prove nothing about the mechanism.

| Executed, not asserted | Result |
|---|---|
| 50 concurrent withdrawals of 100 against a balance of 1000 | Exactly **10** succeed, 40 rejected, balance **0.00** |
| Lost updates | None — pinned by exact success count, not "never negative" |
| 20 concurrent requests sharing one idempotency key | All 200; **exactly one** debit |
| Two relay workers | Disjoint batches, no blocking |
| Transient publish failure | Backs off, stays PENDING, never dead-letters (over 41 attempts) |
| Rejected message | Dead-lettered on first occurrence |
| Contended row past `lock_timeout` | 503 + `Retry-After`, not 500 |
| Ledger vs cached balance, and every transaction | Both reconcile |

```bash
bash scripts/verify_db_claims.sh
```

Seven experiments against a throwaway PostgreSQL 16 container, proving the database
behaviour the design rests on: the original's race, why READ COMMITTED is *required* rather
than merely sufficient, `NUMERIC` scale coercion, the idempotency claim under concurrency,
what `SKIP LOCKED` actually buys, and `lock_timeout`.

---

## Layout

| Package | Holds |
|---|---|
| `api` | Controller, DTOs, RFC 7807 exception handler |
| `application` | Orchestration and retry boundary; the transactional unit of work |
| `domain` | Commands, diagnostics, sealed exception hierarchy |
| `persistence` | Account, ledger and idempotency repositories |
| `messaging` | Outbox (append / relay / operations), SNS publisher, event envelope |
| `job` | Reconciliation and retention |
| `observability` | Metrics, health, operator endpoint |

Start with `JdbcAccountRepository.debitIfPermitted` — the statement the correctness fix
lives in — then `WithdrawalTransaction.execute` for the four writes.

`git log` reads as the narrative: each commit says what was wrong and why the fix is that
fix. [ADR 0001](docs/decisions/0001-no-circuit-breaker-on-the-outbox-relay.md) records the
one decision that was contested.

---

## Not built

Security is out of scope per the brief — there is no authentication, and `X-Client-Id` is
self-asserted. Also absent, deliberately: reversals, available-versus-ledger balance and
holds, value dating, limits and velocity checks, a customer entity, and multi-currency
support. [APPROACH.md §6](APPROACH.md) says why for each, and which one I would close first.

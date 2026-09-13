# Withdrawal Service

Improvement of the supplied bank-withdrawal snippet. The business capability is
unchanged — debit an account if it can cover the amount, then emit a withdrawal
event — but it is now correct under concurrency, atomic with its event, auditable,
and observable.

Runs locally: `docker compose up -d`, then the two commands in §8.

---

## 1. What was wrong with the original

| # | Defect | Consequence |
|---|---|---|
| 1 | Event-publishing code sits after `return` statements | **Unreachable.** No event is ever published |
| 2 | `SELECT balance` then separate `UPDATE` | **Check-then-act race → overdraft.** Reproduced: two concurrent withdrawals of 60 against a balance of 100 leave **−20.00** |
| 3 | DB commit and SNS publish are independent writes | Dual-write problem: crash between them loses the event, or emits an event for money that never moved |
| 4 | No transaction boundary | Partial state on failure |
| 5 | `String.format` JSON | Breaks on any quote/backslash; renders `BigDecimal` as a quoted string |
| 6 | `SnsClient` built in the controller constructor | Untestable, never closed, web layer coupled to AWS transport |
| 7 | Plain-string replies, always HTTP 200 | No client can branch on the outcome reliably |
| 8 | No custom exception types | Failure modes indistinguishable |
| 9 | SQL in the controller | No layering, nothing unit-testable |
| 10 | No input validation | **A negative amount turns a withdrawal into an unaudited deposit** |
| 11 | No observability | Nothing to alert on |
| 12 | No idempotency | A client timeout retry double-debits |
| 13 | Synchronous SNS call in the request path | Broker latency/outage becomes customer-facing |
| 14 | Hardcoded region and topic ARN | Not deployable to more than one environment |
| 15 | `currentBalance != null` guard | Dead code — `queryForObject` **throws** `EmptyResultDataAccessException` for zero rows; it returns null only if the column itself is NULL |
| 16 | Deprecated `queryForObject(String, Object[], Class)` overload | Superseded since Spring 5.3 |

---

## 2. Approach

The core insight the whole design rests on: **a relational transaction is atomic
across multiple tables.** One transaction writes the balance change, both ledger
legs, the idempotency record and the outbox event. That single property is what
makes the dual-write problem, the audit trail and idempotency safety all fall out
of one mechanism instead of three.

```
POST /v1/bank/withdraw
   └── claim idempotency key        (ON CONFLICT DO UPDATE — fails fast, before money moves)
   └── atomic conditional debit     (one statement: status + funds + write, RETURNING new balance)
   └── double-entry ledger          (DEBIT customer / CREDIT settlement, same transaction_id)
   └── outbox row                   (the event, as data)
   └── store response               (for idempotent replay)
   COMMIT
        ↓ (asynchronously, separate transaction)
   OutboxRelay  ──FOR UPDATE SKIP LOCKED──▶  SNS
```

### The concurrency fix

```sql
UPDATE accounts
   SET balance = balance - :amount
 WHERE id = :accountId
   AND status = 'ACTIVE'
   AND is_system = FALSE
   AND currency = :currency
   AND balance >= :amount
RETURNING balance
```

The check and the write are one statement. The database takes the row lock and
evaluates the predicate against the row it is about to modify. A concurrent
updater blocks; when the first transaction commits, PostgreSQL **re-evaluates
the `WHERE` clause against the newly committed row version**, so the second
statement either still qualifies or matches zero rows. A lost update is not
representable.

**This requires `READ COMMITTED` — it is not merely "safe at" that level.** At
`REPEATABLE READ` or `SERIALIZABLE` the identical statement aborts with
`could not serialize access due to concurrent update` instead of re-evaluating,
forcing an application retry loop for an ordinary insufficient-funds outcome.
Both behaviours were verified directly against PostgreSQL 16 (`verify_db_claims.sh`).

Zero rows has three possible causes. One diagnostic query **on the failure path
only** distinguishes them (404 / 409 / 422), so a successful withdrawal remains a
single round trip.

### Money representation

`NUMERIC(19,2)`, `BigDecimal`, never `double`. Validation enforces at most two
decimal places, which is not cosmetic: PostgreSQL rounds on assignment, so
withdrawing `0.004` returns *one row updated* — a success response, a ledger
entry and a published event — while the balance is **unchanged**. Rejecting
sub-cent scale at the edge is what prevents that.

### Event delivery — transactional outbox

The event is written as a row in the same transaction as the balance change, so
it cannot be lost or phantom-emitted. A scheduled relay claims due rows with
`FOR UPDATE SKIP LOCKED` and publishes them.

`SKIP LOCKED` is load-bearing: without it two application instances claim the
same rows and publish every event twice — verified, both workers claimed ids
1,2,3. With it they claim disjoint batches. The trade-off is strict global
ordering, which is not required here.

Failures are handled by what they are, not by how often they have happened. A
message the transport rejects on its own merits is dead-lettered immediately —
retrying it only delays everything behind it. A transport failure backs the row
off and leaves it `PENDING` with **no attempt limit**, because an event that
cannot currently be delivered is an operational problem to raise, not one to
discard. An earlier version counted attempts, which meant an outage lasting more
than about eight and a half minutes silently dead-lettered every pending event;
[ADR 0001](docs/decisions/0001-no-circuit-breaker-on-the-outbox-relay.md) §5 has
the arithmetic. Delivery is at-least-once; consumers must be idempotent.

### Idempotency

`Idempotency-Key` is **required** — for an operation that moves money, optional
idempotency guarantees that some caller eventually double-debits on a timeout.

The key is claimed *before* the debit, via `ON CONFLICT ... DO UPDATE` rather
than by catching a duplicate-key exception, because in PostgreSQL **any error
aborts the surrounding transaction** — a caught violation leaves it unusable
without a `SAVEPOINT`. `DO UPDATE ... WHERE expires_at < now()` is what enforces
the TTL: expiry is a property of the row, so it has to be evaluated when the row
is claimed. Verified: the losing session blocks at the insert, then receives zero
rows with no error raised. Note that `DO NOTHING` blocks too — both wait on the
same speculative-insertion token, 2.16s against 2.19s in `verify_db_claims.sh`
E5 — so blocking is not what `DO UPDATE` buys. The TTL re-claim is.

Keys are scoped per client and bound to a `request_hash`. Reusing a key with
different parameters returns **422** rather than silently replaying the old
response and dropping the new withdrawal.

### Auditability — double-entry ledger

Insert-only. Every withdrawal writes a DEBIT against the customer and a CREDIT
against a system settlement account, sharing a `transaction_id`. The grouping key
matters: without it you can only prove the ledger balances *globally*, which
would not detect two unrelated errors cancelling out.

`accounts.balance` is a deliberate denormalisation — a cached position; the
ledger is the system of record. Two representations of one fact need a control
proving they agree, so `ReconciliationJob` asserts both invariants continuously.
Without that job the ledger is decoration.

The settlement account is **ledger-only and holds no cached balance**: every
withdrawal in the system credits it, so maintaining a cached balance there would
make it the hottest row in the database. Its position is derived by summing the
ledger.

---

## 3. Verified behaviour

Not asserted — executed. `./mvnw verify` runs 60 tests (31 unit, 29 integration
against real PostgreSQL via Testcontainers, not H2, because H2 does not
reproduce the row-locking semantics the design depends on).

| Check | Result |
|---|---|
| 50 concurrent withdrawals of 100 vs balance 1000 | Exactly **10** succeed, 40 rejected, final balance **0.00** |
| Lost updates | None — asserted by exact success count, not merely "never negative" |
| 20 concurrent requests sharing one idempotency key | All return 200; **exactly one** debit |
| Ledger vs cached balance | Agree for every account |
| Every ledger transaction balances | 0 unbalanced |
| Two relay workers | Disjoint batches (`SKIP LOCKED`) |
| Transient publish failure | Backs off, stays PENDING, never dead-letters (asserted over 41 attempts) |
| Rejected message | Dead-lettered on the first occurrence |
| Contended row past `lock_timeout` | 503 + `Retry-After`, not 500 |

End-to-end against Postgres + LocalStack: 200 success, 200 idempotent replay
(no second debit), 422 key-conflict, 422 insufficient funds, 409 frozen,
409 currency mismatch, 404 unknown, 400 sub-cent, 400 missing key — with both
events delivered to a subscribed SQS queue carrying the correlation id.

---

## 4. Implementation choices

**JdbcTemplate/JdbcClient, not JPA.** JPA's value — identity map, dirty checking,
association management — is entirely unused by a statement-oriented, set-based
operation with no object graph. More concretely, the SQL `WHERE` clause *is* the
correctness mechanism here and should be visible in review, and a `@Modifying`
bulk update bypasses the persistence context, so a later read in the same
transaction is stale unless explicitly cleared — a trap this design would walk
into for no benefit. `JdbcClient` (Spring 6.1+) is the current fluent API over
the same machinery the original used.

**Retry wraps the transaction, not the repository call.** Spring marks a
transaction rollback-only on the first exception, so retrying a statement inside
it can never succeed. `WithdrawalService` (`@Retryable`) and
`WithdrawalTransaction` (`@Transactional`) are separate beans specifically so each
attempt begins a genuinely new transaction — and so that a self-invocation, which
would silently bypass the proxy, is impossible.

**Sealed exceptions + pattern-matching switch.** The status mapping has no
`default` branch. Adding a new subtype later breaks the build until someone
decides its status code, instead of silently becoming a 500.

**RFC 7807 `ProblemDetail`**, not a bespoke error shape.

**No circuit breaker.** During an SNS outage the per-row exponential backoff
already suppresses doomed calls, and unlike a breaker it also handles poison
messages. A breaker would add a dependency and a state machine without changing
what happens to the rows. This one was contested rather than obvious; the full
argument, what it costs us and the conditions that would reverse it are recorded
in [ADR 0001](docs/decisions/0001-no-circuit-breaker-on-the-outbox-relay.md).

**Lombok** is limited to `@RequiredArgsConstructor` and `@Slf4j` on plain classes.
Records generate their own accessors, constructor and equality.

---

## 5. Library usage notes

- **AWS SDK v2 `SnsClient`** — builder-constructed, one thread-safe instance as a
  Spring bean (it owns an HTTP connection pool), closed by the container.
  `endpointOverride` + static credentials are used **only** for LocalStack; a real
  deployment resolves credentials through the default chain (instance role / IRSA).
- **`PublishRequest` / `PublishResponse`** — `eventType` is set as a *message
  attribute* as well as in the body so subscribers can apply SNS filter policies
  instead of paying to receive and parse messages they do not want.
- **Standard (not FIFO) SNS topic** — fan-out to independent consumers that each
  tolerate duplicates; throughput and cost beat FIFO's ordering/dedup here.
- **Flyway** — `V1` schema, `V2` demo seed. Opening balances are seeded *as ledger
  entries*, otherwise reconciliation could never succeed.
- **Testcontainers** over H2 — see above.
- **Micrometer** — `outbox.pending.oldest.age.seconds` is the meaningful lag SLO;
  depth alone cannot distinguish a busy relay from a stalled one.

---

## 6. Deliberately not built

Named because scope decisions are decisions.

| Not built | Why |
|---|---|
| CDC / Debezium | The correct answer at scale; disproportionate infrastructure here |
| 2PC / XA | SNS does not support it, and it is a known anti-pattern |
| Schema registry (Avro/Confluent) | Warranted at higher event-type and team count; one producer here. CloudEvents envelope + additive versioning instead |
| FX / multi-currency conversion | Single-currency (ZAR). A rate service is right for *conversion*, which is a different capability from "an account has a currency" |
| Holds / available-balance ledger | Real accounts cannot be drawn to the full balance; authorisation lifecycle is a larger domain |
| Daily/per-transaction limits | Real, but not part of the supplied capability |
| Tracing (Jaeger), Grafana | Metrics are exposed at `/actuator/prometheus`; dashboards add no engineering signal to this submission |
| Circuit breaker on the relay | Redundant with per-row backoff for outages, and insufficient alone for poison messages — [ADR 0001](docs/decisions/0001-no-circuit-breaker-on-the-outbox-relay.md) |
| Security | Out of scope per the brief |

---

## 7. Data governance

Event payloads carry a surrogate account id, the amount and the resulting balance
— no names, national identity numbers or full account numbers cross the service
boundary. POPIA minimisation is about necessity, not about carrying as little as
possible: the balance is what the notification and statement consumers need. Published outbox rows are purged after 7 days and idempotency keys
expire after 24 hours; both would otherwise grow without bound, which is a cost
and a governance problem. The ledger is explicitly **not** purged — financial
records carry a statutory retention obligation (FICA: seven years) and are
archived rather than deleted.

The withdrawal event exists to serve real consumers: AML/FICA cash-threshold
monitoring, fraud scoring, customer notification and statement generation.

---

## 8. Running it

```bash
docker compose up -d          # Postgres + LocalStack (SNS topic and SQS subscriber auto-provisioned)

# The local profile adds the demo accounts (db/seed) and points SNS at LocalStack.
# Without it Flyway applies the schema only, and the SDK resolves the real AWS endpoint.
AWS_ENDPOINT_OVERRIDE=http://localhost:4566 \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # requires JDK 21
./mvnw verify                 # 60 tests, incl. Testcontainers concurrency proof
```

```bash
curl -X POST http://localhost:8080/v1/bank/withdraw \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-key-1' \
  -H 'X-Client-Id: demo-client' \
  -d '{"accountId":1001,"amount":500.00}'
```

Repeat the identical call: the original response is replayed and the balance does
not move again.

Seeded accounts (local profile only — `db/seed`, not on the default migration
path) — **1001** 1000.00 active · **1002** 250.00 active · **1003** 750.00 frozen
· **9000** system settlement, ledger-only and closed to the customer API.

OpenAPI at `/swagger-ui.html`, metrics at `/actuator/prometheus`, health at
`/actuator/health`.

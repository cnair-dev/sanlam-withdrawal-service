# Withdrawal service — approach and implementation choices

Submission for the bank account withdrawal code improvement exercise.

The brief asks for four things: an outline of the approach with the business capability
preserved, elaboration on implementation choices, the fixed code, and notes on any unclear
library usage. This document is organised in that order. The code is in this repository;
`git log` is the narrative — each commit states what was wrong and why the fix is that fix.

---

## 1. Approach

### How I read the task

The snippet is short, but almost every line of it is load-bearing on something the brief
names. I worked in this order, which is also my order of priority:

1. **Correctness first.** A withdrawal endpoint that can produce a wrong balance is not
   improved by anything else on the list. This is where the race condition, the unreachable
   publish and the money arithmetic live.
2. **Then the guarantees around it** — what happens on a retry, a crash between two writes,
   a broker outage. Fault tolerance and auditability in the brief's terms.
3. **Then the controls that prove it worked** — a ledger nobody checks is decoration, so
   the reconciliation exists to make the ledger mean something.
4. **Then operability** — status codes a caller can branch on, logs that can be correlated,
   metrics with a latency distribution rather than an average.

I stopped at the boundary of what this service can own. What I did not build is in §6, and
I would rather be asked why something is missing than explain machinery I could not justify.

### Scope

The estimate was 1.5–2.5 hours. The commit history spans 2h12m, and the shape of the
solution reflects that budget being spent on depth in one place rather than breadth
everywhere: the concurrency and delivery guarantees are worked through properly and
verified against a real database, while whole categories the brief puts out of scope
— security in particular — are untouched and flagged rather than half-built.

Two deliberate departures from the brief's "out of scope" list, both because the thing
being tested cannot be demonstrated any other way:

- **The code compiles and runs.** The brief says it needn't. But the central claim is about
  PostgreSQL's behaviour when a conditional `UPDATE` meets a concurrently locked row, and
  that claim is either true against a real database or it is an assertion. It is worth more
  as a thing you can run than as a paragraph.
- **There are tests.** The brief says they may be omitted. The concurrency ones exist
  because "no lost updates" is a claim that needs executing rather than stating; each is
  written so it fails if the mechanism it covers is removed, and I checked that by removing
  them. The rest followed once the harness existed.

---

## 2. What the original does, and what is wrong with it

Preserving the business capability means being precise about what it currently is.

**Intended capability:** debit an account by an amount if the balance covers it, and emit a
withdrawal event. That is preserved exactly — same operation, same two effects.

**As written, the second half does not happen.** Every branch of the `if/else` returns
before the SNS block is reached, so the publish is unreachable code and no event is ever
emitted. The first thing the rewrite does is make the stated capability actually occur.

| # | Defect | Consequence |
|---|---|---|
| 1 | `SELECT balance`, compare in Java, then `UPDATE` | Check-then-act race. Two concurrent withdrawals of 60 against a balance of 100 both pass the check and both debit, leaving −20.00. Reproduced against PostgreSQL 16. |
| 2 | The SNS publish sits after `return` on every path | Unreachable. The event is never published, so the business capability is half-implemented. |
| 3 | `String.format` JSON in `toJson()` | Produces invalid JSON for any value containing a quote or backslash, and renders `BigDecimal` as a quoted string so consumers parse a number as text. |
| 4 | `SnsClient` built in the controller constructor, `Region.YOUR_REGION`, hardcoded ARN | Untestable, unshareable, never closed, and ties a web component to a transport concern. |
| 5 | Every outcome returns HTTP 200 with a prose string | A caller cannot branch on the result. "Insufficient funds" and "successful" are both 200. |
| 6 | No transaction anywhere | The balance write and the event have no atomicity relationship. |
| 7 | `JdbcTemplate` is used but never imported | It does not compile as given. Noted because the brief says it needn't — but it is worth saying which defects are design and which are the snippet being a sketch. |

---

## 3. Implementation choices

<!-- SPINE: each entry = decision, why, what it cost, what I'd have done instead.
     Pull the detail from defence_map.md Part 2. Order by what an interviewer asks first. -->

### The core fix: one statement, not three

_[decision, the EvalPlanQual explanation, why READ COMMITTED is required rather than merely
sufficient, why not optimistic locking or SELECT FOR UPDATE]_

### Atomicity across the four writes

_[balance, ledger, outbox, idempotency in one transaction; why that single property solves
the dual-write problem, the audit trail and idempotency safety at once]_

### Delivery: the transactional outbox

_[why an outbox rather than publish-after-commit; at-least-once and what that asks of
consumers; the batch window and why closing it needs a distributed transaction]_

### Idempotency

_[required header not optional; ON CONFLICT rather than catching a duplicate key; TTL
enforced at claim time; request hash binding; why retry and idempotency are different
concerns]_

### The ledger and the control that makes it mean something

_[double-entry with a transaction_id grouping key; append-only enforced in the schema;
two-pass reconciliation and why the full sweep is not redundant]_

### Money

_[NUMERIC(19,2); the sub-cent phantom success and why the database-side guard cannot fire;
normalising to the minor unit; that this service never rounds customer money]_

### Failure handling

_[classification rather than attempt counting; why config errors are transient; no terminal
state for transient failures; the no-circuit-breaker decision → ADR 0001]_

### API contract

_[RFC 7807; sealed hierarchy and the exhaustive switch; /v1; why extending
ResponseEntityExceptionHandler is load-bearing]_

### Observability

_[correlation id across the async boundary; histograms not averages; backlog age not depth;
why the outbox health indicator is out of the liveness group]_

---

## 4. The fixed code

<!-- SPINE: inline the two pieces someone should be able to read without cloning:
     the controller, and the conditional UPDATE. Everything else by reference. -->

_[controller + JdbcAccountRepository.debitIfPermitted inline, with a short map of the
package layout and where to look for what]_

---

## 5. Library usage notes

<!-- SPINE: the brief asks to "document any unclear library usage" — both what was unclear
     in the original, and anything in the rewrite a reviewer would not assume. -->

### In the original

- **`JdbcTemplate`** — used but not imported.
- **`PublishResponse publishResponse = snsClient.publish(...)`** — assigned and never read.
  The response carries the SNS `messageId`, which is the only evidence the publish
  happened; discarding it means a failed publish and a successful one are indistinguishable
  at the call site.
- **`Region.YOUR_REGION`** and the placeholder topic ARN — not valid values.
- **`String.format` with `%d`** on a boxed `Long` in `toJson()`.

### In the rewrite

_[JdbcClient vs JdbcTemplate and why; Spring Retry is a separate dependency not Spring
core; Lombok scope and what it is deliberately not used for; Testcontainers/LocalStack;
logstash-logback-encoder; springdoc; Micrometer]_

---

## 6. What I did not build, and why

<!-- SPINE: this section is the judgment one. Two classes: out of scope per the brief,
     and in scope but deliberately deferred. Pull from defence_map.md gaps + Part 3. -->

### Out of scope per the brief

- **Security.** No authentication or authorisation. `X-Client-Id` is self-asserted and
  would be replaced by an authenticated principal.

### In scope, deliberately not built

_[reversals; available vs ledger balance and holds; limits/velocity; customer entity and
what it means for the AML consumer; multi-currency and the settlement account per currency;
value dating]_

### Assumptions encoded as constants

_[scale 2, ZAR, two ledger legs, one settlement account, 24h idempotency TTL — each a
business fact in code rather than data with a rule attached; right at this size, and the
set worth naming]_

---

## 7. Cold review findings

<!-- SPINE: stub. Anything the independent review surfaces that I agree with and acted on,
     plus anything I considered and deliberately did not change, with the reason. -->

_[pending]_

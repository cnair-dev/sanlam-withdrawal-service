# ADR 0001 — No circuit breaker on the outbox relay

**Status:** Accepted, contested. Revisit on the triggers in §6.
**Date:** 2026-09-12
**Scope:** `OutboxRelay` → SNS publish path only. Says nothing about breakers elsewhere.

---

## 1. Context

The relay is a scheduled background poller. It claims `PENDING` rows from
`outbox_event` with `FOR UPDATE SKIP LOCKED`, publishes each to SNS, and marks
the row `PUBLISHED`. The withdrawal itself — the debit, both ledger legs, and the
outbox row — has already committed by the time the relay runs. Nothing on the
request path waits for it.

Two distinct failure modes have to be survived:

| | Failure mode | Shape |
|---|---|---|
| **A** | SNS unavailable, throttling, credentials expired | Broad. Affects *every* row at once. Self-resolves. |
| **B** | Poison message — oversized payload, wrong topic ARN, malformed body | Narrow. Affects *one* row. Never self-resolves. |

Any resilience mechanism chosen here has to be assessed against both.

## 2. Options considered

**Option A — Resilience4j circuit breaker** around the SNS publish call. Trips
open after a failure-rate threshold, short-circuits subsequent calls, allows
trial calls in half-open.

**Option B — per-row exponential backoff with a terminal dead-letter state.**
On failure the row records the error and schedules its own next attempt:

```sql
next_attempt_at = now() + make_interval(secs => LEAST(POWER(2, attempt_count)::int, :cap)),
status = CASE WHEN attempt_count + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END
```

The claim query only sees rows whose `next_attempt_at` has passed.

## 3. Decision

**Option B. No circuit breaker.**

## 4. Why

**1. The breaker covers one failure mode; backoff covers both.** A breaker is a
mechanism for failure mode A. Against B it does nothing useful — one poison row
never trips a threshold, it simply fails forever, and behind an open breaker it
fails forever *faster*. Backoff with an attempt cap handles A (every row backs
off together) and B (the bad row exhausts its attempts and lands in `FAILED`,
where it is queryable and alertable rather than invisibly looping).

**2. There is no caller to protect.** A breaker's primary value is refusing to
make a user wait on a call that is going to fail. On this path the waiting party
does not exist: the HTTP response went out at commit time, the money has already
moved, and the event is durable in the database. The resource a doomed call
wastes is one background thread on a scheduled poll. Failing fast buys nothing
that failing slowly does not already give us.

**3. Backoff already performs the load-shedding.** The second argument for a
breaker is giving a struggling downstream room to recover. Exponential backoff
does this by construction — after a handful of failures each row is retried on
the order of minutes, so the aggregate call rate against a sick topic collapses
on its own. Adding a breaker would suppress calls that backoff has already
suppressed.

**4. The cost is not zero.** A breaker means a dependency, a state machine, a
configuration surface (failure-rate threshold, sliding window type and size,
wait duration, permitted half-open calls) that has to be tuned against a traffic
shape we have not measured, and its own failure modes — most commonly a breaker
tuned for the wrong volume that either never trips or sticks open after recovery.
Two interacting retry mechanisms are materially harder to reason about than one,
and "why did this event take 40 minutes to publish" becomes a question with two
possible answers instead of one.

## 5. What this costs us — argued honestly

The case *for* the breaker, which is not weak:

> It doesn't hamper performance, it doesn't add much complexity, and it's an
> additional guard. In financial services, defence in depth is the house style.

That is a fair position, and the decision does **not** rest on the breaker being
harmful. It rests on it being *redundant with backoff* for mode A while being
*insufficient alone* for mode B — so adopting it means carrying two mechanisms
where one already covers both cases.

Accepted consequences:

- **Residual doomed calls.** During a total SNS outage the relay still makes some
  failing calls. Backoff bounds the rate; it does not drive it to zero. A breaker
  would get closer to zero.
- **No explicit "downstream is down" object** to inspect or expose. Mitigated,
  and arguably improved on, by `outbox.pending.oldest.age.seconds` — which
  measures the thing actually worth alerting on (are events getting out?) rather
  than a proxy for it (is a call failing?). A breaker can sit closed while the
  backlog grows; oldest-age cannot.
- **Fate sharing is not addressed.** With a single downstream this costs nothing.
  See the first revisit trigger.

## 6. Revisit when any of these becomes true

1. **The relay publishes to more than one downstream in the same loop.** This is
   the strongest trigger. One sick dependency would then delay events destined
   for healthy ones, and backoff — being per-row, not per-destination — cannot
   express that. A per-downstream breaker, or separate relays, earns its place.
2. **SNS publication moves onto the synchronous request path.** Then a caller
   exists, and §4.2 no longer holds.
3. **Failed publish attempts become a material cost line** at high volume against
   per-call pricing.
4. **Observed long outages** where the residual call rate under backoff is still
   enough to matter — i.e. §5's first consequence stops being acceptable in
   practice rather than in theory.

## 7. Related

- Poison-message handling and the `FAILED` dead-letter state: `README.md` §4
- Relay claim semantics (`FOR UPDATE SKIP LOCKED`): `README.md` §3, verified as
  experiment E6 in `verify_db_claims.sh`
- Retry on the *transactional* path is a separate concern with a separate
  mechanism (`@Retryable` on `WithdrawalService`, deliberately outside the
  transaction boundary) — not governed by this ADR.

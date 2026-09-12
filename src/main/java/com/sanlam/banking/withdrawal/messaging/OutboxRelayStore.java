package com.sanlam.banking.withdrawal.messaging;

import java.util.List;

/** The delivery side of the outbox. Used only by the relay. */
public interface OutboxRelayStore {

    /**
     * Claims a batch of due events. Correctness comes from holding a row lock while
     * markPublished runs in the same transaction - plain FOR UPDATE is already safe,
     * because a blocked worker re-evaluates its WHERE against rows that are no longer
     * PENDING. SKIP LOCKED buys liveness: measured on PostgreSQL 16, a second worker
     * waits 0.14s rather than 2.47s. Dropping the locking clause is what duplicates.
     *
     * <p>Costs strict global ordering. Per-account ordering would need the claim
     * partitioned by aggregate_id.
     */
    List<OutboxRecord> claimBatch(int batchSize);

    void markPublished(long id);

    /**
     * Transport could not take the message but the message is fine: count the attempt,
     * push next_attempt_at out by a capped exponential interval, leave the row PENDING.
     *
     * <p>No terminal state here, deliberately. An undeliverable AML-relevant event is
     * something to alert on, not to discard, and holding the rows PENDING is what keeps
     * outbox.pending.oldest.age.seconds honest - a terminal state would drop the lag
     * gauge to zero at the moment delivery had failed completely.
     */
    void markTransientFailure(long id, String error, int backoffCapSeconds);

    /**
     * The message was rejected and will never publish. Terminal on first occurrence:
     * there is nothing to wait for, and it delays everything behind it.
     */
    void markPermanentFailure(long id, String error);
}

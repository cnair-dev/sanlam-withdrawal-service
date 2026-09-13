package com.sanlam.banking.withdrawal.messaging;

import java.util.List;

/**
 * Everything the outbox does except append: claiming and marking on the delivery side,
 * counting and repairing on the operational side.
 *
 * <p>{@link OutboxAppender} is separate because it is the only part of the outbox that runs
 * inside the withdrawal's transaction, and the only part whose failure rolls back a
 * customer's money. That narrowing is worth a file. Splitting delivery from operations as
 * well would be interface segregation applied for its own sake - both sides talk to the
 * same table through the same bean, and nothing becomes unreachable by naming it twice.
 */
public interface OutboxRepository {

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

    long countPending();

    /** Oldest pending event age - a more meaningful lag SLO than queue depth. */
    long oldestPendingAgeSeconds();

    long countFailed();

    /**
     * Move a dead-lettered event back into the claim set once whatever made it
     * unpublishable has been fixed. Without this, recovery is an operator running UPDATE
     * against a financial system by hand.
     */
    boolean requeueFailed(long id);

    int purgePublishedOlderThanDays(int days);
}

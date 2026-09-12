package com.sanlam.banking.withdrawal.messaging;

import java.util.List;

public interface OutboxRepository {

    /** Written in the SAME transaction as the balance and ledger changes. */
    void append(long aggregateId, String eventType, String payload, int eventVersion);

    /**
     * Claim a batch of due events for this worker.
     *
     * Uses FOR UPDATE SKIP LOCKED. Without it, two application instances both
     * select the same rows and both publish them - verified: two workers each
     * claimed ids 1,2,3. With it they claimed 1,2,3 and 4,5,6 respectively.
     * The trade-off is that strict global ordering is given up in exchange for
     * parallel drain; per-account ordering would require partitioning the claim
     * by aggregate_id.
     */
    List<OutboxRecord> claimBatch(int batchSize);

    void markPublished(long id);

    /** Exponential backoff, then a terminal FAILED state that acts as a dead letter. */
    void markFailed(long id, String error, int maxAttempts, int backoffCapSeconds);

    long countPending();

    /** Oldest pending event age in seconds - the meaningful lag SLO, better than depth. */
    long oldestPendingAgeSeconds();

    long countFailed();

    int purgePublishedOlderThanDays(int days);
}

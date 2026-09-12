package com.sanlam.banking.withdrawal.messaging;

/**
 * Reading and repairing the outbox: what metrics, health checks, the retention
 * job and an operator need. Nothing here participates in delivering an event.
 */
public interface OutboxOperations {

    long countPending();

    /** Oldest pending event age in seconds - the meaningful lag SLO, better than depth. */
    long oldestPendingAgeSeconds();

    long countFailed();

    /**
     * Move dead-lettered events back into the claim set, once whatever made them
     * unpublishable has been fixed. Returns the number requeued.
     *
     * <p>Without this, recovery from a dead letter is an operator running UPDATE
     * against a financial system by hand, which is not a recovery procedure.
     */
    int requeueFailed();

    /** As above, for a single event. Returns true if it was in FAILED. */
    boolean requeueFailed(long id);

    int purgePublishedOlderThanDays(int days);
}

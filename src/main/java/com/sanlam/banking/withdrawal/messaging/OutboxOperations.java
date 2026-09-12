package com.sanlam.banking.withdrawal.messaging;

/**
 * Reading and repairing the outbox: what metrics, health checks, the retention job and
 * an operator need. Nothing here participates in delivering an event.
 */
public interface OutboxOperations {

    long countPending();

    /** Oldest pending event age - a more meaningful lag SLO than queue depth. */
    long oldestPendingAgeSeconds();

    long countFailed();

    /**
     * Move dead-lettered events back into the claim set once whatever made them
     * unpublishable has been fixed. Without this, recovery is an operator running UPDATE
     * against a financial system by hand.
     */
    int requeueFailed();

    /** As above, for a single event. Returns true if it was in FAILED. */
    boolean requeueFailed(long id);

    int purgePublishedOlderThanDays(int days);
}

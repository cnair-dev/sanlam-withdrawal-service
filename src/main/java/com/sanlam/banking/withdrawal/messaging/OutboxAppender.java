package com.sanlam.banking.withdrawal.messaging;

/**
 * Writing an event as part of the movement that caused it.
 *
 * <p>Split out because it is the only part of the outbox that runs inside the
 * withdrawal's transaction, and the only part whose failure rolls back a customer's
 * money. Claiming, publishing, counting and purging all happen afterwards on background
 * threads and should not be reachable from the code path that moves money.
 */
public interface OutboxAppender {

    /** Written in the SAME transaction as the balance and ledger changes. */
    void append(long aggregateId, String eventType, String payload, int eventVersion,
                String correlationId);
}

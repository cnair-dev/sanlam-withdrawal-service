package com.sanlam.banking.withdrawal.messaging;

/**
 * Writing an event as part of the movement that caused it.
 *
 * <p>Separated from the rest of the outbox because it is the only part of it
 * that runs inside the withdrawal's transaction, and the only part whose
 * failure rolls back a customer's money. Everything else the outbox does -
 * claiming, publishing, counting, purging, repairing - happens afterwards, on
 * background threads, and none of it should be reachable from the code path
 * that moves money.
 */
public interface OutboxAppender {

    /** Written in the SAME transaction as the balance and ledger changes. */
    void append(long aggregateId, String eventType, String payload, int eventVersion,
                String correlationId);
}

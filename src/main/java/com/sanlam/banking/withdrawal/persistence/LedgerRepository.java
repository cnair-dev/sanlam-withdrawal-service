package com.sanlam.banking.withdrawal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface LedgerRepository {

    /**
     * Write both legs of one movement: the customer account debited, the system settlement
     * account credited, both tagged with the same transactionId so that any INDIVIDUAL
     * movement can be proven to balance - not merely the ledger as a whole.
     *
     * <p>Returns the instant the ledger recorded, so the event and the response quote the
     * ledger rather than a second clock. Inside a transaction PostgreSQL's now() is the
     * transaction start time and does not advance, so both legs carry it and so does
     * everything downstream.
     *
     * @return the ledger's created_at - the one authoritative time for this movement
     */
    Instant recordWithdrawal(UUID transactionId, long accountId, long settlementAccountId,
                             BigDecimal amount, String currency, String correlationId);

    /** Reconciliation control: ledger-derived position for an account. */
    BigDecimal netMovementFor(long accountId);
}

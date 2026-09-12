package com.sanlam.banking.withdrawal.persistence;

import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerRepository {

    /**
     * Write both legs of one movement. Double-entry: the customer account is
     * debited and the system settlement account is credited by the same amount,
     * both tagged with the same transactionId so that any INDIVIDUAL movement
     * can be proven to balance - not merely the ledger as a whole.
     */
    void recordWithdrawal(UUID transactionId, long accountId, long settlementAccountId,
                          BigDecimal amount, String currency, String correlationId);

    /** Reconciliation control: ledger-derived position for an account. */
    BigDecimal netMovementFor(long accountId);
}

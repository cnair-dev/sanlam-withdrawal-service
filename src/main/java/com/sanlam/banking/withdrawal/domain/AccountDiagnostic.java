package com.sanlam.banking.withdrawal.domain;

import java.math.BigDecimal;

/**
 * Read only on the failure path, to turn "the conditional UPDATE matched zero rows" into a
 * specific reason. The happy path issues no diagnostic query.
 *
 * <p>A second statement is a second snapshot, so this observes the account slightly later
 * than the UPDATE was rejected against. It can mislabel a rejection but never cause one -
 * the decision was already made by the UPDATE.
 */
public record AccountDiagnostic(
        long accountId,
        String status,
        BigDecimal balance,
        String currency
) {
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}

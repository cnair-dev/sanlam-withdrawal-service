package com.sanlam.banking.withdrawal.domain;

import java.math.BigDecimal;

/**
 * Read ONLY on the failure path, to turn "the conditional UPDATE matched zero
 * rows" into a specific reason. The happy path never issues this query, so a
 * successful withdrawal remains a single database round trip.
 */
public record AccountDiagnostic(
        long accountId,
        String status,
        BigDecimal balance,
        BigDecimal overdraftLimit
) {
    public boolean isActive() { return "ACTIVE".equals(status); }

    public boolean hasAvailableFunds(BigDecimal amount) {
        return balance.subtract(amount).compareTo(overdraftLimit.negate()) >= 0;
    }
}

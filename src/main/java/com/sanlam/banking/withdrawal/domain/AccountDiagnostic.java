package com.sanlam.banking.withdrawal.domain;

import java.math.BigDecimal;

/**
 * Read only on the failure path, to turn "the conditional UPDATE matched zero
 * rows" into a specific reason. The happy path issues no diagnostic query.
 *
 * <p>This is a second statement and therefore a second snapshot, so it observes
 * the account slightly later than the UPDATE was rejected against. It can only
 * mislabel a rejection, never cause one: the decision was already made and
 * committed to by the UPDATE.
 */
public record AccountDiagnostic(
        long accountId,
        String status,
        BigDecimal balance
) {
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}

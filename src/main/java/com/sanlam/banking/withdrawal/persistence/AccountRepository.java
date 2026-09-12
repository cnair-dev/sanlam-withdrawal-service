package com.sanlam.banking.withdrawal.persistence;

import com.sanlam.banking.withdrawal.domain.AccountDiagnostic;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * An interface with one implementation on purpose: it is the seam the service is
 * unit-tested against, and the cost is one file. Not a speculative port for a second
 * database.
 */
public interface AccountRepository {

    /**
     * Atomically debit an account, enforcing status, currency and the balance invariant
     * inside one SQL statement.
     *
     * @return the resulting balance, or empty if nothing was debited - no such account,
     *         not ACTIVE, wrong currency, or insufficient funds, which {@link #diagnose}
     *         distinguishes
     */
    Optional<BigDecimal> debitIfPermitted(long accountId, BigDecimal amount, String currency);

    /** Failure-path only: explains why {@link #debitIfPermitted} matched zero rows. */
    Optional<AccountDiagnostic> diagnose(long accountId);
}

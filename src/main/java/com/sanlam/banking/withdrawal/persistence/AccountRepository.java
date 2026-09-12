package com.sanlam.banking.withdrawal.persistence;

import com.sanlam.banking.withdrawal.domain.AccountDiagnostic;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Kept as an interface with a single implementation on purpose: it is the
 * seam the service is unit-tested against (Dependency Inversion), and the cost
 * is one file. It is NOT a speculative "port" for a second database.
 */
public interface AccountRepository {

    /**
     * Atomically debit an account, enforcing status and the available-balance
     * invariant inside a single SQL statement.
     *
     * @return the resulting balance, or empty if nothing was debited.
     *         Empty means one of: no such account, account not ACTIVE, or
     *         insufficient available funds - {@link #diagnose} distinguishes them.
     */
    Optional<BigDecimal> debitIfPermitted(long accountId, BigDecimal amount);

    /** Failure-path only: explains why {@link #debitIfPermitted} matched zero rows. */
    Optional<AccountDiagnostic> diagnose(long accountId);
}

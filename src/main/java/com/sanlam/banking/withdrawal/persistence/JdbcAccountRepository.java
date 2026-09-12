package com.sanlam.banking.withdrawal.persistence;

import com.sanlam.banking.withdrawal.domain.AccountDiagnostic;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcAccountRepository implements AccountRepository {

    private final JdbcClient jdbc;

    /**
     * The single most important statement in this service.
     *
     * The original code did SELECT balance, compared it in Java, then UPDATE.
     * Between those two steps another request can read the same balance and
     * both can pass the check - verified to produce a final balance of -20.00
     * when two concurrent withdrawals of 60 hit a balance of 100.
     *
     * Here the check and the write are ONE statement. The database takes the
     * row lock itself and evaluates the WHERE clause against the row it is
     * about to modify. A concurrent updater blocks; when the first transaction
     * commits, PostgreSQL RE-EVALUATES this WHERE clause against the newly
     * committed row version. So the second statement either still qualifies
     * (and applies correctly) or matches zero rows. A lost update is not
     * representable.
     *
     * This is safe at READ COMMITTED, and READ COMMITTED is REQUIRED rather
     * than merely sufficient: at REPEATABLE READ or SERIALIZABLE the same
     * statement aborts with "could not serialize access due to concurrent
     * update" instead of re-evaluating, which would force an application-level
     * retry loop for an ordinary insufficient-funds outcome.
     *
     * RETURNING gives us the post-debit balance without a second round trip.
     */
    @Override
    public Optional<BigDecimal> debitIfPermitted(long accountId, BigDecimal amount) {
        return jdbc.sql("""
                    UPDATE accounts
                       SET balance = balance - :amount
                     WHERE id = :accountId
                       AND status = 'ACTIVE'
                       AND balance - :amount >= -overdraft_limit
                 RETURNING balance
                """)
                .param("accountId", accountId)
                .param("amount", amount)
                .query(BigDecimal.class)
                .optional();
    }

    @Override
    public Optional<AccountDiagnostic> diagnose(long accountId) {
        return jdbc.sql("""
                    SELECT id, status, balance, overdraft_limit
                      FROM accounts
                     WHERE id = :accountId
                """)
                .param("accountId", accountId)
                .query((rs, rowNum) -> new AccountDiagnostic(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("overdraft_limit")))
                .optional();
    }
}

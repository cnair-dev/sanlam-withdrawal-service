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
     * Debits the account, with the balance and status checks in the same
     * statement as the write.
     *
     * <p>The original code read the balance, compared it in Java, then issued a
     * separate UPDATE. Two callers can read the same balance, both pass the
     * check and both debit: two concurrent withdrawals of 60 against a balance
     * of 100 leave -20.00. Keeping the predicate in the UPDATE means the row
     * lock and the check are the same operation, so there is no window between
     * them.
     *
     * <p>This depends on READ COMMITTED, which is pinned at the pool rather than
     * assumed. A blocked UPDATE re-reads the committed row version and
     * re-evaluates this WHERE clause against it, so the second caller either
     * still qualifies or matches zero rows. REPEATABLE READ and above cannot do
     * that without breaking their own snapshot, so they raise a serialization
     * failure instead and an ordinary insufficient-funds outcome would come back
     * as an error needing an application retry.
     *
     * <p>System accounts are excluded here and in {@code diagnose}, so the
     * settlement account cannot be drawn on through the customer API and is not
     * reported as existing by it. Previously nothing forbade it - the only thing
     * stopping a withdrawal from account 9000 was the coincidence of its balance
     * being zero, which is not a control. Treating it as absent rather than
     * forbidden also avoids confirming the internal account structure to a
     * caller who guessed an id.
     *
     * <p>Zero rows has three possible causes here - no such customer account,
     * not active, or insufficient funds. {@code diagnose} separates them. Every
     * other failure mode arrives as a thrown exception, so zero rows is always a
     * business outcome.
     *
     * @return the balance after the debit, or empty if the account did not qualify
     */
    @Override
    public Optional<BigDecimal> debitIfPermitted(long accountId, BigDecimal amount) {
        return jdbc.sql("""
                    UPDATE accounts
                       SET balance = balance - :amount
                     WHERE id = :accountId
                       AND is_system = FALSE
                       AND status = 'ACTIVE'
                       AND balance >= :amount
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
                    SELECT id, status, balance
                      FROM accounts
                     WHERE id = :accountId
                       AND is_system = FALSE
                """)
                .param("accountId", accountId)
                .query((rs, rowNum) -> new AccountDiagnostic(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBigDecimal("balance")))
                .optional();
    }
}

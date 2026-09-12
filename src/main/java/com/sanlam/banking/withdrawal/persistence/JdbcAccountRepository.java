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
     * Debits the account, with the balance and status checks in the same statement as
     * the write.
     *
     * <p>The original code read the balance, compared it in Java, then issued a separate
     * UPDATE. Two callers can read the same balance, both pass the check and both debit:
     * two concurrent withdrawals of 60 against a balance of 100 leave -20.00. Keeping the
     * predicate in the UPDATE makes the row lock and the check one operation.
     *
     * <p>That depends on READ COMMITTED, which is pinned at the pool rather than assumed.
     * A blocked UPDATE re-reads the committed row version and re-evaluates this WHERE
     * against it, so the second caller either still qualifies or matches zero rows.
     * REPEATABLE READ and above cannot do that without breaking their own snapshot, so
     * they raise a serialization failure and an ordinary insufficient-funds outcome comes
     * back as an error needing an application retry.
     *
     * <p>Currency is a predicate rather than an assumption. The service settles in one
     * currency, and a ledger pair that debits a customer in one and credits settlement in
     * another does not balance in any meaningful sense - so an account denominated in
     * anything else is refused here rather than silently relabelled. Supporting more than
     * one currency means a settlement account per currency, not a looser check.
     *
     * <p>System accounts are excluded here and in {@code diagnose}, so the settlement
     * account cannot be drawn on through the customer API and is not reported as existing
     * by it. Previously the only thing stopping a withdrawal from account 9000 was the
     * coincidence of its balance being zero, which is not a control. Absent rather than
     * forbidden also avoids confirming the internal account structure to a caller who
     * guessed an id.
     *
     * @return the balance after the debit, or empty if the account did not qualify
     */
    @Override
    public Optional<BigDecimal> debitIfPermitted(long accountId, BigDecimal amount, String currency) {
        return jdbc.sql("""
                    UPDATE accounts
                       SET balance = balance - :amount
                     WHERE id = :accountId
                       AND is_system = FALSE
                       AND status = 'ACTIVE'
                       AND currency = :currency
                       AND balance >= :amount
                 RETURNING balance
                """)
                .param("accountId", accountId)
                .param("amount", amount)
                .param("currency", currency)
                .query(BigDecimal.class)
                .optional();
    }

    @Override
    public Optional<AccountDiagnostic> diagnose(long accountId) {
        return jdbc.sql("""
                    SELECT id, status, balance, currency
                      FROM accounts
                     WHERE id = :accountId
                       AND is_system = FALSE
                """)
                .param("accountId", accountId)
                .query((rs, rowNum) -> new AccountDiagnostic(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBigDecimal("balance"),
                        rs.getString("currency")))
                .optional();
    }
}

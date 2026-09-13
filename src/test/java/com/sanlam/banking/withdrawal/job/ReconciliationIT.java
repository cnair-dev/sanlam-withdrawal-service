package com.sanlam.banking.withdrawal.job;

import com.sanlam.banking.withdrawal.AbstractPostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A control nobody tests is a control nobody can rely on. These assert that reconciliation
 * detects the two breaches it exists to detect, and names the account, so they do not pass
 * by coincidence of whatever else the suite left behind.
 */
@SpringBootTest
@Import(AbstractPostgresIT.TestPublisherConfig.class)
class ReconciliationIT extends AbstractPostgresIT {

    private static final AtomicLong NEXT_ACCOUNT = new AtomicLong(830_000L);
    private static final long SETTLEMENT_ID = 9000L;

    @Autowired ReconciliationJob reconciliation;
    @Autowired JdbcClient jdbc;
    @Autowired MeterRegistry meterRegistry;

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    /** An account funded the way the seed funds one: balance and a balanced ledger pair. */
    private long seedFundedAccount(String amount) {
        long id = NEXT_ACCOUNT.incrementAndGet();
        jdbc.sql("INSERT INTO accounts(id, balance, currency, status) VALUES (:id, CAST(:amt AS numeric), 'ZAR', 'ACTIVE')")
                .param("id", id).param("amt", amount).update();
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id,         'CREDIT', CAST(:amt AS numeric), 'ZAR', 'recon-test'),
                       (:txn, :settlement, 'DEBIT',  CAST(:amt AS numeric), 'ZAR', 'recon-test')
                """)
                .param("txn", UUID.randomUUID()).param("id", id)
                .param("settlement", SETTLEMENT_ID).param("amt", amount).update();
        return id;
    }

    /** Does this specific account disagree with its own ledger? */
    private boolean drifts(long accountId) {
        return jdbc.sql("""
                SELECT a.balance <> COALESCE((
                           SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                             FROM ledger_entry l
                            WHERE l.account_id = a.id AND l.currency = a.currency), 0)
                  FROM accounts a WHERE a.id = :id
                """).param("id", accountId).query(Boolean.class).single();
    }

    @Test
    @DisplayName("A consistent account reconciles clean")
    void consistentLedgerReconciles() {
        long accountId = seedFundedAccount("400.00");

        reconciliation.reconcile();

        assertThat(drifts(accountId)).isFalse();
    }

    @Test
    @DisplayName("A one-legged transaction is reported as unbalanced")
    void unbalancedTransactionIsDetected() {
        UUID orphan = UUID.randomUUID();
        long accountId = seedFundedAccount("100.00");

        // A credit with no matching debit: money appearing from nowhere.
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id, 'CREDIT', 25.00, 'ZAR', 'recon-test-unbalanced')
                """).param("txn", orphan).param("id", accountId).update();

        reconciliation.reconcile();

        assertThat(gauge("ledger.unbalanced.transactions")).isGreaterThanOrEqualTo(1.0);
        assertThat(drifts(accountId))
                .as("the ledger now says 125.00 and the cached balance says 100.00")
                .isTrue();
    }

    @Test
    @DisplayName("A balance moved with no ledger entry behind it is caught")
    void balanceTamperingIsDetected() {
        long accountId = seedFundedAccount("700.00");
        assertThat(drifts(accountId)).isFalse();

        // The failure this control exists for: a cached balance moved with nothing in the
        // ledger behind it. A direct UPDATE, a restore, a bad migration.
        jdbc.sql("UPDATE accounts SET balance = 999.00 WHERE id = :id").param("id", accountId).update();

        reconciliation.reconcile();

        assertThat(gauge("ledger.drifting.accounts")).isGreaterThanOrEqualTo(1.0);
        assertThat(drifts(accountId))
                .as("named explicitly, so this cannot pass on another test's leftovers")
                .isTrue();

        jdbc.sql("UPDATE accounts SET balance = 700.00 WHERE id = :id").param("id", accountId).update();
    }
}

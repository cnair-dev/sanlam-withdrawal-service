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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A control nobody tests is a control nobody can rely on. These assert that the
 * reconciliation actually detects the breaches it exists to detect, and - just
 * as importantly - that the incremental pass is blind to the one class of
 * breach only the full sweep can find.
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

    private double breachesDetected() {
        return meterRegistry.get("ledger.reconciliation.breaches").counter().count();
    }

    private long watermark() {
        return jdbc.sql("SELECT last_ledger_id FROM reconciliation_watermark").query(Long.class).single();
    }

    /** An account funded the way V2 funds one: balance and a balanced ledger pair. */
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

    @Test
    @DisplayName("A consistent ledger reconciles clean, and the watermark advances")
    void consistentLedgerReconciles() {
        seedFundedAccount("400.00");
        long before = watermark();

        double breachesBefore = breachesDetected();

        reconciliation.reconcileIncrementally();

        assertThat(breachesDetected()).isEqualTo(breachesBefore);
        assertThat(watermark()).isGreaterThan(before);
    }

    @Test
    @DisplayName("A one-legged transaction is caught by the incremental pass")
    void unbalancedTransactionIsDetected() {
        long accountId = seedFundedAccount("100.00");
        reconciliation.reconcileIncrementally();   // start from a clean watermark

        // A credit with no matching debit: money appearing from nowhere.
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id, 'CREDIT', 25.00, 'ZAR', 'recon-test-unbalanced')
                """).param("txn", UUID.randomUUID()).param("id", accountId).update();

        double breachesBefore = breachesDetected();

        reconciliation.reconcileIncrementally();

        assertThat(breachesDetected()).isGreaterThan(breachesBefore);
    }

    @Test
    @DisplayName("A balance changed with no ledger entry is invisible to the incremental pass and caught by the full sweep")
    void balanceTamperingNeedsTheFullSweep() {
        long accountId = seedFundedAccount("700.00");
        reconciliation.reconcileIncrementally();

        // The failure this control exists for: a cached balance moved with
        // nothing in the ledger behind it. A direct UPDATE, a restore, a bad
        // migration. No new ledger entry means no new watermark range.
        jdbc.sql("UPDATE accounts SET balance = 999.00 WHERE id = :id").param("id", accountId).update();

        double breachesBefore = breachesDetected();
        reconciliation.reconcileIncrementally();
        assertThat(breachesDetected())
                .as("the incremental pass never looks at this account again - no new ledger entry")
                .isEqualTo(breachesBefore);

        reconciliation.reconcileEverything();
        assertThat(gauge("ledger.drifting.accounts"))
                .as("the full sweep is why it still gets caught")
                .isGreaterThanOrEqualTo(1.0);

        // Leave the fixture consistent for anything that runs after this.
        jdbc.sql("UPDATE accounts SET balance = 700.00 WHERE id = :id").param("id", accountId).update();
    }

    @Test
    @DisplayName("Two instances reconciling at once count a breach once, not twice")
    void watermarkRowIsTheLease() throws Exception {
        long accountId = seedFundedAccount("100.00");
        reconciliation.reconcileIncrementally();

        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id, 'CREDIT', 25.00, 'ZAR', 'recon-test-lease')
                """).param("txn", UUID.randomUUID()).param("id", accountId).update();

        // Every instance runs this schedule. The barrier forces the overlap that would
        // otherwise be a matter of timing: both threads enter with the same watermark
        // visible, and only the row lock stops both of them reporting the same breach.
        double before = breachesDetected();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Runnable pass = () -> {
            try {
                startTogether.await();
                reconciliation.reconcileIncrementally();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Thread a = Thread.ofVirtual().start(pass);
        Thread b = Thread.ofVirtual().start(pass);
        a.join();
        b.join();

        // One orphaned credit is two breaches: the transaction does not balance, and the
        // account's cached balance no longer matches its net ledger movement. The point of
        // the assertion is that it is two and not four - the second pass blocks on the
        // watermark row, then finds an empty window and reports nothing.
        assertThat(breachesDetected() - before)
                .as("both instances ran, one did the work")
                .isEqualTo(2.0);
    }
}

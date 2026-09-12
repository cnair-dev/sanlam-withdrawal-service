package com.sanlam.banking.withdrawal.application;

import com.sanlam.banking.withdrawal.AbstractPostgresIT;
import com.sanlam.banking.withdrawal.api.dto.WithdrawalResponse;
import com.sanlam.banking.withdrawal.domain.WithdrawalCommand;
import com.sanlam.banking.withdrawal.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The highest-value test in the suite: it PROVES the concurrency claim rather
 * than asserting it.
 *
 * Note what is asserted. "The balance never went negative" is a weak property -
 * it would also pass if updates were silently LOST, which is the other failure
 * mode of a check-then-act implementation. The assertions below pin the exact
 * outcome: the number of successes, the resulting balance, and agreement with
 * the ledger. Only a correct implementation satisfies all three.
 */
@SpringBootTest
@Import(AbstractPostgresIT.TestPublisherConfig.class)
class WithdrawalConcurrencyIT extends AbstractPostgresIT {

    private static final long ACCOUNT_ID = 7001L;
    private static final BigDecimal OPENING = new BigDecimal("1000.00");
    private static final BigDecimal AMOUNT  = new BigDecimal("100.00");
    private static final int THREADS = 50;
    private static final int EXPECTED_SUCCESSES = 10;   // 1000 / 100

    @Autowired WithdrawalService withdrawalService;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seedAccount() {
        jdbc.sql("DELETE FROM ledger_entry WHERE account_id = :id").param("id", ACCOUNT_ID).update();
        jdbc.sql("DELETE FROM outbox_event WHERE aggregate_id = :id").param("id", ACCOUNT_ID).update();
        jdbc.sql("DELETE FROM idempotency_key WHERE account_id = :id").param("id", ACCOUNT_ID).update();
        jdbc.sql("DELETE FROM accounts WHERE id = :id").param("id", ACCOUNT_ID).update();
        jdbc.sql("""
                INSERT INTO accounts(id, balance, currency, status, overdraft_limit)
                VALUES (:id, :balance, 'ZAR', 'ACTIVE', 0.00)
                """).param("id", ACCOUNT_ID).param("balance", OPENING).update();
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (gen_random_uuid(), :id, 'CREDIT', :amount, 'ZAR', 'test-opening')
                """).param("id", ACCOUNT_ID).param("amount", OPENING).update();
    }

    @Test
    @DisplayName("50 concurrent withdrawals of 100 against a balance of 1000: exactly 10 succeed, none overdraw")
    void concurrentWithdrawalsNeverOverdrawAndNeverLoseUpdates() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();           // release all threads at once
                    try {
                        WithdrawalResponse r = withdrawalService.withdraw(new WithdrawalCommand(
                                ACCOUNT_ID, AMOUNT, "load-test",
                                UUID.randomUUID().toString(), "corr-" + UUID.randomUUID()));
                        assertThat(r.resultingBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                        successes.incrementAndGet();
                    } catch (InsufficientFundsException e) {
                        insufficient.incrementAndGet();
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        BigDecimal finalBalance = jdbc.sql("SELECT balance FROM accounts WHERE id = :id")
                .param("id", ACCOUNT_ID).query(BigDecimal.class).single();

        // 1. No overdraft.
        assertThat(finalBalance).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // 2. No LOST updates - exactly the affordable number of withdrawals applied.
        assertThat(successes.get()).isEqualTo(EXPECTED_SUCCESSES);
        assertThat(insufficient.get()).isEqualTo(THREADS - EXPECTED_SUCCESSES);

        // 3. Arithmetic is exact.
        assertThat(finalBalance).isEqualByComparingTo(
                OPENING.subtract(AMOUNT.multiply(BigDecimal.valueOf(EXPECTED_SUCCESSES))));

        // 4. The ledger agrees with the cached balance.
        BigDecimal ledgerDerived = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
                  FROM ledger_entry WHERE account_id = :id
                """).param("id", ACCOUNT_ID).query(BigDecimal.class).single();
        assertThat(ledgerDerived).isEqualByComparingTo(finalBalance);

        // 5. Every successful withdrawal produced exactly one outbox event.
        Long outboxCount = jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :id")
                .param("id", ACCOUNT_ID).query(Long.class).single();
        assertThat(outboxCount).isEqualTo(EXPECTED_SUCCESSES);

        // 6. Every ledger transaction balanced.
        Long unbalanced = jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT transaction_id FROM ledger_entry
                     WHERE transaction_id IN (SELECT transaction_id FROM ledger_entry WHERE account_id = :id)
                     GROUP BY transaction_id
                    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                ) t
                """).param("id", ACCOUNT_ID).query(Long.class).single();
        assertThat(unbalanced).isEqualTo(1L); // only the synthetic test-opening credit is unpaired
    }

    @Test
    @DisplayName("Concurrent requests sharing one Idempotency-Key debit exactly once")
    void duplicateIdempotencyKeyDebitsOnce() throws Exception {
        String sharedKey = "shared-" + UUID.randomUUID();
        int attempts = 20;
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    withdrawalService.withdraw(new WithdrawalCommand(
                            ACCOUNT_ID, AMOUNT, "dup-client", sharedKey, "corr-dup"));
                    ok.incrementAndGet();
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        }

        // All callers get a successful response...
        assertThat(ok.get()).isEqualTo(attempts);

        // ...but the money moved exactly once.
        BigDecimal balance = jdbc.sql("SELECT balance FROM accounts WHERE id = :id")
                .param("id", ACCOUNT_ID).query(BigDecimal.class).single();
        assertThat(balance).isEqualByComparingTo(OPENING.subtract(AMOUNT));

        Long debits = jdbc.sql("""
                SELECT count(*) FROM ledger_entry
                 WHERE account_id = :id AND direction = 'DEBIT'
                """).param("id", ACCOUNT_ID).query(Long.class).single();
        assertThat(debits).isEqualTo(1L);
    }
}

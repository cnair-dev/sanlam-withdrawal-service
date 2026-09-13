package com.sanlam.banking.withdrawal.application;

import com.sanlam.banking.withdrawal.AbstractPostgresIT;
import com.sanlam.banking.withdrawal.domain.WithdrawalCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What a caller actually gets when it loses a race for a contended account row.
 *
 * <p>This is the path the pool's lock_timeout exists to bound, and nothing covered it -
 * which is how the service spent four commits returning 500 for it while the configuration
 * comment and the handler javadoc both claimed 503.
 */
@SpringBootTest
@Import(AbstractPostgresIT.TestPublisherConfig.class)
class LockContentionIT extends AbstractPostgresIT {

    private static final AtomicLong NEXT_ACCOUNT = new AtomicLong(860_000L);
    private static final long SETTLEMENT_ID = 9000L;

    @Autowired WithdrawalService withdrawalService;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate txTemplate;

    private long seed() {
        long id = NEXT_ACCOUNT.incrementAndGet();
        jdbc.sql("INSERT INTO accounts(id, balance, currency, status) VALUES (:id, 500.00, 'ZAR', 'ACTIVE')")
                .param("id", id).update();
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id,         'CREDIT', 500.00, 'ZAR', 'lock-test'),
                       (:txn, :settlement, 'DEBIT',  500.00, 'ZAR', 'lock-test')
                """)
                .param("txn", UUID.randomUUID()).param("id", id)
                .param("settlement", SETTLEMENT_ID).update();
        return id;
    }

    @Test
    @DisplayName("A row held past lock_timeout surfaces as a recognisable, retryable-by-the-caller failure")
    void contendedRowIsNotAnInternalError() throws Exception {
        long accountId = seed();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = Thread.ofVirtual().start(() -> txTemplate.executeWithoutResult(tx -> {
            jdbc.sql("UPDATE accounts SET balance = balance - 1 WHERE id = :id")
                    .param("id", accountId).update();
            holding.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        Throwable thrown = catchThrowable(() -> withdrawalService.withdraw(new WithdrawalCommand(
                accountId, new BigDecimal("100.00"), "lock-client",
                UUID.randomUUID().toString(), "corr-lock")));

        release.countDown();
        holder.join();

        // Pinning what actually happens, not what the configuration comment used to claim.
        // Under Spring 6.1's SQLExceptionSubclassTranslator there is no mapping for SQLSTATE
        // class 55, so this is UncategorizedSQLException and NOT a TransientDataAccessException
        // - which is why @Retryable leaves it alone and why the handler matches on SQLSTATE.
        // If a future Spring maps 55P03 to something specific, this test says so.
        assertThat(thrown).isInstanceOf(UncategorizedSQLException.class);
        assertThat(thrown).isNotInstanceOf(TransientDataAccessException.class);
        assertThat(((UncategorizedSQLException) thrown).getSQLException().getSQLState())
                .as("the SQLSTATE the handler keys on")
                .isEqualTo("55P03");
    }
}

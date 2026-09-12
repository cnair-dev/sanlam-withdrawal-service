package com.sanlam.banking.withdrawal.job;

import com.sanlam.banking.withdrawal.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention deletes from tables that hold a customer's transaction history, so what it
 * does NOT delete matters as much as what it does.
 */
@SpringBootTest
@Import(AbstractPostgresIT.TestPublisherConfig.class)
class RetentionIT extends AbstractPostgresIT {

    @Autowired RetentionJob retention;
    @Autowired JdbcClient jdbc;

    private long insertOutboxRow(String state, String publishedAt) {
        return jdbc.sql("""
                INSERT INTO outbox_event(aggregate_id, event_type, payload, event_version,
                                         status, published_at, correlation_id)
                VALUES (1001, 'test.event', '{}', 1, :state,
                        now() - CAST(:publishedAt AS interval),
                        'retention-test')
                RETURNING id
                """)
                .param("state", state).param("publishedAt", publishedAt)
                .query(Long.class).single();
    }

    private boolean outboxRowExists(long id) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE id = :id")
                .param("id", id).query(Long.class).single() == 1L;
    }

    @Test
    @DisplayName("Published rows past the window go; recent ones and undelivered ones stay")
    void purgesOnlyWhatHasDoneItsJob() {
        long old      = insertOutboxRow("PUBLISHED", "30 days");
        long recent   = insertOutboxRow("PUBLISHED", "1 hour");
        long pending  = insertOutboxRow("PENDING", null);
        long failed   = insertOutboxRow("FAILED", null);

        retention.purge();

        assertThat(outboxRowExists(old)).as("delivered and past the forensics window").isFalse();
        assertThat(outboxRowExists(recent)).as("delivered but still inside the window").isTrue();
        assertThat(outboxRowExists(pending)).as("never delivered - purging it loses the event").isTrue();
        assertThat(outboxRowExists(failed)).as("dead-lettered, still awaiting an operator").isTrue();
    }

    @Test
    @DisplayName("Expired idempotency keys go, live ones stay")
    void purgesExpiredKeysOnly() {
        String liveKey    = "retention-live-" + UUID.randomUUID();
        String expiredKey = "retention-expired-" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO idempotency_key(client_id, idempotency_key, request_hash, account_id, expires_at)
                VALUES ('retention-test', :live,    'hash', 1001, now() + interval '1 hour'),
                       ('retention-test', :expired, 'hash', 1001, now() - interval '1 hour')
                """)
                .param("live", liveKey).param("expired", expiredKey).update();

        retention.purge();

        assertThat(keyExists(liveKey)).isTrue();
        assertThat(keyExists(expiredKey)).isFalse();
    }

    @Test
    @DisplayName("The ledger is never purged - FICA keeps financial records for seven years")
    void ledgerIsNeverTouched() {
        UUID txn = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency,
                                         correlation_id, created_at)
                VALUES (:txn, 1001, 'DEBIT',  1.00, 'ZAR', 'retention-test', now() - interval '3650 days'),
                       (:txn, 9000, 'CREDIT', 1.00, 'ZAR', 'retention-test', now() - interval '3650 days')
                """).param("txn", txn).update();

        retention.purge();

        long remaining = jdbc.sql("SELECT count(*) FROM ledger_entry WHERE transaction_id = :txn")
                .param("txn", txn).query(Long.class).single();
        assertThat(remaining).as("ten years old and still not the retention job's business").isEqualTo(2L);
    }

    private boolean keyExists(String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_key WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single() == 1L;
    }
}

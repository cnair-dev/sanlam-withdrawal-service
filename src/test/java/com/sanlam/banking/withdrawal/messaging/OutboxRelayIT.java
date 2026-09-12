package com.sanlam.banking.withdrawal.messaging;

import com.sanlam.banking.withdrawal.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(AbstractPostgresIT.TestPublisherConfig.class)
class OutboxRelayIT extends AbstractPostgresIT {

    @Autowired OutboxRelay relay;
    @Autowired OutboxRepository outboxRepository;
    @Autowired RecordingEventPublisher publisher;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outbox_event").update();
        publisher.published.clear();
        publisher.setFailing(false);
    }

    @Test
    @DisplayName("Relay drains pending events and marks them published")
    void drainsPendingEvents() {
        outboxRepository.append(1001L, "withdrawal.completed", "{\"a\":1}", 1);
        outboxRepository.append(1002L, "withdrawal.completed", "{\"a\":2}", 1);

        relay.drain();

        assertThat(publisher.published).hasSize(2);
        assertThat(outboxRepository.countPending()).isZero();
    }

    @Test
    @DisplayName("A failing publish backs off exponentially and stays PENDING until attempts are exhausted")
    void failedPublishBacksOffThenDeadLetters() {
        outboxRepository.append(1001L, "withdrawal.completed", "{\"a\":1}", 1);
        publisher.setFailing(true);

        relay.drain();

        var row = jdbc.sql("""
                SELECT attempt_count, status, last_error, next_attempt_at > now() AS backed_off
                  FROM outbox_event LIMIT 1
                """).query((rs, n) -> List.of(
                        rs.getInt("attempt_count"), rs.getString("status"),
                        rs.getString("last_error"), rs.getBoolean("backed_off")))
                .single();

        assertThat(row.get(0)).isEqualTo(1);            // attempt recorded
        assertThat(row.get(1)).isEqualTo("PENDING");    // not yet dead-lettered
        assertThat((String) row.get(2)).contains("simulated SNS outage");

        // Exhaust the remaining attempts; the row must end in the terminal
        // FAILED state rather than being retried forever.
        jdbc.sql("UPDATE outbox_event SET attempt_count = 9, next_attempt_at = now()").update();
        relay.drain();

        assertThat(outboxRepository.countFailed()).isEqualTo(1L);
        assertThat(outboxRepository.countPending()).isZero();
    }

    @Test
    @DisplayName("Backed-off events are not re-claimed before next_attempt_at")
    void respectsBackoffWindow() {
        outboxRepository.append(1001L, "withdrawal.completed", "{\"a\":1}", 1);
        publisher.setFailing(true);
        relay.drain();

        publisher.setFailing(false);
        publisher.published.clear();
        relay.drain();   // still inside the backoff window

        assertThat(publisher.published).isEmpty();

        jdbc.sql("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second'").update();
        relay.drain();
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    @DisplayName("Two concurrent relay workers never claim the same event (FOR UPDATE SKIP LOCKED)")
    void concurrentWorkersClaimDisjointBatches() throws Exception {
        for (int i = 0; i < 6; i++) {
            outboxRepository.append(1000L + i, "withdrawal.completed", "{\"i\":" + i + "}", 1);
        }

        // Two independent transactions claiming concurrently. Without SKIP LOCKED
        // the second worker would block and then return the SAME rows, causing
        // every event to be published twice.
        var worker = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.concurrent.CopyOnWriteArrayList<List<Long>>();

        Runnable claim = () -> {
            var ids = jdbc.sql("""
                    SELECT id FROM outbox_event
                     WHERE status = 'PENDING' AND next_attempt_at <= now()
                     ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 3
                    """).query(Long.class).list();
            results.add(ids);
        };

        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Thread a = Thread.ofVirtual().start(() -> txTemplate.executeWithoutResult(s -> {
            claim.run();
            try { worker.await(); } catch (InterruptedException ignored) { }
        }));
        Thread.sleep(300);
        Thread b = Thread.ofVirtual().start(() -> txTemplate.executeWithoutResult(s -> claim.run()));
        b.join();
        worker.countDown();
        a.join();

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).doesNotContainAnyElementsOf(results.get(1));
        assertThat(results.get(0)).hasSize(3);
        assertThat(results.get(1)).hasSize(3);
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
}

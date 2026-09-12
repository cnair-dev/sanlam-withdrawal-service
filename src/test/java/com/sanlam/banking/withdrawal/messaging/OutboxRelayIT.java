package com.sanlam.banking.withdrawal.messaging;

import com.sanlam.banking.withdrawal.AbstractPostgresIT;
import com.sanlam.banking.withdrawal.config.OutboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(AbstractPostgresIT.TestPublisherConfig.class)
class OutboxRelayIT extends AbstractPostgresIT {

    @Autowired OutboxRelay relay;
    @Autowired OutboxRepository outboxRepository;
    @Autowired RecordingEventPublisher publisher;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired OutboxProperties properties;

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
        assertThat((Boolean) row.get(3)).as("next_attempt_at moved into the future").isTrue();

        // Jump to the last attempt and let it fail: the row must end in the
        // terminal FAILED state rather than being retried forever. Derived from
        // configuration so raising max-attempts does not quietly stop this
        // asserting the transition.
        jdbc.sql("UPDATE outbox_event SET attempt_count = :last, next_attempt_at = now()")
                .param("last", properties.maxAttempts() - 1).update();
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

        // Pin the window rather than racing it. The first backoff is one second,
        // so relying on two drains completing inside it makes this test fail on
        // a loaded machine for reasons that have nothing to do with backoff.
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = now() + interval '1 hour'").update();
        relay.drain();

        assertThat(publisher.published).isEmpty();

        jdbc.sql("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second'").update();
        relay.drain();
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    @DisplayName("A second relay worker claims a disjoint batch without waiting for the first")
    void concurrentWorkersClaimDisjointBatchesWithoutBlocking() throws Exception {
        for (int i = 0; i < 6; i++) {
            outboxRepository.append(1000L + i, "withdrawal.completed", "{\"i\":" + i + "}", 1);
        }

        // Disjointness on its own does not test SKIP LOCKED. Measured against
        // PostgreSQL 16: with plain FOR UPDATE the second worker still ends up
        // with a disjoint batch, because it blocks until the first commits and
        // then re-evaluates its WHERE against rows that are no longer PENDING.
        // What SKIP LOCKED changes is that it does not wait - 0.14s against
        // 2.47s in that comparison. So the wait is the assertion that matters,
        // and both workers go through the production claim query.
        var firstWorkerHolds = new CountDownLatch(1);
        var batches = new CopyOnWriteArrayList<List<Long>>();
        var txTemplate = new TransactionTemplate(txManager);

        Runnable claim = () -> batches.add(
                outboxRepository.claimBatch(3).stream().map(OutboxRecord::id).toList());

        Thread first = Thread.ofVirtual().start(() -> txTemplate.executeWithoutResult(tx -> {
            claim.run();
            try {
                firstWorkerHolds.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        Thread.sleep(300);

        long startedAt = System.nanoTime();
        Thread second = Thread.ofVirtual().start(() -> txTemplate.executeWithoutResult(tx -> claim.run()));
        second.join();
        long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        firstWorkerHolds.countDown();
        first.join();

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(3);
        assertThat(batches.get(1)).hasSize(3);
        assertThat(batches.get(0)).doesNotContainAnyElementsOf(batches.get(1));
        assertThat(waitedMillis)
                .as("second worker should not block on the first worker's rows")
                .isLessThan(1000L);
    }

}

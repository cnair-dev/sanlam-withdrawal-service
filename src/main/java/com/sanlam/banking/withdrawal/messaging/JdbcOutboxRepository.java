package com.sanlam.banking.withdrawal.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcClient jdbc;

    @Override
    public void append(long aggregateId, String eventType, String payload, int eventVersion) {
        jdbc.sql("""
                INSERT INTO outbox_event (aggregate_id, event_type, payload, event_version)
                VALUES (:aggregateId, :eventType, CAST(:payload AS jsonb), :eventVersion)
                """)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payload)
                .param("eventVersion", eventVersion)
                .update();
    }

    @Override
    public List<OutboxRecord> claimBatch(int batchSize) {
        return jdbc.sql("""
                SELECT id, aggregate_id, event_type, CAST(payload AS text) AS payload, attempt_count
                  FROM outbox_event
                 WHERE status = 'PENDING'
                   AND next_attempt_at <= now()
                 ORDER BY id
                   FOR UPDATE SKIP LOCKED
                 LIMIT :batchSize
                """)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new OutboxRecord(
                        rs.getLong("id"),
                        rs.getLong("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempt_count")))
                .list();
    }

    @Override
    public void markPublished(long id) {
        jdbc.sql("""
                UPDATE outbox_event
                   SET status = 'PUBLISHED', published_at = now(), last_error = NULL
                 WHERE id = :id
                """).param("id", id).update();
    }

    @Override
    public void markTransientFailure(long id, String error, int backoffCapSeconds) {
        jdbc.sql("""
                UPDATE outbox_event
                   SET attempt_count   = attempt_count + 1,
                       last_error      = :error,
                       -- LEAST before the cast, not after. POWER returns double
                       -- precision and ::int overflows above 2^31, so casting
                       -- first made this throw once attempt_count reached 31.
                       next_attempt_at = now() + make_interval(
                             secs => LEAST(POWER(2, attempt_count), :cap)::int)
                 WHERE id = :id
                """)
                .param("error", truncate(error))
                .param("cap", backoffCapSeconds)
                .param("id", id)
                .update();
    }

    @Override
    public void markPermanentFailure(long id, String error) {
        jdbc.sql("""
                UPDATE outbox_event
                   SET attempt_count = attempt_count + 1,
                       last_error    = :error,
                       status        = 'FAILED'
                 WHERE id = :id
                """)
                .param("error", truncate(error))
                .param("id", id)
                .update();
    }

    @Override
    public int requeueFailed() {
        return jdbc.sql("""
                UPDATE outbox_event
                   SET status = 'PENDING', attempt_count = 0, next_attempt_at = now()
                 WHERE status = 'FAILED'
                """).update();
    }

    @Override
    public boolean requeueFailed(long id) {
        return jdbc.sql("""
                UPDATE outbox_event
                   SET status = 'PENDING', attempt_count = 0, next_attempt_at = now()
                 WHERE id = :id AND status = 'FAILED'
                """).param("id", id).update() == 1;
    }

    private static String truncate(String error) {
        return error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }

    @Override
    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")
                .query(Long.class).single();
    }

    @Override
    public long oldestPendingAgeSeconds() {
        return jdbc.sql("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at)))::bigint, 0)
                  FROM outbox_event WHERE status = 'PENDING'
                """).query(Long.class).single();
    }

    @Override
    public long countFailed() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE status = 'FAILED'")
                .query(Long.class).single();
    }

    @Override
    public int purgePublishedOlderThanDays(int days) {
        return jdbc.sql("""
                DELETE FROM outbox_event
                 WHERE status = 'PUBLISHED'
                   AND published_at < now() - make_interval(days => :days)
                """).param("days", days).update();
    }
}

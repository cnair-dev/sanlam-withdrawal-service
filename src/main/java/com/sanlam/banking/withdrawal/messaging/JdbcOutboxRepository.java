package com.sanlam.banking.withdrawal.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcOutboxRepository
        implements OutboxAppender, OutboxRelayStore, OutboxOperations {

    private final JdbcClient jdbc;

    @Override
    public void append(long aggregateId, String eventType, String payload, int eventVersion,
                       String correlationId) {
        jdbc.sql("""
                INSERT INTO outbox_event (aggregate_id, event_type, payload, event_version, correlation_id)
                VALUES (:aggregateId, :eventType, CAST(:payload AS jsonb), :eventVersion, :correlationId)
                """)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payload)
                .param("eventVersion", eventVersion)
                .param("correlationId", correlationId)
                .update();
    }

    @Override
    public List<OutboxRecord> claimBatch(int batchSize) {
        return jdbc.sql("""
                SELECT id, aggregate_id, event_type, CAST(payload AS text) AS payload,
                       attempt_count, correlation_id
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
                        rs.getInt("attempt_count"),
                        rs.getString("correlation_id")))
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
                       -- The exponent is capped, not just the result. LEAST evaluates
                       -- both arguments, so POWER(2, attempt_count) is computed before
                       -- the cap can clamp it: casting first threw at 2^31, and moving
                       -- LEAST before the cast only moved the wall to 1024, where POWER
                       -- overflows double precision itself. Transient failures never
                       -- terminate, so at a 300s cap 1024 attempts is about three and a
                       -- half days of sustained outage - which is the exact scenario the
                       -- no-attempt-limit design exists for. It would have thrown inside
                       -- handleFailure, aborted the drain, and wedged the relay for good.
                       next_attempt_at = now() + make_interval(
                             secs => LEAST(POWER(2, LEAST(attempt_count, 30)), :cap)::int)
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

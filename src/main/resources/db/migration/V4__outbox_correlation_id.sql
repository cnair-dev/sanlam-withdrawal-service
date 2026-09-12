-- Carry the correlation id on the outbox row, not only inside the payload.
--
-- The request path puts the correlation id in the MDC, so every log line from a
-- withdrawal carries it. The relay runs on the scheduler thread and inherits
-- none of that, so every publish-side log line - including the failure ones an
-- engineer reads during an incident - had no correlation id on it. The trail
-- stopped at the commit: you could see the withdrawal, and you could see that
-- some event failed to publish, and nothing tied the two together.
--
-- It is in the payload, but the payload is JSONB the relay treats as an opaque
-- string, and reaching into it to log would mean parsing every event on the hot
-- path to recover something that is cheap to carry as a column.
--
-- Indexed because its use is operational - "show me everything that happened to
-- this request" - which is a lookup, not a scan.
ALTER TABLE outbox_event ADD COLUMN correlation_id TEXT NULL;
CREATE INDEX idx_outbox_correlation ON outbox_event(correlation_id);

-- countFailed() is polled on every Prometheus scrape and every health check.
-- V1 gave partial indexes to PENDING and PUBLISHED and left FAILED to scan the
-- whole table, which grows to seven days of published rows between purges.
CREATE INDEX idx_outbox_failed ON outbox_event(id) WHERE status = 'FAILED';

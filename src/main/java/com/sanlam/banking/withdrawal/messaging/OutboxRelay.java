package com.sanlam.banking.withdrawal.messaging;

import com.sanlam.banking.withdrawal.config.OutboxProperties;
import com.sanlam.banking.withdrawal.observability.WithdrawalMetrics;
import lombok.RequiredArgsConstructor;
import com.sanlam.banking.withdrawal.config.CorrelationIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polling relay for the transactional outbox.
 *
 * Why an outbox at all: the original code committed the balance change and then
 * published to SNS as a second, independent write. If the process dies between
 * them the money moved and no event exists; if the publish succeeds and the
 * transaction later rolls back, an event exists for money that never moved.
 * Writing the event as a row in the SAME transaction removes that window by
 * construction, and this relay turns the row into a real message afterwards.
 * Delivery is therefore at-least-once with a small delay, and consumers must be
 * idempotent - which they must be under any realistic messaging system anyway.
 *
 * On holding the row lock across the SNS call: the general rule is never to hold
 * a CONTENDED lock across network I/O. Outbox rows are contended only by other
 * relay workers, which SKIP LOCKED past them, so the blast radius is a held
 * connection and a longer transaction. That is bounded at batch-size multiplied
 * by the SNS client's apiCallTimeout - currently 20 x 10s - and both halves of
 * that bound are set explicitly in configuration, because the SDK does not
 * impose an API call timeout of its own. On the withdrawal path, where rows are
 * contended by live customers, no network call happens inside the transaction.
 *
 * Note this is one transaction for the whole batch, so the window between SNS
 * accepting a publish and the row being marked published spans the rest of the
 * batch, not one row. It cannot be closed without a distributed transaction
 * between PostgreSQL and SNS, which is the dual-write problem again one step
 * further downstream. At-least-once plus consumer idempotency is the answer,
 * and the event id is the transaction id minted inside the withdrawal, so it is
 * stable across every republish.
 *
 * Failures are handled by what they are rather than by how often they have
 * happened. A message the transport rejects on its own merits is dead-lettered
 * immediately - retrying it ten times only delays everything behind it. A
 * transport failure backs the event off and leaves it PENDING with no attempt
 * limit, because an event that cannot currently be delivered is an operational
 * problem to raise, not one to discard. An earlier version counted attempts
 * instead, which meant an SNS outage lasting longer than about eight and a half
 * minutes quietly dead-lettered every pending event.
 *
 * A circuit breaker was considered and deliberately left out. Backoff already
 * suppresses doomed calls during an outage, and the classification above is a
 * better answer to poison messages than a breaker, which does nothing for them.
 * See docs/decisions/0001.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRelayStore outboxRelayStore;
    private final EventPublisher eventPublisher;
    private final OutboxProperties properties;
    private final WithdrawalMetrics metrics;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    @Transactional
    public void drain() {
        List<OutboxRecord> batch = outboxRelayStore.claimBatch(properties.batchSize());
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox relay claimed {} event(s)", batch.size());

        for (OutboxRecord record : batch) {
            // The relay runs on the scheduler thread and inherits nothing from
            // the request that produced the event, so without this every
            // publish-side log line - including the ones read during an incident
            // - is unattributable. Restored per record because one batch spans
            // many unrelated requests.
            if (record.correlationId() != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, record.correlationId());
            }
            try {
                eventPublisher.publish(record.eventType(), record.payload(),
                        "account-" + record.aggregateId());
                outboxRelayStore.markPublished(record.id());
                metrics.recordPublish(true);
            } catch (EventPublishException e) {
                // Caught per record: one bad message must not stall the batch.
                // Both branches are ordinary UPDATEs, so the surrounding
                // transaction stays usable.
                handleFailure(record, e);
            } catch (RuntimeException e) {
                // An unclassified failure - a bug in the publisher, a
                // serialisation fault - is treated as transient. Holding the
                // event and raising the backlog is recoverable; discarding it
                // is not.
                handleFailure(record, new EventPublishException(
                        EventPublishException.Kind.TRANSIENT, e.getMessage(), e));
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    private void handleFailure(OutboxRecord record, EventPublishException e) {
        metrics.recordPublish(false);

        if (e.isPermanent()) {
            outboxRelayStore.markPermanentFailure(record.id(), e.getMessage());
            log.error("Outbox event {} was rejected by the transport and moved to FAILED. "
                            + "It will not be retried until requeued: {}",
                    record.id(), e.getMessage());
            return;
        }

        outboxRelayStore.markTransientFailure(record.id(), e.getMessage(),
                properties.backoffCapSeconds());
        log.warn("Outbox event {} publish failed (attempt {}), backing off and staying PENDING: {}",
                record.id(), record.attemptCount() + 1, e.getMessage());
    }
}

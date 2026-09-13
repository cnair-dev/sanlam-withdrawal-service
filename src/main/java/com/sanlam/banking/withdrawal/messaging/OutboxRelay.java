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
 * <p>Why an outbox: the original code committed the balance change and then published to
 * SNS as a second, independent write. Die between the two and the money moved with no
 * event; publish and then roll back and an event exists for money that never moved.
 * Writing the event as a row in the SAME transaction removes that window by construction.
 * Delivery becomes at-least-once with a small delay, so consumers must be idempotent -
 * which they must be under any realistic broker anyway.
 *
 * <p>The SNS call happens inside the claiming transaction, bending the rule never to hold
 * a CONTENDED lock across network I/O. These rows are contended only by other relay
 * workers, which SKIP LOCKED past them, so the cost is a held connection and a long
 * transaction, bounded at batch-size x the SNS apiCallTimeout (20 x 10s, both set
 * explicitly). Nothing network-bound runs inside the withdrawal transaction, where rows
 * are contended by live customers.
 *
 * <p>One transaction covers the whole batch, so the window between SNS accepting a publish
 * and the row being marked published spans the rest of the batch rather than one row.
 * Closing it needs a distributed transaction between PostgreSQL and SNS - the dual-write
 * problem again, one step downstream. The event id is the transaction id minted inside the
 * withdrawal, so it is stable across republishes.
 *
 * <p>Failures are handled by what they are rather than by how often they have happened; an
 * earlier version counted attempts, so an SNS outage over about eight and a half minutes
 * quietly dead-lettered everything pending. A circuit breaker was considered and left out -
 * backoff already suppresses doomed calls, and classification is the better answer to
 * poison messages. See docs/decisions/0001.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository outboxRepository;
    private final EventPublisher eventPublisher;
    private final OutboxProperties properties;
    private final WithdrawalMetrics metrics;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    @Transactional
    public void drain() {
        List<OutboxRecord> batch = outboxRepository.claimBatch(properties.batchSize());
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox relay claimed {} event(s)", batch.size());

        for (OutboxRecord record : batch) {
            // The scheduler thread inherits nothing from the request that produced the
            // event, so without this every publish-side log line is unattributable.
            // Restored per record because one batch spans many unrelated requests.
            if (record.correlationId() != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, record.correlationId());
            }
            try {
                eventPublisher.publish(record.eventType(), record.payload(),
                        "account-" + record.aggregateId());
                outboxRepository.markPublished(record.id());
                metrics.recordPublish(true);
            } catch (EventPublishException e) {
                // Per record: one bad message must not stall the batch. Both branches of
                // handleFailure are ordinary UPDATEs, so the transaction stays usable.
                handleFailure(record, e);
            } catch (RuntimeException e) {
                // Unclassified - a publisher bug, a serialisation fault. Treated as
                // transient: holding the event is recoverable, discarding it is not.
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
            outboxRepository.markPermanentFailure(record.id(), e.getMessage());
            log.error("Outbox event {} was rejected by the transport and moved to FAILED. "
                            + "It will not be retried until requeued: {}",
                    record.id(), e.getMessage());
            return;
        }

        outboxRepository.markTransientFailure(record.id(), e.getMessage(),
                properties.backoffCapSeconds());
        log.warn("Outbox event {} publish failed (attempt {}), backing off and staying PENDING: {}",
                record.id(), record.attemptCount() + 1, e.getMessage());
    }
}

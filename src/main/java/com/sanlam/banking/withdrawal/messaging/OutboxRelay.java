package com.sanlam.banking.withdrawal.messaging;

import com.sanlam.banking.withdrawal.config.OutboxProperties;
import com.sanlam.banking.withdrawal.observability.WithdrawalMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * connection and a longer transaction - both bounded here by a small batch and
 * the SDK's request timeout. On the withdrawal path, where rows are contended by
 * live customers, no network call happens inside the transaction at all.
 *
 * A circuit breaker was considered and deliberately left out: during an SNS
 * outage the per-row exponential backoff below already suppresses doomed calls,
 * and unlike a breaker it also handles the poison-message case. A breaker would
 * have added a dependency and a state machine without changing what happens to
 * the rows.
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
            try {
                eventPublisher.publish(record.eventType(), record.payload(),
                        "account-" + record.aggregateId());
                outboxRepository.markPublished(record.id());
                metrics.recordPublish(true);
            } catch (Exception e) {
                // Deliberately caught per-record: one poison message must not
                // stall the rest of the batch. markFailed is an ordinary UPDATE,
                // so the surrounding transaction stays usable.
                int nextAttempt = record.attemptCount() + 1;
                outboxRepository.markFailed(record.id(), e.getMessage(),
                        properties.maxAttempts(), properties.backoffCapSeconds());
                metrics.recordPublish(false);

                if (nextAttempt >= properties.maxAttempts()) {
                    log.error("Outbox event {} exhausted {} attempts and was moved to FAILED (dead letter): {}",
                            record.id(), properties.maxAttempts(), e.getMessage());
                } else {
                    log.warn("Outbox event {} publish failed (attempt {}/{}), backing off: {}",
                            record.id(), nextAttempt, properties.maxAttempts(), e.getMessage());
                }
            }
        }
    }
}

package com.sanlam.banking.withdrawal.job;

import com.sanlam.banking.withdrawal.config.OutboxProperties;
import com.sanlam.banking.withdrawal.messaging.OutboxOperations;
import com.sanlam.banking.withdrawal.persistence.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention.
 *
 * Both of these tables grow without bound otherwise, which is a cost problem and
 * a data-governance problem: under POPIA, personal and transactional data should
 * be kept only as long as it serves the purpose it was collected for.
 *
 * Published outbox rows have done their job once delivered and are purged after
 * a short window kept only for operational forensics. Idempotency keys expire on
 * their own TTL. The ledger is explicitly NOT purged here - financial records
 * carry a statutory retention obligation (FICA: seven years) and are archived
 * rather than deleted.
 *
 * At higher volume the right mechanism is monthly partitions and DROP PARTITION
 * instead of DELETE, which avoids the vacuum load entirely.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RetentionJob {

    private final OutboxOperations outboxOperations;
    private final IdempotencyRepository idempotencyRepository;
    private final OutboxProperties outboxProperties;

    @Scheduled(cron = "${app.retention.cron:0 0 3 * * *}")
    @Transactional
    public void purge() {
        int outboxPurged = outboxOperations
                .purgePublishedOlderThanDays(outboxProperties.purgePublishedAfterDays());
        int keysPurged = idempotencyRepository.purgeExpired();
        log.info("Retention purge complete: outboxRows={} idempotencyKeys={}", outboxPurged, keysPurged);
    }
}

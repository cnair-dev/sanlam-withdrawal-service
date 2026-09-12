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
 * Retention. Both of these tables grow without bound otherwise - a cost problem and a
 * data-governance one: under POPIA, personal and transactional data is kept only as long
 * as it serves the purpose it was collected for.
 *
 * <p>Published outbox rows are purged after a short forensics window; idempotency keys
 * expire on their own TTL. The ledger is explicitly NOT purged - financial records carry a
 * statutory retention obligation (FICA: seven years) and are archived, not deleted.
 *
 * <p>At higher volume the right mechanism is monthly partitions and DROP PARTITION rather
 * than DELETE, which avoids the vacuum load entirely.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RetentionJob {

    private final OutboxOperations outboxOperations;
    private final IdempotencyRepository idempotencyRepository;
    private final OutboxProperties outboxProperties;

    // Zoned explicitly. A container runs UTC, so an unzoned "3am" is 5am in the market
    // this serves - the middle of the morning ramp rather than the quiet window it was
    // meant to be. Timestamps are TIMESTAMPTZ and unaffected; only the trigger moves.
    @Scheduled(cron = "${app.retention.cron:0 0 3 * * *}",
               zone = "${app.schedule.zone:Africa/Johannesburg}")
    @Transactional
    public void purge() {
        int outboxPurged = outboxOperations
                .purgePublishedOlderThanDays(outboxProperties.purgePublishedAfterDays());
        int keysPurged = idempotencyRepository.purgeExpired();
        log.info("Retention purge complete: outboxRows={} idempotencyKeys={}", outboxPurged, keysPurged);
    }
}

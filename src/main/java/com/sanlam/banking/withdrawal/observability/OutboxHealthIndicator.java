package com.sanlam.banking.withdrawal.observability;

import com.sanlam.banking.withdrawal.config.OutboxProperties;
import com.sanlam.banking.withdrawal.messaging.OutboxOperations;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the outbox backlog. Deliberately NOT in the liveness or readiness groups (see
 * application.yml): a backlog means the broker is unhappy, not that this instance is
 * broken. In liveness it would make the orchestrator restart every pod during exactly the
 * incident it exists to surface, which makes the backlog worse.
 */
@Component("outbox")
@RequiredArgsConstructor
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxOperations outboxOperations;
    private final OutboxProperties properties;

    @Override
    public Health health() {
        long pending = outboxOperations.countPending();
        long failed  = outboxOperations.countFailed();
        long oldest  = outboxOperations.oldestPendingAgeSeconds();

        Health.Builder builder = (failed > 0 || pending > properties.backlogWarnThreshold())
                ? Health.status("DEGRADED")
                : Health.up();

        return builder
                .withDetail("pending", pending)
                .withDetail("failed", failed)
                .withDetail("oldestPendingAgeSeconds", oldest)
                .withDetail("backlogWarnThreshold", properties.backlogWarnThreshold())
                .build();
    }
}

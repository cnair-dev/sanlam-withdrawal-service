package com.sanlam.banking.withdrawal.observability;

import com.sanlam.banking.withdrawal.messaging.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Publishes the outbox backlog on the health endpoint, for an operator who has a
 * management port and not a Prometheus query to hand.
 *
 * <p>Reports UP whatever the numbers say, and is excluded from the liveness and readiness
 * groups (see application.yml). A backlog means the broker is unhappy, not that this
 * instance is broken: wired into liveness it would have the orchestrator restart every pod
 * during exactly the incident it exists to surface, which makes the backlog worse. There
 * is no threshold here either - the same three figures are published as gauges, and a
 * threshold belongs in the alert rule rather than duplicated in the application, where the
 * two drift apart.
 */
@Component("outbox")
@RequiredArgsConstructor
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxRepository outboxRepository;

    @Override
    public Health health() {
        return Health.up()
                .withDetail("pending", outboxRepository.countPending())
                .withDetail("failed", outboxRepository.countFailed())
                .withDetail("oldestPendingAgeSeconds", outboxRepository.oldestPendingAgeSeconds())
                .build();
    }
}

package com.sanlam.banking.withdrawal.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.sanlam.banking.withdrawal.messaging.OutboxRepository;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalMetrics {

    private final MeterRegistry registry;
    private final Timer duration;

    public WithdrawalMetrics(MeterRegistry registry, OutboxRepository outboxRepository) {
        this.registry = registry;
        this.duration = Timer.builder("withdrawal.duration")
                .description("End-to-end withdrawal processing time")
                .publishPercentileHistogram()
                .register(registry);

        // Backlog DEPTH tells you how much is queued; backlog AGE tells you whether
        // the relay is actually keeping up. Age is the SLO worth alerting on - a
        // steady depth with a rising age means the relay has stalled.
        registry.gauge("outbox.pending.count", outboxRepository, r -> (double) r.countPending());
        registry.gauge("outbox.pending.oldest.age.seconds", outboxRepository,
                r -> (double) r.oldestPendingAgeSeconds());
        registry.gauge("outbox.failed.count", outboxRepository, r -> (double) r.countFailed());
    }

    public void recordAttempt(String outcome) {
        Counter.builder("withdrawal.attempts")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordIdempotentReplay() {
        registry.counter("idempotency.replay.count").increment();
    }

    public void recordPublish(boolean success) {
        registry.counter("outbox.publish." + (success ? "success" : "failure")).increment();
    }

    public Timer duration() { return duration; }
}

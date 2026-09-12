package com.sanlam.banking.withdrawal.observability;

import com.sanlam.banking.withdrawal.messaging.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Operator control for the dead-letter queue.
 *
 * <p>Dead-lettering is only half a design. Without a way back, recovering an
 * event that was rejected for a reason since fixed means someone running UPDATE
 * by hand against a financial system, usually at the worst possible hour - and
 * an ad-hoc UPDATE against the outbox is exactly the class of operation that
 * should not be routine.
 *
 * <p>This lives on the actuator surface rather than the public API on purpose.
 * Actuator is conventionally bound to a separate management port and secured at
 * the platform edge, which is where an operational control belongs; the
 * withdrawal API is for callers moving money, not for operators repairing
 * delivery. Security is out of scope for this exercise, so nothing here is
 * authenticated - in a real deployment this endpoint is management-port-only
 * and role-restricted, and that is a deployment concern rather than a code one.
 */
@Component
@Endpoint(id = "outbox")
@RequiredArgsConstructor
@Slf4j
public class OutboxOperationsEndpoint {

    private final OutboxRepository outboxRepository;

    @ReadOperation
    public Map<String, Object> status() {
        return Map.of(
                "pending", outboxRepository.countPending(),
                "failed", outboxRepository.countFailed(),
                "oldestPendingAgeSeconds", outboxRepository.oldestPendingAgeSeconds());
    }

    /** Requeue every dead-lettered event, for recovery after a fixed defect. */
    @WriteOperation
    @Transactional
    public Map<String, Object> requeueAll() {
        int requeued = outboxRepository.requeueFailed();
        log.warn("Operator requeued {} dead-lettered outbox event(s)", requeued);
        return Map.of("requeued", requeued);
    }

    /** Requeue one event, when only a known message needs replaying. */
    @WriteOperation
    @Transactional
    public Map<String, Object> requeueOne(@Selector long id) {
        boolean requeued = outboxRepository.requeueFailed(id);
        log.warn("Operator requeue of outbox event {}: {}", id, requeued ? "requeued" : "not in FAILED");
        return Map.of("id", id, "requeued", requeued);
    }
}

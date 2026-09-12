package com.sanlam.banking.withdrawal.observability;

import com.sanlam.banking.withdrawal.messaging.OutboxOperations;
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
 * <p>Dead-lettering is only half a design. Without a way back, recovering an event
 * rejected for a reason since fixed means someone hand-writing UPDATE against a financial
 * system at the worst possible hour.
 *
 * <p>On the actuator surface rather than the public API deliberately: actuator is bound to
 * a separate management port and secured at the platform edge, which is where an
 * operational control belongs. Security is out of scope here, so nothing is authenticated
 * - in a real deployment this is management-port-only and role-restricted.
 */
@Component
@Endpoint(id = "outbox")
@RequiredArgsConstructor
@Slf4j
public class OutboxOperationsEndpoint {

    private final OutboxOperations outboxOperations;

    @ReadOperation
    public Map<String, Object> status() {
        return Map.of(
                "pending", outboxOperations.countPending(),
                "failed", outboxOperations.countFailed(),
                "oldestPendingAgeSeconds", outboxOperations.oldestPendingAgeSeconds());
    }

    /** Requeue every dead-lettered event, for recovery after a fixed defect. */
    @WriteOperation
    @Transactional
    public Map<String, Object> requeueAll() {
        int requeued = outboxOperations.requeueFailed();
        log.warn("Operator requeued {} dead-lettered outbox event(s)", requeued);
        return Map.of("requeued", requeued);
    }

    /** Requeue one event, when only a known message needs replaying. */
    @WriteOperation
    @Transactional
    public Map<String, Object> requeueOne(@Selector long id) {
        boolean requeued = outboxOperations.requeueFailed(id);
        log.warn("Operator requeue of outbox event {}: {}", id, requeued ? "requeued" : "not in FAILED");
        return Map.of("id", id, "requeued", requeued);
    }
}

package com.sanlam.banking.withdrawal.messaging;

/**
 * Publishing seam. Justified by two needs that exist today, not by a hypothetical
 * broker migration: it lets the relay be tested without AWS, and it keeps AWS SDK
 * types out of the relay and the service layer.
 */
public interface EventPublisher {
    void publish(String eventType, String payload, String subject);
}

package com.sanlam.banking.withdrawal.messaging;

/**
 * Publishing seam.
 *
 * Justified by two needs that exist today, not by a hypothetical future
 * migration: it lets the relay be tested without AWS, and it keeps AWS SDK
 * types from leaking into the relay and the service layer. That a different
 * broker could be substituted later is a consequence, not the reason.
 */
public interface EventPublisher {
    void publish(String eventType, String payload, String subject);
}

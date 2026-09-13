package com.sanlam.banking.withdrawal.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds are constraints, not documentation. A batch size of 0 makes the relay claim
 * nothing forever, and a negative backoff cap puts next_attempt_at in the past and turns
 * the poll into a hot loop. Both fail silently, which is the worst way for a delivery
 * component to be misconfigured.
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @Min(1) int batchSize,
        @Min(1) int backoffCapSeconds,
        @Min(1) int purgePublishedAfterDays
) {}

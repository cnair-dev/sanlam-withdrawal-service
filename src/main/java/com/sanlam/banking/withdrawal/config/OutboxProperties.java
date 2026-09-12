package com.sanlam.banking.withdrawal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        int batchSize,
        int backoffCapSeconds,
        int purgePublishedAfterDays
) {}

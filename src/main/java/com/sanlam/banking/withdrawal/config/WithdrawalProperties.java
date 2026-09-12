package com.sanlam.banking.withdrawal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.withdrawal")
public record WithdrawalProperties(
        long settlementAccountId,
        String defaultCurrency,
        int idempotencyTtlHours
) {}

package com.sanlam.banking.withdrawal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.withdrawal")
public record WithdrawalProperties(
        long settlementAccountId,
        String defaultCurrency,

        /**
         * How long a used idempotency key is remembered. This is a CORRECTNESS setting,
         * not a housekeeping one: past the TTL the same key is a fresh key, so a retry
         * arriving later performs a SECOND withdrawal. It has to exceed the longest window
         * in which a caller might legitimately retry - seconds for an app, but hours or
         * days for a batch reprocessed after an outage.
         *
         * <p>Shortening it to reclaim storage reintroduces double-debits silently, with
         * nothing failing to say so. Size the retention against caller behaviour, and if
         * the table is the problem, partition it.
         */
        int idempotencyTtlHours
) {}

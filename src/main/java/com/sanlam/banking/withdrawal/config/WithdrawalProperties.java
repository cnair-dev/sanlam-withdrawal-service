package com.sanlam.banking.withdrawal.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.withdrawal")
public record WithdrawalProperties(
        @Min(1) long settlementAccountId,
        @NotBlank String defaultCurrency,

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
         *
         * <p>@Min(1) because 0 sets expires_at to now(), so every subsequent claim finds
         * an expired key and re-claims it. Idempotency would be off with nothing failing
         * to say so - exactly the silent double-debit described above, reachable by a
         * single character in a config file.
         */
        @Min(1) int idempotencyTtlHours
) {}

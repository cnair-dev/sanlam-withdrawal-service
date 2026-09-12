package com.sanlam.banking.withdrawal.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Withdrawal event envelope, using the CloudEvents 1.0 core attributes (specversion / id /
 * source / type / subject / time) rather than an ad-hoc shape, so routing infrastructure
 * can read it without bespoke knowledge of this service. {@code eventversion} is an
 * extension attribute - lowercase because the spec restricts extension names to lowercase
 * alphanumerics, so the spelling is not cosmetic.
 *
 * <p>The consumers this exists for: AML / FICA cash-threshold monitoring, fraud scoring,
 * customer notification, statement generation.
 *
 * <p>Data governance: no customer name, national identity number or full account number
 * crosses the boundary - a surrogate account id, the amount and the resulting balance are
 * what the notification and statement consumers actually need (POPIA minimisation is about
 * necessity, not about carrying as little as possible).
 *
 * <p>Versioning is additive-only: fields may be added, existing ones never change meaning
 * or disappear. A schema registry is the right answer at higher event-type and team count,
 * not for one event type and one producer.
 */
public record WithdrawalEvent(
        @JsonProperty("specversion") String specVersion,
        String id,
        String source,
        String type,
        String subject,
        Instant time,
        @JsonProperty("eventversion") int eventVersion,
        WithdrawalEventData data
) {
    public static final String SPEC_VERSION = "1.0";
    public static final String SOURCE       = "/za/co/sanlam/banking/withdrawal-service";
    public static final String TYPE         = "za.co.sanlam.banking.withdrawal.completed";
    public static final int    VERSION      = 1;

    public static WithdrawalEvent completed(UUID transactionId, long accountId, BigDecimal amount,
                                            BigDecimal resultingBalance, String currency,
                                            String correlationId, Instant occurredAt) {
        return new WithdrawalEvent(
                SPEC_VERSION,
                transactionId.toString(),
                SOURCE,
                TYPE,
                "account/" + accountId,
                occurredAt,
                VERSION,
                new WithdrawalEventData(transactionId, accountId, amount, resultingBalance,
                        currency, "SUCCESSFUL", correlationId));
    }

    public record WithdrawalEventData(
            UUID transactionId,
            Long accountId,
            BigDecimal amount,
            BigDecimal resultingBalance,
            String currency,
            String status,
            String correlationId
    ) {}
}

package com.sanlam.banking.withdrawal.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Withdrawal event envelope, shaped after the CNCF CloudEvents 1.0 core
 * attributes (specversion / id / source / type / subject / time) rather than an
 * ad-hoc structure, so downstream consumers and routing infrastructure can
 * interpret it without bespoke knowledge of this service.
 *
 * Consumers this exists for: AML / FICA cash-threshold monitoring, fraud
 * scoring, customer notification, and statement generation. That is the reason
 * a withdrawal publishes an event at all.
 *
 * Data governance: the payload deliberately carries only a surrogate account
 * id and the amount. No customer name, national identity number or full
 * account number crosses the service boundary (POPIA data minimisation).
 *
 * Versioning is additive-only: new fields may be added, existing fields never
 * change meaning or disappear. eventVersion lets a consumer branch if it must.
 * A schema registry would be the right answer at higher event-type and team
 * count; it is not warranted for one event type and one producer.
 */
public record WithdrawalEvent(
        @JsonProperty("specversion") String specVersion,
        String id,
        String source,
        String type,
        String subject,
        Instant time,
        @JsonProperty("eventVersion") int eventVersion,
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

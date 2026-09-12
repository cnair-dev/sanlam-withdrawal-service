package com.sanlam.banking.withdrawal.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Withdrawal request body.
 *
 * <p>The amount constraints are the only thing standing between a sub-cent
 * request and a phantom success. Balance is NUMERIC(19,2) and PostgreSQL rounds
 * on assignment, so withdrawing 0.005 from 100.00 reports one row updated and
 * leaves the balance at 100.00 - a success response, a ledger pair and a
 * published event for money that never moved. The obvious database-side guard
 * does not work: a CHECK on scale() cannot fire, because the column coerces the
 * value to scale 2 before the constraint is evaluated.
 *
 * <p>The compact constructor normalises before validation runs, so 100.000 and
 * 100.00 are the same request. Without it @Digits rejects any client that sends
 * trailing zeros, which is a wire-format detail callers should not have to know.
 */
public record WithdrawalRequest(
        @NotNull(message = "accountId is required")
        Long accountId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount
) {
    public WithdrawalRequest {
        if (amount != null) {
            amount = amount.stripTrailingZeros();
        }
    }
}

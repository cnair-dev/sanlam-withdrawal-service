package com.sanlam.banking.withdrawal.api.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * @param amount  Money amount. @Digits(fraction = 2) is NOT cosmetic:
 *                balance is NUMERIC(19,2), and PostgreSQL rounds on assignment.
 *                Verified: withdrawing 0.004 from 100.00 returns "1 row updated"
 *                (success) while leaving the balance at 100.00 - a success
 *                response, a ledger entry and a published event for money that
 *                never moved. Rejecting sub-cent scale at the edge is what
 *                prevents that.
 */
public record WithdrawalRequest(
        @NotNull(message = "accountId is required")
        Long accountId,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount
) {}

package com.sanlam.banking.withdrawal.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Withdrawal request body.
 *
 * <p>The amount constraints are the only thing standing between a sub-cent request and a
 * phantom success. Balance is NUMERIC(19,2) and PostgreSQL rounds on assignment, so
 * withdrawing 0.005 from 100.00 reports one row updated and leaves the balance at 100.00 -
 * a success response, a ledger pair and a published event for money that never moved. The
 * obvious database-side guard does not work: a CHECK on scale() cannot fire, because the
 * column coerces the value to scale 2 before the constraint is evaluated.
 *
 * <p>The compact constructor normalises to the minor unit before validation runs, so a
 * client may send 10, 10.00 or 10.000 and the service works in 10.00 throughout. Scale is
 * not cosmetic here: BigDecimal carries it, and 10.00 stripped of trailing zeros is 1E+1,
 * which is what Jackson would then put in the response body and the SNS payload for a ten
 * rand withdrawal. Two decimal places is the ZAR minor unit - the same single-currency
 * assumption the debit predicate enforces, and it would have to become per-currency
 * alongside it (JPY has no minor unit, KWD has three).
 */
public record WithdrawalRequest(
        @NotNull(message = "accountId is required")
        Long accountId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount
) {
    /** ZAR. See the class comment before changing it. */
    private static final int MINOR_UNIT_SCALE = 2;

    public WithdrawalRequest {
        if (amount != null) {
            amount = amount.stripTrailingZeros();
            if (amount.scale() < MINOR_UNIT_SCALE) {
                // UNNECESSARY, not HALF_UP: this branch only widens the scale, so it can
                // never round. If that ever stops being true it should throw, not quietly
                // decide what a customer's money rounds to.
                amount = amount.setScale(MINOR_UNIT_SCALE, RoundingMode.UNNECESSARY);
            }
        }
    }
}

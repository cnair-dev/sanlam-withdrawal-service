package com.sanlam.banking.withdrawal.domain.exception;

import java.math.BigDecimal;

public final class InsufficientFundsException extends WithdrawalException {
    private final long accountId;
    private final BigDecimal requested;

    public InsufficientFundsException(long accountId, BigDecimal requested) {
        super("Insufficient available funds on account %d for amount %s".formatted(accountId, requested));
        this.accountId = accountId;
        this.requested = requested;
    }

    public long getAccountId()       { return accountId; }
    public BigDecimal getRequested() { return requested; }
}

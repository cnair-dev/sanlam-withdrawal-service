package com.sanlam.banking.withdrawal.domain.exception;

public final class AccountNotFoundException extends WithdrawalException {
    private final long accountId;

    public AccountNotFoundException(long accountId) {
        super("Account %d does not exist".formatted(accountId));
        this.accountId = accountId;
    }

    public long getAccountId() { return accountId; }
}

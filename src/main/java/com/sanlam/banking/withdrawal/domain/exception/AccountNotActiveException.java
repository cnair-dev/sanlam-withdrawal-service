package com.sanlam.banking.withdrawal.domain.exception;

public final class AccountNotActiveException extends WithdrawalException {
    private final long accountId;
    private final String status;

    public AccountNotActiveException(long accountId, String status) {
        super("Account %d is not active (status=%s)".formatted(accountId, status));
        this.accountId = accountId;
        this.status = status;
    }

    public long getAccountId() { return accountId; }
    public String getStatus()  { return status; }
}

package com.sanlam.banking.withdrawal.domain.exception;

/**
 * The account is denominated in a currency this service does not settle in. Refused rather
 * than relabelled: the ledger pair would debit a customer in one currency and credit
 * settlement in another, which does not balance in any sense the reconciliation could
 * check.
 */
public final class CurrencyMismatchException extends WithdrawalException {
    public CurrencyMismatchException(long accountId, String accountCurrency, String settlementCurrency) {
        super("Account %d is denominated in %s; this service settles in %s"
                .formatted(accountId, accountCurrency, settlementCurrency));
    }
}

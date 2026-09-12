package com.sanlam.banking.withdrawal.domain.exception;

/**
 * Sealed base for every business outcome that is not a successful withdrawal.
 *
 * <p>Sealed rather than merely abstract so the exception -> HTTP mapping in
 * ApiExceptionHandler can switch with no default branch: a new subtype becomes a compile
 * error at every mapping site instead of a silent fall-through to 500. Unchecked because
 * the caller cannot recover from these in-process, and threading `throws` through every
 * layer adds noise without safety.
 */
public abstract sealed class WithdrawalException extends RuntimeException
        permits AccountNotFoundException, AccountNotActiveException,
                InsufficientFundsException, IdempotencyConflictException,
                CurrencyMismatchException {

    protected WithdrawalException(String message) {
        super(message);
    }
}

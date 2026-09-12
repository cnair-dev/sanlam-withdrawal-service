package com.sanlam.banking.withdrawal.domain.exception;

/**
 * Sealed base for every business outcome that is not a successful withdrawal.
 *
 * Sealed (not just abstract) so that the exception -> HTTP status mapping in
 * ApiExceptionHandler can use a pattern-matching switch with NO default branch.
 * Adding a new subtype later becomes a compile error at every mapping site
 * rather than a silent fall-through to HTTP 500.
 *
 * Unchecked: these are outcomes the caller cannot meaningfully recover from
 * in-process, and forcing `throws` clauses through every layer adds noise
 * without adding safety.
 */
public abstract sealed class WithdrawalException extends RuntimeException
        permits AccountNotFoundException, AccountNotActiveException,
                InsufficientFundsException, IdempotencyConflictException {

    protected WithdrawalException(String message) {
        super(message);
    }
}

package com.sanlam.banking.withdrawal.domain.exception;

/**
 * The caller reused an Idempotency-Key for a DIFFERENT request payload.
 * Returning the original response here would silently drop the new withdrawal,
 * so this is surfaced as a client error instead.
 */
public final class IdempotencyConflictException extends WithdrawalException {
    public IdempotencyConflictException(String key) {
        super("Idempotency-Key '%s' was already used with different request parameters".formatted(key));
    }
}

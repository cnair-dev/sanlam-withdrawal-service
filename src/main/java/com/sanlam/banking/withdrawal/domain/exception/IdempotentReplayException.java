package com.sanlam.banking.withdrawal.domain.exception;

/**
 * Internal signal, never surfaced to the caller. Thrown when the idempotency-key claim
 * returns zero rows, meaning a previous request with this key already committed.
 * Throwing rather than returning is deliberate: it rolls the current transaction back so
 * nothing is half-written, after which the caller re-reads the stored response in a fresh
 * transaction.
 */
public class IdempotentReplayException extends RuntimeException {
    public IdempotentReplayException() { super(null, null, false, false); }
}

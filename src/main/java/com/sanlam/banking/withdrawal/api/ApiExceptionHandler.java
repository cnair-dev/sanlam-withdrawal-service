package com.sanlam.banking.withdrawal.api;

import com.sanlam.banking.withdrawal.config.CorrelationIdFilter;
import com.sanlam.banking.withdrawal.domain.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Replaces the original's stringly-typed replies ("Withdrawal successful",
 * "Insufficient funds for withdrawal") - all returned as HTTP 200, which no
 * client can branch on reliably.
 *
 * Responses use RFC 7807 ProblemDetail, the actual interoperability standard
 * for HTTP error payloads, rather than a bespoke error shape that every
 * consumer would have to learn.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    private static final String BASE = "https://sanlam.co.za/problems/";

    /**
     * The mapping is a pattern-matching switch over a SEALED hierarchy with no
     * default branch. That is the point: if someone adds a new WithdrawalException
     * subtype later, this switch stops compiling until they decide its status
     * code, instead of silently falling through to a 500 in production.
     */
    @ExceptionHandler(WithdrawalException.class)
    public ProblemDetail handleWithdrawal(WithdrawalException ex) {
        ProblemDetail problem = switch (ex) {
            case AccountNotFoundException e -> build(HttpStatus.NOT_FOUND,
                    "Account not found", e.getMessage(), "account-not-found");

            case AccountNotActiveException e -> build(HttpStatus.CONFLICT,
                    "Account not active", e.getMessage(), "account-not-active");

            case InsufficientFundsException e -> build(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient funds", e.getMessage(), "insufficient-funds");

            case IdempotencyConflictException e -> build(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency key conflict", e.getMessage(), "idempotency-conflict");
        };
        log.warn("Withdrawal rejected: {}", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "Invalid request", detail, "validation-failed");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        return build(HttpStatus.BAD_REQUEST, "Missing required header",
                ex.getHeaderName() + " header is required", "missing-header");
    }

    /**
     * Last resort. The message is deliberately generic: internal failure detail
     * belongs in the logs, correlated by id, not in a response body that may
     * cross a trust boundary.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled error processing withdrawal", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be completed.", "internal-error");
    }

    private ProblemDetail build(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE + type));
        problem.setProperty("timestamp", Instant.now());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}

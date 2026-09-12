package com.sanlam.banking.withdrawal.api;

import com.sanlam.banking.withdrawal.config.CorrelationIdFilter;
import com.sanlam.banking.withdrawal.domain.exception.*;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Replaces the original's stringly-typed replies ("Withdrawal successful", "Insufficient
 * funds for withdrawal"), all returned as HTTP 200, with RFC 7807 ProblemDetail.
 *
 * <p>Extending ResponseEntityExceptionHandler is load-bearing.
 * ExceptionHandlerExceptionResolver runs before DefaultHandlerExceptionResolver, so an
 * advice carrying only a catch-all @ExceptionHandler(Exception.class) intercepts every
 * Spring MVC exception before the framework can map it - malformed JSON, wrong media type,
 * wrong method and unknown path all become 500. On an endpoint that moves money that is
 * worse than untidy: 500 tells a well-behaved client the outcome was ambiguous and should
 * be retried, so a permanently malformed request becomes a retry loop. The base class
 * supplies the 4xx mappings; the catch-all below sees only what nothing else claimed.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String BASE = "https://sanlam.co.za/problems/";

    /**
     * A pattern-matching switch over a sealed hierarchy with no default branch: adding a
     * WithdrawalException subtype stops this compiling until someone chooses its status
     * code, rather than falling through to a 500.
     */
    @ExceptionHandler(WithdrawalException.class)
    public ProblemDetail handleWithdrawal(WithdrawalException ex) {
        ProblemDetail problem = switch (ex) {
            case AccountNotFoundException e -> build(HttpStatus.NOT_FOUND,
                    "Account not found", e.getMessage(), "account-not-found");

            // One status code, distinct problem types. All three are a conflict with the
            // account's state, but the caller's next step differs: dormant needs
            // reactivating, frozen needs the hold lifted, closed is terminal. The type URI
            // is where a client reads that difference without parsing prose.
            case AccountNotActiveException e -> build(HttpStatus.CONFLICT,
                    "Account not active", e.getMessage(), switch (e.getStatus()) {
                        case "FROZEN"  -> "account-frozen";
                        case "DORMANT" -> "account-dormant";
                        case "CLOSED"  -> "account-closed";
                        default        -> "account-not-active";
                    });

            case InsufficientFundsException e -> build(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient funds", e.getMessage(), "insufficient-funds");

            case IdempotencyConflictException e -> build(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency key conflict", e.getMessage(), "idempotency-conflict");
        };
        log.warn("Withdrawal rejected: {}", ex.getMessage());
        return problem;
    }

    /**
     * @Validated on the controller, for a present but blank Idempotency-Key. A missing
     * header is a different exception, handled by the base class.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "Invalid request", detail, "validation-failed");
    }

    /**
     * The lock_timeout set on every pooled connection surfaces here once @Retryable has
     * exhausted its attempts. The row is contended, not broken, so the caller is told to
     * come back rather than that the request failed.
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ProblemDetail> handleLockTimeout(CannotAcquireLockException ex) {
        log.warn("Lock wait exceeded on withdrawal path: {}", ex.getMessage());
        ProblemDetail problem = build(HttpStatus.SERVICE_UNAVAILABLE, "Account busy",
                "The account is temporarily locked by another operation. Retry shortly.",
                "account-busy");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    /**
     * Last resort. Generic on purpose: failure detail belongs in the logs, correlated by
     * id, not in a response body that may cross a trust boundary.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled error processing request", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be completed.", "internal-error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(build(HttpStatus.BAD_REQUEST, "Invalid request", detail, "validation-failed"));
    }

    /** Gives framework-mapped errors the same correlation id and timestamp as ours. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            decorate(problem);
        }
        return response;
    }

    private ProblemDetail build(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE + type));
        decorate(problem);
        return problem;
    }

    private void decorate(ProblemDetail problem) {
        problem.setProperty("timestamp", Instant.now());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}

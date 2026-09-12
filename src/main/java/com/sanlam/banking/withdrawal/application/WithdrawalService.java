package com.sanlam.banking.withdrawal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanlam.banking.withdrawal.api.dto.WithdrawalResponse;
import com.sanlam.banking.withdrawal.config.WithdrawalProperties;
import com.sanlam.banking.withdrawal.domain.WithdrawalCommand;
import com.sanlam.banking.withdrawal.domain.exception.IdempotencyConflictException;
import com.sanlam.banking.withdrawal.domain.exception.IdempotentReplayException;
import com.sanlam.banking.withdrawal.domain.exception.WithdrawalException;
import com.sanlam.banking.withdrawal.observability.WithdrawalMetrics;
import com.sanlam.banking.withdrawal.persistence.IdempotencyRepository;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Orchestration and the retry boundary. Holds no transaction of its own - see
 * {@link WithdrawalTransaction} for why the two are separate beans.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WithdrawalService {

    private final WithdrawalTransaction withdrawalTransaction;
    private final IdempotencyRepository idempotencyRepository;
    private final WithdrawalProperties properties;
    private final WithdrawalMetrics metrics;
    private final ObjectMapper objectMapper;

    /**
     * Retries are scoped to genuinely transient infrastructure faults - a
     * connection blip, or being chosen as a deadlock victim - each attempt
     * running in a fresh transaction.
     *
     * Business outcomes are deliberately excluded. InsufficientFundsException is
     * a correct answer, not a failure: retrying it would waste work and, worse,
     * could succeed on a later attempt after an unrelated deposit landed, which
     * is not what the caller asked for.
     *
     * This is a different concern from idempotency. Retry covers a request that
     * never got a database answer; idempotency covers a client that never got an
     * HTTP answer and sent the request again.
     */
    @Retryable(
            retryFor = { TransientDataAccessException.class, ConcurrencyFailureException.class },
            noRetryFor = { WithdrawalException.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2.0, maxDelay = 400))
    public WithdrawalResponse withdraw(WithdrawalCommand command) {
        Timer.Sample sample = Timer.start();
        String requestHash = hash(command);
        try {
            WithdrawalResponse response = withdrawalTransaction.execute(command, requestHash);
            metrics.recordAttempt("success");
            return response;
        } catch (IdempotentReplayException e) {
            // The transaction rolled back, so nothing was half-applied. The
            // winning request has committed, so its response is now readable.
            WithdrawalResponse replayed = replay(command, requestHash);
            metrics.recordAttempt("idempotent_replay");
            metrics.recordIdempotentReplay();
            return replayed;
        } catch (WithdrawalException e) {
            metrics.recordAttempt(e.getClass().getSimpleName());
            throw e;
        } finally {
            sample.stop(metrics.duration());
        }
    }

    private WithdrawalResponse replay(WithdrawalCommand command, String requestHash) {
        IdempotencyRepository.StoredResponse stored = idempotencyRepository
                .findResponse(command.clientId(), command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key was claimed by another request but no response was stored"));

        // Binding the key to the request that created it. Without this check a
        // client that reuses a key with a different amount silently receives the
        // previous response and the new withdrawal never happens.
        if (!requestHash.equals(stored.requestHash())) {
            throw new IdempotencyConflictException(command.idempotencyKey());
        }

        log.info("Idempotent replay for key={} account={}", command.idempotencyKey(), command.accountId());
        try {
            return objectMapper.readValue(stored.body(), WithdrawalResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotent response could not be read", e);
        }
    }

    /**
     * Fingerprint of the business parameters of the request. Only the fields
     * that define the operation are included - not the correlation id, which
     * legitimately differs between a client's original call and its retry.
     */
    private String hash(WithdrawalCommand command) {
        String canonical = "%d|%s|%s".formatted(
                command.accountId(),
                command.amount().stripTrailingZeros().toPlainString(),
                properties.defaultCurrency());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

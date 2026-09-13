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
     * Retries cover genuinely transient infrastructure faults - a connection blip, being
     * chosen as a deadlock victim - each attempt in a fresh transaction.
     *
     * <p>Business outcomes are excluded. InsufficientFundsException is a correct answer,
     * not a failure: retrying it wastes work and could succeed on a later attempt after an
     * unrelated deposit landed, which is not what the caller asked for.
     *
     * <p>Separate concern from idempotency. Retry covers a request that never got a
     * database answer; idempotency covers a client that never got an HTTP answer.
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
            try {
                WithdrawalResponse response = withdrawalTransaction.execute(command, requestHash);
                metrics.recordAttempt("success");
                return response;
            } catch (IdempotentReplayException e) {
                // Rolled back, so nothing was half-applied, and the winner has committed so
                // its response is readable.
            }

            // Deliberately out here rather than inside the catch above: a throw from within
            // a catch block is not caught by a sibling catch on the same try, so a conflict
            // raised during replay would escape the counters below.
            WithdrawalResponse replayed = replay(command, requestHash);
            metrics.recordAttempt("idempotent_replay");
            metrics.recordIdempotentReplay();
            return replayed;

        } catch (WithdrawalException e) {
            metrics.recordAttempt(e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            // Everything that is not a business outcome: a lock timeout, a dead connection,
            // a bug. These used to pass through uncounted, so withdrawal.attempts described
            // successes and refusals and was silent about the failures you would page on.
            metrics.recordAttempt("infrastructure_failure");
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

        // Binds the key to the request that created it. Without this, a client reusing a
        // key with a different amount silently gets the previous response and the new
        // withdrawal never happens.
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

    /** Business parameters only - not the correlation id, which differs across a retry. */
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

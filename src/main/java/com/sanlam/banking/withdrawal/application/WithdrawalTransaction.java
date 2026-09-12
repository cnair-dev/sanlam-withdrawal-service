package com.sanlam.banking.withdrawal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanlam.banking.withdrawal.api.dto.WithdrawalResponse;
import com.sanlam.banking.withdrawal.config.WithdrawalProperties;
import com.sanlam.banking.withdrawal.domain.AccountDiagnostic;
import com.sanlam.banking.withdrawal.domain.WithdrawalCommand;
import com.sanlam.banking.withdrawal.domain.exception.*;
import com.sanlam.banking.withdrawal.messaging.OutboxAppender;
import com.sanlam.banking.withdrawal.messaging.WithdrawalEvent;
import com.sanlam.banking.withdrawal.persistence.AccountRepository;
import com.sanlam.banking.withdrawal.persistence.IdempotencyRepository;
import com.sanlam.banking.withdrawal.persistence.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional unit of work for a withdrawal.
 *
 * <p>Its own bean, separate from {@link WithdrawalService}, because the retry policy has to
 * wrap the TRANSACTION rather than run inside it. Spring's transaction advice marks a
 * transaction rollback-only on the first exception, so retrying a statement within the same
 * transaction can never succeed. With @Retryable on the caller and @Transactional here,
 * each attempt begins a genuinely new transaction. Calling this via `this.` from the
 * service would bypass the proxy and silently run without a transaction at all; separate
 * beans make that mistake impossible.
 *
 * <p>All four writes below - balance, ledger, outbox, idempotency - commit or roll back
 * together. That one property is what makes the dual-write problem, the audit trail and
 * idempotency safety fall out of a single mechanism.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WithdrawalTransaction {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final OutboxAppender outboxAppender;
    private final IdempotencyRepository idempotencyRepository;
    private final WithdrawalProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional
    public WithdrawalResponse execute(WithdrawalCommand command, String requestHash) {

        // 1. Claim the key BEFORE touching money. A concurrent request with the same key
        //    blocks on the unique index until this transaction resolves; if this one
        //    commits, the other gets zero rows and replays the stored response.
        boolean claimed = idempotencyRepository.tryClaim(
                command.clientId(), command.idempotencyKey(), requestHash,
                command.accountId(), properties.idempotencyTtlHours());
        if (!claimed) {
            throw new IdempotentReplayException();
        }

        // 2. Atomic conditional debit.
        String currency = properties.defaultCurrency();
        Optional<BigDecimal> resulting = accountRepository.debitIfPermitted(
                command.accountId(), command.amount(), currency);

        if (resulting.isEmpty()) {
            // Throwing rolls back the idempotency claim too, so a genuine retry later is
            // not blocked by a failed attempt.
            throw explain(command);
        }

        BigDecimal newBalance = resulting.get();
        UUID transactionId = UUID.randomUUID();

        // 3. Double-entry: debit the customer, credit the settlement account. The ledger's
        //    own created_at is the movement's time - the event and the response quote it
        //    rather than calling Instant.now() on a second clock. One withdrawal timed by
        //    two independent clocks can have the event claiming an instant before the
        //    ledger entry it describes, which is the kind of thing an auditor finds.
        Instant now = ledgerRepository.recordWithdrawal(transactionId, command.accountId(),
                properties.settlementAccountId(), command.amount(), currency,
                command.correlationId());

        // 4. Outbox row, same transaction as everything above.
        WithdrawalEvent event = WithdrawalEvent.completed(transactionId, command.accountId(),
                command.amount(), newBalance, currency, command.correlationId(), now);
        outboxAppender.append(command.accountId(), WithdrawalEvent.TYPE,
                serialise(event), WithdrawalEvent.VERSION, command.correlationId());

        WithdrawalResponse response = new WithdrawalResponse(transactionId, command.accountId(),
                command.amount(), newBalance, currency, "SUCCESSFUL", now);

        // 5. Record the response so a replay can return it verbatim.
        idempotencyRepository.storeResponse(command.clientId(), command.idempotencyKey(),
                200, serialise(response));

        log.info("Withdrawal applied: txn={} account={} amount={} balance={}",
                transactionId, command.accountId(), command.amount(), newBalance);
        return response;
    }

    private WithdrawalException explain(WithdrawalCommand command) {
        AccountDiagnostic diagnostic = accountRepository.diagnose(command.accountId())
                .orElse(null);
        if (diagnostic == null) {
            return new AccountNotFoundException(command.accountId());
        }
        if (!diagnostic.isActive()) {
            return new AccountNotActiveException(command.accountId(), diagnostic.status());
        }
        if (!properties.defaultCurrency().equals(diagnostic.currency())) {
            return new CurrencyMismatchException(command.accountId(), diagnostic.currency(),
                    properties.defaultCurrency());
        }
        return new InsufficientFundsException(command.accountId(), command.amount());
    }

    private String serialise(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Jackson replaces the original String.format JSON building, which produced
            // invalid documents for any value containing a quote or backslash.
            throw new IllegalStateException("Failed to serialise payload", e);
        }
    }
}

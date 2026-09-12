package com.sanlam.banking.withdrawal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sanlam.banking.withdrawal.api.dto.WithdrawalResponse;
import com.sanlam.banking.withdrawal.config.WithdrawalProperties;
import com.sanlam.banking.withdrawal.domain.AccountDiagnostic;
import com.sanlam.banking.withdrawal.domain.WithdrawalCommand;
import com.sanlam.banking.withdrawal.domain.exception.*;
import com.sanlam.banking.withdrawal.messaging.OutboxAppender;
import com.sanlam.banking.withdrawal.messaging.OutboxOperations;
import com.sanlam.banking.withdrawal.observability.WithdrawalMetrics;
import com.sanlam.banking.withdrawal.persistence.AccountRepository;
import com.sanlam.banking.withdrawal.persistence.IdempotencyRepository;
import com.sanlam.banking.withdrawal.persistence.LedgerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the branch logic, using mocked repositories.
 *
 * These exist because the repository interfaces give a seam to mock against.
 * They cover decisions, not database behaviour - the concurrency guarantees are
 * covered by WithdrawalConcurrencyIT against a real PostgreSQL, because they
 * are properties of the database and cannot be demonstrated with mocks.
 */
class WithdrawalServiceTest {

    AccountRepository accounts = mock(AccountRepository.class);
    LedgerRepository ledger = mock(LedgerRepository.class);
    OutboxAppender outbox = mock(OutboxAppender.class);
    OutboxOperations outboxOperations = mock(OutboxOperations.class);
    IdempotencyRepository idempotency = mock(IdempotencyRepository.class);

    WithdrawalProperties properties = new WithdrawalProperties(9000L, "ZAR", 24);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    WithdrawalService service;

    @BeforeEach
    void setUp() {
        WithdrawalMetrics metrics = new WithdrawalMetrics(new SimpleMeterRegistry(), outboxOperations);
        WithdrawalTransaction transaction = new WithdrawalTransaction(
                accounts, ledger, outbox, idempotency, properties, objectMapper);
        service = new WithdrawalService(transaction, idempotency, properties, metrics, objectMapper);
        when(idempotency.tryClaim(any(), any(), any(), anyLong(), anyInt())).thenReturn(true);
    }

    private WithdrawalCommand command(String amount) {
        return new WithdrawalCommand(1001L, new BigDecimal(amount), "client-a", "key-1", "corr-1");
    }

    @Test
    @DisplayName("Successful withdrawal writes ledger and outbox and returns the new balance")
    void successfulWithdrawal() {
        when(accounts.debitIfPermitted(1001L, new BigDecimal("100.00"), "ZAR"))
                .thenReturn(Optional.of(new BigDecimal("900.00")));

        WithdrawalResponse response = service.withdraw(command("100.00"));

        assertThat(response.resultingBalance()).isEqualByComparingTo("900.00");
        assertThat(response.status()).isEqualTo("SUCCESSFUL");
        verify(ledger).recordWithdrawal(any(), eq(1001L), eq(9000L),
                eq(new BigDecimal("100.00")), eq("ZAR"), eq("corr-1"));
        verify(outbox).append(eq(1001L), anyString(), anyString(), anyInt(), eq("corr-1"));
    }

    @Test
    @DisplayName("Zero rows plus a missing account yields AccountNotFound")
    void accountNotFound() {
        when(accounts.debitIfPermitted(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(accounts.diagnose(1001L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(command("100.00")))
                .isInstanceOf(AccountNotFoundException.class);
        verifyNoInteractions(ledger);
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Zero rows plus a frozen account yields AccountNotActive, not InsufficientFunds")
    void frozenAccount() {
        when(accounts.debitIfPermitted(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(accounts.diagnose(1001L)).thenReturn(Optional.of(
                new AccountDiagnostic(1001L, "FROZEN", new BigDecimal("5000.00"), "ZAR")));

        assertThatThrownBy(() -> service.withdraw(command("100.00")))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    @DisplayName("Zero rows on an active, underfunded account yields InsufficientFunds")
    void insufficientFunds() {
        when(accounts.debitIfPermitted(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(accounts.diagnose(1001L)).thenReturn(Optional.of(
                new AccountDiagnostic(1001L, "ACTIVE", new BigDecimal("10.00"), "ZAR")));

        assertThatThrownBy(() -> service.withdraw(command("100.00")))
                .isInstanceOf(InsufficientFundsException.class);
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("Replaying a key with different parameters is rejected rather than silently ignored")
    void idempotencyKeyReusedWithDifferentRequest() {
        when(idempotency.tryClaim(any(), any(), any(), anyLong(), anyInt())).thenReturn(false);
        when(idempotency.findResponse("client-a", "key-1")).thenReturn(Optional.of(
                new IdempotencyRepository.StoredResponse("a-different-hash", 200, "{}")));

        assertThatThrownBy(() -> service.withdraw(command("100.00")))
                .isInstanceOf(IdempotencyConflictException.class);
        verifyNoInteractions(ledger);
    }
}

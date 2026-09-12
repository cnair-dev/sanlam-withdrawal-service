package com.sanlam.banking.withdrawal.api;

import com.sanlam.banking.withdrawal.api.dto.WithdrawalResponse;
import com.sanlam.banking.withdrawal.application.WithdrawalService;
import com.sanlam.banking.withdrawal.domain.WithdrawalCommand;
import com.sanlam.banking.withdrawal.domain.exception.AccountNotActiveException;
import com.sanlam.banking.withdrawal.domain.exception.AccountNotFoundException;
import com.sanlam.banking.withdrawal.domain.exception.IdempotencyConflictException;
import com.sanlam.banking.withdrawal.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract. Every status code the README and the OpenAPI annotations
 * promise is asserted here rather than assumed.
 *
 * <p>Written after a review found that a catch-all @ExceptionHandler was turning
 * malformed JSON, a blank required header and an unknown path into 500s while
 * the controller advertised 400. Nothing in the suite exercised the HTTP layer,
 * so nothing caught it. On an endpoint that moves money a spurious 500 is not a
 * cosmetic problem: it is the status that tells a client the outcome was
 * ambiguous and the call should be retried.
 */
@WebMvcTest(WithdrawalController.class)
class WithdrawalApiTest {

    private static final String PATH = "/v1/bank/withdraw";
    private static final String BODY = "{\"accountId\":1001,\"amount\":100.00}";

    @Autowired MockMvc mvc;
    @MockBean WithdrawalService withdrawalService;

    private static WithdrawalResponse ok() {
        return new WithdrawalResponse(UUID.randomUUID(), 1001L, new BigDecimal("100.00"),
                new BigDecimal("900.00"), "ZAR", "SUCCESSFUL", Instant.now());
    }

    @Test
    @DisplayName("A valid withdrawal returns 200 with the transaction body")
    void success() throws Exception {
        given(withdrawalService.withdraw(any())).willReturn(ok());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-1").header("X-Client-Id", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.resultingBalance").value(900.00));
    }

    @Test
    @DisplayName("Malformed JSON is a 400, not a 500")
    void malformedJson() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":")
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isBadRequest());

        verify(withdrawalService, never()).withdraw(any());
    }

    @Test
    @DisplayName("A non-numeric amount is a 400, not a 500")
    void unparseableAmount() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1001,\"amount\":\"abc\"}")
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A missing Idempotency-Key is a 400")
    void missingIdempotencyKey() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());

        verify(withdrawalService, never()).withdraw(any());
    }

    @Test
    @DisplayName("A blank Idempotency-Key is a 400")
    void blankIdempotencyKey() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest());

        verify(withdrawalService, never()).withdraw(any());
    }

    @Test
    @DisplayName("An unknown path is a 404, not a 500")
    void unknownPath() throws Exception {
        mvc.perform(get("/v1/bank/nope")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("The wrong HTTP method is a 405, not a 500")
    void wrongMethod() throws Exception {
        mvc.perform(get(PATH).header("Idempotency-Key", "key-1"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("An unsupported content type is a 415, not a 500")
    void unsupportedMediaType() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(BODY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("A sub-cent amount is rejected at the edge with 400")
    void subCentAmount() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1001,\"amount\":0.005}")
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isBadRequest());

        verify(withdrawalService, never()).withdraw(any());
    }

    @Test
    @DisplayName("Trailing zeros are a wire-format detail, not a client error")
    void trailingZerosAccepted() throws Exception {
        given(withdrawalService.withdraw(any())).willReturn(ok());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1001,\"amount\":100.000}")
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A negative amount is rejected - the original treated it as a deposit")
    void negativeAmount() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1001,\"amount\":-50.00}")
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isBadRequest());

        verify(withdrawalService, never()).withdraw(any());
    }

    @Test
    @DisplayName("Insufficient funds is 422 with an RFC 7807 body")
    void insufficientFunds() throws Exception {
        given(withdrawalService.withdraw(any()))
                .willThrow(new InsufficientFundsException(1001L, new BigDecimal("100.00")));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient funds"))
                .andExpect(jsonPath("$.type").value("https://sanlam.co.za/problems/insufficient-funds"));
    }

    @ParameterizedTest(name = "a {0} account is 409 with problem type {1}")
    @CsvSource({
            "FROZEN,  https://sanlam.co.za/problems/account-frozen",
            "DORMANT, https://sanlam.co.za/problems/account-dormant",
            "CLOSED,  https://sanlam.co.za/problems/account-closed"
    })
    void inactiveAccountsCarryTheirOwnProblemType(String status, String expectedType) throws Exception {
        given(withdrawalService.withdraw(any()))
                .willThrow(new AccountNotActiveException(1003L, status));

        // Same status code, different type. A caller branches on the type to
        // decide whether to prompt reactivation, surface a hold, or stop.
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Account not active"))
                .andExpect(jsonPath("$.type").value(expectedType));
    }

    @Test
    @DisplayName("An unknown account is 404")
    void unknownAccount() throws Exception {
        given(withdrawalService.withdraw(any())).willThrow(new AccountNotFoundException(9999L));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A key reused with different parameters is 422")
    void idempotencyConflict() throws Exception {
        given(withdrawalService.withdraw(any())).willThrow(new IdempotencyConflictException("key-1"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Idempotency key conflict"));
    }

    @Test
    @DisplayName("An unexpected failure is a 500 that leaks no internal detail")
    void unexpectedFailure() throws Exception {
        given(withdrawalService.withdraw(any()))
                .willThrow(new IllegalStateException("connection reset by peer at 10.0.3.11"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("The request could not be completed."));
    }

    @Test
    @DisplayName("The client id reaches the service, so keys are scoped to a caller")
    void clientIdIsPropagated() throws Exception {
        given(withdrawalService.withdraw(any())).willReturn(ok());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-1").header("X-Client-Id", "partner-a"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(WithdrawalCommand.class);
        verify(withdrawalService).withdraw(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().clientId()).isEqualTo("partner-a");
    }
}

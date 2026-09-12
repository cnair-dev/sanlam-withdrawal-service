package com.sanlam.banking.withdrawal.api;

import com.sanlam.banking.withdrawal.api.dto.WithdrawalRequest;
import com.sanlam.banking.withdrawal.api.dto.WithdrawalResponse;
import com.sanlam.banking.withdrawal.application.WithdrawalService;
import com.sanlam.banking.withdrawal.config.CorrelationIdFilter;
import com.sanlam.banking.withdrawal.domain.WithdrawalCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * The endpoint keeps the original business capability exactly: debit an account
 * if it can cover the amount, and emit a withdrawal event.
 *
 * The wire contract does change, deliberately: query parameters become a JSON
 * body, plain-string replies become typed responses and RFC 7807 problem
 * documents, and every response now carries a meaningful status code instead of
 * an unconditional 200. The /v1 prefix is what makes that safe - an existing
 * caller can be migrated rather than broken, and a future contract change gets
 * /v2 rather than a silent reinterpretation of this one.
 */
@RestController
@RequestMapping("/v1/bank")
@Validated
@Slf4j
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw funds from an account",
               description = "Debits the account atomically and emits a withdrawal event. "
                           + "Requires an Idempotency-Key header; replaying the same key returns "
                           + "the original response without debiting again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawal applied, or original response replayed"),
            @ApiResponse(responseCode = "400", description = "Malformed request"),
            @ApiResponse(responseCode = "404", description = "Account does not exist"),
            @ApiResponse(responseCode = "409", description = "Account is not active"),
            @ApiResponse(responseCode = "422", description = "Insufficient funds, or idempotency key reused with different parameters")
    })
    public ResponseEntity<WithdrawalResponse> withdraw(
            @Valid @RequestBody WithdrawalRequest request,

            // Required, not optional. For an operation that moves money, leaving
            // idempotency to the caller's discretion guarantees that some caller
            // will eventually double-debit on a timeout retry.
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,

            // Keys are scoped per caller so two clients cannot collide on, or
            // squat, the same key value.
            @RequestHeader(value = "X-Client-Id", defaultValue = "anonymous") String clientId) {

        MDC.put("accountId", String.valueOf(request.accountId()));
        try {
            WithdrawalCommand command = new WithdrawalCommand(
                    request.accountId(),
                    request.amount(),
                    clientId,
                    idempotencyKey,
                    MDC.get(CorrelationIdFilter.MDC_KEY));

            return ResponseEntity.ok(withdrawalService.withdraw(command));
        } finally {
            MDC.remove("accountId");
        }
    }
}

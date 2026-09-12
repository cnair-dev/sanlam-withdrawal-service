package com.sanlam.banking.withdrawal.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalResponse(
        UUID transactionId,
        Long accountId,
        BigDecimal amount,
        BigDecimal resultingBalance,
        String currency,
        String status,
        Instant processedAt
) {}

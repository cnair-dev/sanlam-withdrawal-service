package com.sanlam.banking.withdrawal.domain;

import java.math.BigDecimal;

public record WithdrawalCommand(
        long accountId,
        BigDecimal amount,
        String clientId,
        String idempotencyKey,
        String correlationId
) {}

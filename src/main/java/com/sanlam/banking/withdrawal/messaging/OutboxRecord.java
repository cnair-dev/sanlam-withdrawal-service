package com.sanlam.banking.withdrawal.messaging;

public record OutboxRecord(long id, long aggregateId, String eventType, String payload, int attemptCount) {}

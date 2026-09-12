package com.sanlam.banking.withdrawal.persistence;

import java.util.Optional;

public interface IdempotencyRepository {

    /** Attempt to claim the key. True if this request now owns it. */
    boolean tryClaim(String clientId, String key, String requestHash, long accountId, int ttlHours);

    Optional<StoredResponse> findResponse(String clientId, String key);

    void storeResponse(String clientId, String key, int status, String body);

    int purgeExpired();

    record StoredResponse(String requestHash, Integer status, String body) {}
}

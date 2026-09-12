package com.sanlam.banking.withdrawal.persistence;

import java.util.Optional;

public interface IdempotencyRepository {

    /**
     * Attempt to claim the key. Returns true if this request now owns it.
     *
     * Implemented with INSERT ... ON CONFLICT DO NOTHING rather than by catching
     * a duplicate-key exception, because in PostgreSQL ANY error aborts the
     * surrounding transaction - a caught constraint violation would leave the
     * transaction unusable without a SAVEPOINT. ON CONFLICT DO NOTHING is an
     * ordinary result, not an error.
     *
     * Verified under concurrency: the losing session blocks at the INSERT until
     * the winner commits, then receives 0 rows with zero errors raised.
     */
    boolean tryClaim(String clientId, String key, String requestHash, long accountId, int ttlHours);

    Optional<StoredResponse> findResponse(String clientId, String key);

    void storeResponse(String clientId, String key, int status, String body);

    int purgeExpired();

    record StoredResponse(String requestHash, Integer status, String body) {}
}

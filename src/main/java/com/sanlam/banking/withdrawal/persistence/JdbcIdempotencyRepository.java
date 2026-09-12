package com.sanlam.banking.withdrawal.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final JdbcClient jdbc;

    @Override
    public boolean tryClaim(String clientId, String key, String requestHash, long accountId, int ttlHours) {
        int rows = jdbc.sql("""
                INSERT INTO idempotency_key
                       (client_id, idempotency_key, request_hash, account_id, expires_at)
                VALUES (:clientId, :key, :hash, :accountId, now() + make_interval(hours => :ttl))
                ON CONFLICT (client_id, idempotency_key) DO NOTHING
                """)
                .param("clientId", clientId)
                .param("key", key)
                .param("hash", requestHash)
                .param("accountId", accountId)
                .param("ttl", ttlHours)
                .update();
        return rows == 1;
    }

    @Override
    public Optional<StoredResponse> findResponse(String clientId, String key) {
        return jdbc.sql("""
                SELECT request_hash, response_status, CAST(response_body AS text) AS response_body
                  FROM idempotency_key
                 WHERE client_id = :clientId AND idempotency_key = :key
                """)
                .param("clientId", clientId)
                .param("key", key)
                .query((rs, rowNum) -> new StoredResponse(
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body")))
                .optional();
    }

    @Override
    public void storeResponse(String clientId, String key, int status, String body) {
        jdbc.sql("""
                UPDATE idempotency_key
                   SET response_status = :status,
                       response_body   = CAST(:body AS jsonb)
                 WHERE client_id = :clientId AND idempotency_key = :key
                """)
                .param("status", status)
                .param("body", body)
                .param("clientId", clientId)
                .param("key", key)
                .update();
    }

    @Override
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM idempotency_key WHERE expires_at < now()").update();
    }
}

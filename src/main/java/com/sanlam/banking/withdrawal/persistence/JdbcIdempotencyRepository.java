package com.sanlam.banking.withdrawal.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final JdbcClient jdbc;

    /**
     * Claims the key, or reports that someone else already holds it. ON CONFLICT rather
     * than catching a duplicate-key exception: in PostgreSQL any error aborts the
     * surrounding transaction, leaving the caller holding one it cannot use for the replay
     * read that follows.
     *
     * <p>DO UPDATE ... WHERE expires_at < now() is what enforces the TTL. Expiry is a
     * property of the row, so it has to be evaluated when the row is claimed. Leaving it to
     * the nightly purge meant a key stayed effective for up to 24h past expiry and a
     * legitimate reuse silently replayed the original response instead of withdrawing.
     *
     * <p>DO UPDATE takes a row lock where DO NOTHING does not, so a concurrent duplicate
     * blocks until the winner commits, then re-evaluates the WHERE against the committed
     * row - the winner has just pushed expires_at forward, so the loser sees a live key.
     */
    @Override
    public boolean tryClaim(String clientId, String key, String requestHash, long accountId, int ttlHours) {
        int rows = jdbc.sql("""
                INSERT INTO idempotency_key
                       (client_id, idempotency_key, request_hash, account_id, expires_at)
                VALUES (:clientId, :key, :hash, :accountId, now() + make_interval(hours => :ttl))
                ON CONFLICT (client_id, idempotency_key) DO UPDATE
                   SET request_hash    = EXCLUDED.request_hash,
                       account_id      = EXCLUDED.account_id,
                       expires_at      = EXCLUDED.expires_at,
                       response_status = NULL,
                       response_body   = NULL
                 WHERE idempotency_key.expires_at < now()
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

    /**
     * Removes keys that are already past their TTL and therefore no longer protecting
     * anything - the claim in tryClaim treats an expired row as available regardless. This
     * reclaims space; it does not shorten the guarantee. The TTL is what sets that, and
     * WithdrawalProperties says why it is not a number to tune for disk.
     */
    @Override
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM idempotency_key WHERE expires_at < now()").update();
    }
}

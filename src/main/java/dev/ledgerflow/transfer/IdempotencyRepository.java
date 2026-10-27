package dev.ledgerflow.transfer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record Record(String requestHash, String responseBody) {}

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this transaction. Returns 0 if another transaction already owns it. When that other
     * transaction is still open, PostgreSQL makes this insert wait until it commits or rolls back, which is
     * what serialises two concurrent requests that carry the same key.
     */
    public int tryClaim(String principal, String key, String requestHash) {
        return jdbc.sql("INSERT INTO idempotency_keys (principal, idem_key, request_hash) "
                        + "VALUES (:p, :k, :h) ON CONFLICT DO NOTHING")
                .param("p", principal)
                .param("k", key)
                .param("h", requestHash)
                .update();
    }

    public Optional<Record> find(String principal, String key) {
        return jdbc.sql("SELECT request_hash, response_body FROM idempotency_keys WHERE principal = :p AND idem_key = :k")
                .param("p", principal)
                .param("k", key)
                .query((rs, i) -> new Record(rs.getString("request_hash"), rs.getString("response_body")))
                .optional();
    }

    public void complete(String principal, String key, UUID transactionId, String responseBody) {
        jdbc.sql("UPDATE idempotency_keys SET transaction_id = :t, response_body = :b WHERE principal = :p AND idem_key = :k")
                .param("t", transactionId)
                .param("b", responseBody)
                .param("p", principal)
                .param("k", key)
                .update();
    }
}

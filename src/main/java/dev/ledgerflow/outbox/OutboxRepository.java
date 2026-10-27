package dev.ledgerflow.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    public record OutboxEvent(UUID id, String aggregateId, String eventType, String payload) {}

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the same transaction as the state change it describes. */
    public void insert(String aggregateId, String eventType, String payloadJson) {
        jdbc.sql("INSERT INTO outbox_events (id, aggregate_id, event_type, payload) "
                        + "VALUES (:id, :agg, :type, CAST(:payload AS jsonb))")
                .param("id", UUID.randomUUID())
                .param("agg", aggregateId)
                .param("type", eventType)
                .param("payload", payloadJson)
                .update();
    }

    /** SKIP LOCKED lets several instances drain the outbox without publishing the same row twice at once. */
    public List<OutboxEvent> lockUnpublished(int limit) {
        return jdbc.sql("SELECT id, aggregate_id, event_type, payload::text AS payload FROM outbox_events "
                        + "WHERE published_at IS NULL ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("limit", limit)
                .query((rs, i) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = :id").param("id", id).update();
    }
}

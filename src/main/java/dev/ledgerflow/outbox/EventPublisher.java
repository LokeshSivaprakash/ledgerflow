package dev.ledgerflow.outbox;

/**
 * Where outbox events are delivered. The default implementation logs them; a message-broker publisher (Kafka,
 * SNS, ...) can replace it (mark it @Primary) without touching the ledger code. Delivery is at-least-once, so consumers must be
 * idempotent.
 */
public interface EventPublisher {
    void publish(OutboxRepository.OutboxEvent event);
}

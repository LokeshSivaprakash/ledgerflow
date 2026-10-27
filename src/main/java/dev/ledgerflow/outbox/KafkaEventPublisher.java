package dev.ledgerflow.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Delivers outbox events to a Kafka topic.
 *
 * <p>The record key is the transaction id, so every event for one transaction lands on the same partition in order.
 * The {@code event-id} header carries the outbox row id: delivery is at-least-once (a crash after the broker accepts
 * a record but before the outbox row is marked published causes a resend), so consumers should de-duplicate on it.
 *
 * <p>The send is awaited. If the broker is unreachable the exception propagates, the outbox transaction rolls back,
 * and the same batch is retried on the next poll.
 */
@Component
@ConditionalOnProperty(prefix = "ledger.events", name = "publisher", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka,
            @Value("${ledger.events.topic:ledger.transactions}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public void publish(OutboxRepository.OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.aggregateId(), event.payload());
        record.headers().add("event-id", event.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", event.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing event " + event.id(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka publish failed for event " + event.id(), e);
        }
    }
}

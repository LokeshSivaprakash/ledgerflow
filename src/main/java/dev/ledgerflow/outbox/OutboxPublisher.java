package dev.ledgerflow.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {

    private static final int BATCH = 100;

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Counter published;

    public OutboxPublisher(OutboxRepository outbox, EventPublisher publisher, MeterRegistry meters) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.published = Counter.builder("ledger.outbox.published").description("Outbox events delivered").register(meters);
    }

    @Scheduled(initialDelayString = "${ledger.outbox.initial-delay-ms:1000}",
            fixedDelayString = "${ledger.outbox.poll-ms:1000}")
    public void poll() {
        while (publishPending() == BATCH) {
            // keep draining while full batches come back
        }
    }

    /**
     * Publishes one batch and marks it. If publish throws, the transaction rolls back, nothing is marked, and the
     * batch is retried on the next poll (at-least-once delivery).
     */
    @Transactional
    public int publishPending() {
        List<OutboxRepository.OutboxEvent> events = outbox.lockUnpublished(BATCH);
        for (OutboxRepository.OutboxEvent e : events) {
            publisher.publish(e);
            outbox.markPublished(e.id());
            published.increment();
        }
        return events.size();
    }
}

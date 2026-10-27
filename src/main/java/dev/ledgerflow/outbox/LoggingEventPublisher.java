package dev.ledgerflow.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ledger.events", name = "publisher", havingValue = "logging", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxRepository.OutboxEvent event) {
        log.info("outbox event type={} aggregate={} payload={}", event.eventType(), event.aggregateId(), event.payload());
    }
}

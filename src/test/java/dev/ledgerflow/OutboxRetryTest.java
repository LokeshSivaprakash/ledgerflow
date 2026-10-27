package dev.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerflow.outbox.EventPublisher;
import dev.ledgerflow.outbox.OutboxPublisher;
import dev.ledgerflow.outbox.OutboxRepository;
import dev.ledgerflow.transfer.LedgerService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Proves the outbox guarantee: a failed publish loses nothing, and the same events go out on the next attempt. */
@SpringBootTest
@ActiveProfiles("test")
@Import(OutboxRetryTest.ToggleablePublisherConfig.class)
class OutboxRetryTest {

    static final AtomicBoolean FAIL = new AtomicBoolean(false);
    static final AtomicInteger DELIVERED = new AtomicInteger();

    @TestConfiguration
    static class ToggleablePublisherConfig {
        @Bean
        @Primary
        EventPublisher toggleablePublisher() {
            return (OutboxRepository.OutboxEvent event) -> {
                if (FAIL.get()) {
                    throw new IllegalStateException("broker down");
                }
                DELIVERED.incrementAndGet();
            };
        }
    }

    @Autowired LedgerService ledger;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired JdbcClient jdbc;

    @Test
    void failedPublishRollsBackAndTheSameEventsAreRetried() {
        // start from a drained outbox so the counts below are exact
        FAIL.set(false);
        while (outboxPublisher.publishPending() > 0) {
            // drain
        }
        DELIVERED.set(0);

        ledger.openAccount("retry-" + UUID.randomUUID(), "USD", 500); // writes exactly one outbox event
        assertThat(unpublished()).isEqualTo(1);

        FAIL.set(true);
        assertThatThrownBy(() -> outboxPublisher.publishPending()).hasMessageContaining("broker down");
        assertThat(unpublished()).as("nothing marked published after a failure").isEqualTo(1);
        assertThat(DELIVERED.get()).isZero();

        FAIL.set(false);
        assertThat(outboxPublisher.publishPending()).isEqualTo(1);
        assertThat(unpublished()).isZero();
        assertThat(DELIVERED.get()).isEqualTo(1);
    }

    private int unpublished() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Integer.class).single();
    }
}

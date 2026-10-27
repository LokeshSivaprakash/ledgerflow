package dev.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerflow.outbox.OutboxPublisher;
import dev.ledgerflow.transfer.LedgerService;
import dev.ledgerflow.transfer.TransferDtos.TransferRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Runs the real Kafka publisher against an in-process broker. */
@SpringBootTest(properties = {
        "ledger.events.publisher=kafka",
        "ledger.events.topic=ledger.transactions.test",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@EmbeddedKafka(partitions = 1, topics = "ledger.transactions.test")
@ActiveProfiles("test")
@DirtiesContext
class KafkaPublisherTest {

    @Autowired LedgerService ledger;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired JdbcClient jdbc;
    @Autowired EmbeddedKafkaBroker broker;

    @Test
    void committedTransfersAreDeliveredToKafkaWithKeyAndHeaders() {
        String owner = "kafka-" + UUID.randomUUID();
        var from = ledger.openAccount(owner, "USD", 1_000);
        var to = ledger.openAccount(owner, "USD", 0);
        var outcome = ledger.transfer(owner, "kafka-key-" + UUID.randomUUID(),
                new TransferRequest(from.id(), to.id(), 250, "USD"));
        String transactionId = outcome.response().transactionId().toString();

        while (outboxPublisher.publishPending() > 0) {
            // drain everything pending, including events from other tests
        }

        List<ConsumerRecord<String, String>> records = readAll();
        ConsumerRecord<String, String> mine = records.stream()
                .filter(r -> transactionId.equals(r.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record for transaction " + transactionId));

        assertThat(mine.value()).contains("\"type\"").contains("TRANSFER").contains(transactionId);
        assertThat(header(mine, "event-type")).isEqualTo("ledger.transaction.posted");
        assertThat(UUID.fromString(header(mine, "event-id"))).isNotNull();

        Integer unpublished = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Integer.class).single();
        assertThat(unpublished).isZero();
    }

    private List<ConsumerRecord<String, String>> readAll() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("verify-" + UUID.randomUUID(), "false", broker);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "ledger.transactions.test");
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            ConsumerRecords<String, String> batch = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
            batch.forEach(out::add);
            return out;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}

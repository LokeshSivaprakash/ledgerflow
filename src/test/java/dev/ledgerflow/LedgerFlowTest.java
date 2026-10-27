package dev.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerflow.config.DevTokenController;
import dev.ledgerflow.config.LedgerProperties;
import dev.ledgerflow.outbox.OutboxPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LedgerFlowTest {

    @Autowired TestRestTemplate http;
    @Autowired JwtEncoder encoder;
    @Autowired LedgerProperties props;
    @Autowired JdbcClient jdbc;
    @Autowired OutboxPublisher outboxPublisher;

    // ---------- helpers ----------

    private String token(String subject) {
        return DevTokenController.issue(encoder, props, subject);
    }

    private HttpHeaders headers(String subject, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token(subject));
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> openAccount(String owner, long deposit) {
        ResponseEntity<Map> r = http.exchange("/api/accounts", HttpMethod.POST,
                new HttpEntity<>(Map.of("currency", "USD", "initialDepositMinor", deposit), headers(owner, null)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> transfer(String owner, String key, Object from, Object to, long amount, String currency) {
        return http.exchange("/api/transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of("fromAccountId", from, "toAccountId", to, "amountMinor", amount, "currency", currency),
                        headers(owner, key)), Map.class);
    }

    private long balance(String owner, Object accountId) {
        ResponseEntity<Map> r = http.exchange("/api/accounts/" + accountId, HttpMethod.GET,
                new HttpEntity<>(headers(owner, null)), Map.class);
        return ((Number) r.getBody().get("balanceMinor")).longValue();
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    // ---------- tests ----------

    @Test
    void requestsWithoutTokenAreRejected() {
        ResponseEntity<String> r = http.getForEntity("/api/accounts/" + UUID.randomUUID(), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listingAccountsReturnsOnlyTheCallersOwn() {
        String me = "lister-" + UUID.randomUUID();
        Map<String, Object> mine = openAccount(me, 1_000);
        openAccount("someone-else-" + UUID.randomUUID(), 1_000);
        ResponseEntity<List> r = http.exchange("/api/accounts", HttpMethod.GET,
                new HttpEntity<>(headers(me, null)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
        assertThat(((Map<String, Object>) r.getBody().get(0)).get("id")).isEqualTo(mine.get("id"));
        assertThat(http.getForEntity("/api/accounts", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void openingAccountWithDepositFundsItFromTreasury() {
        Map<String, Object> acc = openAccount("alice-" + UUID.randomUUID(), 5_000);
        assertThat(((Number) acc.get("balanceMinor")).longValue()).isEqualTo(5_000);
    }

    @Test
    void transferMovesMoneyAndWritesBalancedEntriesAndOutboxEvent() {
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        Object a = openAccount(alice, 10_000).get("id");
        Object b = openAccount(bob, 0).get("id");

        ResponseEntity<Map> r = transfer(alice, key(), a, b, 2_500, "USD");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(alice, a)).isEqualTo(7_500);
        assertThat(balance(bob, b)).isEqualTo(2_500);

        UUID txId = UUID.fromString((String) r.getBody().get("transactionId"));
        Long sum = jdbc.sql("SELECT SUM(delta_minor) FROM ledger_entries WHERE transaction_id = :t")
                .param("t", txId).query(Long.class).single();
        Integer legs = jdbc.sql("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = :t")
                .param("t", txId).query(Integer.class).single();
        Integer events = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = :t")
                .param("t", txId.toString()).query(Integer.class).single();
        assertThat(sum).isZero();
        assertThat(legs).isEqualTo(2);
        assertThat(events).isEqualTo(1);
    }

    @Test
    void insufficientFundsIsRejectedAndNothingMoves() {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 1_000).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");

        ResponseEntity<Map> r = transfer(alice, key(), a, b, 1_001, "USD");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("code")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(alice, a)).isEqualTo(1_000);
    }

    @Test
    void sameIdempotencyKeyReplaysTheOriginalResultWithoutMovingMoneyAgain() {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 10_000).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");
        String k = key();

        ResponseEntity<Map> first = transfer(alice, k, a, b, 4_000, "USD");
        ResponseEntity<Map> second = transfer(alice, k, a, b, 4_000, "USD");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("transactionId")).isEqualTo(first.getBody().get("transactionId"));
        assertThat(balance(alice, a)).isEqualTo(6_000);
    }

    @Test
    void reusingAKeyWithADifferentBodyIsRejected() {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 10_000).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");
        String k = key();

        transfer(alice, k, a, b, 100, "USD");
        ResponseEntity<Map> r = transfer(alice, k, a, b, 200, "USD");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(alice, a)).isEqualTo(9_900);
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 100).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");
        ResponseEntity<Map> r = transfer(alice, null, a, b, 10, "USD");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidAmountIsRejectedByValidation() {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 100).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");
        ResponseEntity<Map> r = transfer(alice, key(), a, b, -5, "USD");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void currencyMismatchIsRejected() {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 1_000).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");
        ResponseEntity<Map> r = transfer(alice, key(), a, b, 100, "EUR");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("code")).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void otherUsersAccountsLookNonexistent() {
        String alice = "alice-" + UUID.randomUUID();
        String mallory = "mallory-" + UUID.randomUUID();
        Object a = openAccount(alice, 1_000).get("id");
        Object m = openAccount(mallory, 0).get("id");

        ResponseEntity<Map> read = http.exchange("/api/accounts/" + a, HttpMethod.GET,
                new HttpEntity<>(headers(mallory, null)), Map.class);
        ResponseEntity<Map> steal = transfer(mallory, key(), a, m, 500, "USD");

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(steal.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balance(alice, a)).isEqualTo(1_000);
    }

    @Test
    void concurrentTransfersNeverOverdrawAndAlwaysConserveMoney() throws Exception {
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        Object a = openAccount(alice, 100).get("id"); // enough for exactly 10 transfers of 10
        Object b = openAccount(bob, 0).get("id");

        List<ResponseEntity<Map>> results = runConcurrently(20, i -> transfer(alice, key(), a, b, 10, "USD"));

        long ok = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long rejected = results.stream().filter(r -> r.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY).count();
        assertThat(ok).isEqualTo(10);
        assertThat(rejected).isEqualTo(10);
        assertThat(balance(alice, a)).isZero();
        assertThat(balance(bob, b)).isEqualTo(100);
    }

    @Test
    void concurrentRequestsWithTheSameKeyPostExactlyOneTransaction() throws Exception {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 1_000).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");
        String k = key();

        List<ResponseEntity<Map>> results = runConcurrently(10, i -> transfer(alice, k, a, b, 100, "USD"));

        assertThat(results).allMatch(r -> r.getStatusCode().is2xxSuccessful());
        long distinctTransactions = results.stream().map(r -> r.getBody().get("transactionId")).distinct().count();
        assertThat(distinctTransactions).isEqualTo(1);
        assertThat(balance(alice, a)).isEqualTo(900);
    }

    @Test
    void opposingConcurrentTransfersDoNotDeadlock() throws Exception {
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        Object a = openAccount(alice, 10_000).get("id");
        Object b = openAccount(bob, 10_000).get("id");

        List<ResponseEntity<Map>> results = runConcurrently(20, i -> i % 2 == 0
                ? transfer(alice, key(), a, b, 5, "USD")
                : transfer(bob, key(), b, a, 5, "USD"));

        assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);
        assertThat(balance(alice, a) + balance(bob, b)).isEqualTo(20_000);
    }

    @Test
    void everyTransactionInTheLedgerSumsToZero() {
        Integer unbalanced = jdbc.sql("SELECT COUNT(*) FROM (SELECT transaction_id FROM ledger_entries "
                + "GROUP BY transaction_id HAVING SUM(delta_minor) <> 0) t").query(Integer.class).single();
        assertThat(unbalanced).isZero();
    }

    @Test
    void outboxPublisherMarksPendingEventsPublished() {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 500).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");
        transfer(alice, key(), a, b, 50, "USD");

        int before = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Integer.class).single();
        assertThat(before).isGreaterThan(0);

        while (outboxPublisher.publishPending() > 0) {
            // drain
        }

        int after = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Integer.class).single();
        assertThat(after).isZero();
    }

    @Test
    void entriesEndpointPagesNewestFirst() {
        String alice = "alice-" + UUID.randomUUID();
        Object a = openAccount(alice, 1_000).get("id");
        Object b = openAccount("bob-" + UUID.randomUUID(), 0).get("id");
        for (int i = 0; i < 3; i++) {
            transfer(alice, key(), a, b, 10, "USD");
        }

        ResponseEntity<Map> page = http.exchange("/api/accounts/" + a + "/entries?limit=2", HttpMethod.GET,
                new HttpEntity<>(headers(alice, null)), Map.class);

        List<?> entries = (List<?>) page.getBody().get("entries");
        assertThat(entries).hasSize(2);
        assertThat(page.getBody().get("nextBefore")).isNotNull();
    }

    private <T> List<T> runConcurrently(int n, java.util.function.IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Callable<T> c = () -> {
                ready.countDown();
                go.await();
                return task.apply(idx);
            };
            futures.add(pool.submit(c));
        }
        ready.await();
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}

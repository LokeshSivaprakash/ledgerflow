package dev.ledgerflow.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerflow.account.Account;
import dev.ledgerflow.account.AccountRepository;
import dev.ledgerflow.common.ApiException;
import dev.ledgerflow.outbox.OutboxRepository;
import dev.ledgerflow.transfer.TransferDtos.TransferOutcome;
import dev.ledgerflow.transfer.TransferDtos.TransferRequest;
import dev.ledgerflow.transfer.TransferDtos.TransferResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final IdempotencyRepository idempotency;
    private final OutboxRepository outbox;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Counter transfers;
    private final Counter replays;

    public LedgerService(AccountRepository accounts, IdempotencyRepository idempotency, OutboxRepository outbox,
            JdbcClient jdbc, ObjectMapper json, MeterRegistry meters) {
        this.accounts = accounts;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
        this.transfers = Counter.builder("ledger.transfers").description("Transfers posted").register(meters);
        this.replays = Counter.builder("ledger.transfers.replayed").description("Idempotent replays").register(meters);
    }

    @Transactional
    public Account openAccount(String owner, String currency, long initialDepositMinor) {
        UUID id = UUID.randomUUID();
        accounts.insert(new Account(id, owner, currency, 0, false));
        if (initialDepositMinor > 0) {
            Account treasury = accounts.treasury(currency);
            List<Account> locked = accounts.lockAll(List.of(treasury.id(), id));
            post("DEPOSIT", pick(locked, treasury.id()), pick(locked, id), initialDepositMinor);
        }
        return accounts.find(id).orElseThrow();
    }

    @Transactional
    public TransferOutcome transfer(String principal, String idempotencyKey, TransferRequest req) {
        String hash = requestHash(req);

        if (idempotency.tryClaim(principal, idempotencyKey, hash) == 0) {
            IdempotencyRepository.Record existing = idempotency.find(principal, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency row vanished"));
            if (!existing.requestHash().equals(hash)) {
                throw ApiException.idempotencyKeyReused();
            }
            replays.increment();
            return new TransferOutcome(read(existing.responseBody()), true);
        }

        if (req.fromAccountId().equals(req.toAccountId())) {
            throw ApiException.sameAccount();
        }

        List<Account> locked = accounts.lockAll(List.of(req.fromAccountId(), req.toAccountId()));
        if (locked.size() != 2) {
            throw ApiException.accountNotFound();
        }
        Account from = pick(locked, req.fromAccountId());
        Account to = pick(locked, req.toAccountId());

        // Someone else's account is reported as missing, so account ids cannot be probed.
        if (!from.ownerSubject().equals(principal)) {
            throw ApiException.accountNotFound();
        }
        if (!from.currency().equals(req.currency()) || !to.currency().equals(req.currency())) {
            throw ApiException.currencyMismatch();
        }
        if (!from.allowNegative() && from.balanceMinor() < req.amountMinor()) {
            throw ApiException.insufficientFunds();
        }

        PostResult posted = post("TRANSFER", from, to, req.amountMinor());
        TransferResponse response = new TransferResponse(posted.transactionId(), from.id(), to.id(),
                req.amountMinor(), req.currency(), from.balanceMinor() - req.amountMinor(), posted.createdAt());
        idempotency.complete(principal, idempotencyKey, posted.transactionId(), write(response));
        transfers.increment();
        return new TransferOutcome(response, false);
    }

    private record PostResult(UUID transactionId, Instant createdAt) {}

    /** Writes both legs, updates both balances and the outbox event. Callers must already hold the row locks. */
    private PostResult post(String type, Account from, Account to, long amount) {
        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("INSERT INTO ledger_transactions (id, type) VALUES (:id, :type)")
                .param("id", txId).param("type", type).update();
        insertEntry(txId, from.id(), -amount);
        insertEntry(txId, to.id(), amount);
        accounts.setBalance(from.id(), from.balanceMinor() - amount);
        accounts.setBalance(to.id(), to.balanceMinor() + amount);

        outbox.insert(txId.toString(), "ledger.transaction.posted", write(Map.of(
                "transactionId", txId,
                "type", type,
                "fromAccountId", from.id(),
                "toAccountId", to.id(),
                "amountMinor", amount,
                "currency", from.currency(),
                "occurredAt", now)));
        return new PostResult(txId, now);
    }

    private void insertEntry(UUID txId, UUID accountId, long delta) {
        jdbc.sql("INSERT INTO ledger_entries (transaction_id, account_id, delta_minor) VALUES (:t, :a, :d)")
                .param("t", txId).param("a", accountId).param("d", delta).update();
    }

    private static Account pick(List<Account> accounts, UUID id) {
        return accounts.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private static String requestHash(TransferRequest r) {
        String canonical = r.fromAccountId() + "|" + r.toAccountId() + "|" + r.amountMinor() + "|" + r.currency();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TransferResponse read(String body) {
        try {
            return json.readValue(body, TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

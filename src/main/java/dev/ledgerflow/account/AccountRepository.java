package dev.ledgerflow.account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, owner_subject, currency, balance_minor, allow_negative";

    private static final RowMapper<Account> MAPPER = (rs, i) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("owner_subject"),
            rs.getString("currency"),
            rs.getLong("balance_minor"),
            rs.getBoolean("allow_negative"));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account a) {
        jdbc.sql("INSERT INTO accounts (id, owner_subject, currency, balance_minor, allow_negative) "
                        + "VALUES (:id, :owner, :currency, :balance, :neg)")
                .param("id", a.id())
                .param("owner", a.ownerSubject())
                .param("currency", a.currency())
                .param("balance", a.balanceMinor())
                .param("neg", a.allowNegative())
                .update();
    }

    public Optional<Account> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<Account> findByOwner(String owner) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE owner_subject = :owner ORDER BY created_at, id")
                .param("owner", owner)
                .query(MAPPER)
                .list();
    }

    /**
     * Locks the given accounts for the rest of the transaction. Rows are always taken in id order, so two
     * transfers touching the same pair of accounts in opposite directions cannot deadlock.
     */
    public List<Account> lockAll(Collection<UUID> ids) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids)
                .query(MAPPER)
                .list();
    }

    public void setBalance(UUID id, long balanceMinor) {
        jdbc.sql("UPDATE accounts SET balance_minor = :balance WHERE id = :id")
                .param("balance", balanceMinor)
                .param("id", id)
                .update();
    }

    /** Per-currency funding account that is allowed to go negative; the source of every deposit. */
    public Account treasury(String currency) {
        String key = "treasury:" + currency;
        jdbc.sql("INSERT INTO accounts (id, owner_subject, currency, balance_minor, allow_negative, system_key) "
                        + "VALUES (:id, 'system', :currency, 0, TRUE, :key) ON CONFLICT (system_key) DO NOTHING")
                .param("id", UUID.randomUUID())
                .param("currency", currency)
                .param("key", key)
                .update();
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE system_key = :key")
                .param("key", key)
                .query(MAPPER)
                .single();
    }

    public record EntryRow(long id, UUID transactionId, long deltaMinor, java.time.Instant createdAt) {}

    public List<EntryRow> entries(UUID accountId, Long before, int limit) {
        String sql = "SELECT id, transaction_id, delta_minor, created_at FROM ledger_entries WHERE account_id = :a "
                + (before != null ? "AND id < :before " : "")
                + "ORDER BY id DESC LIMIT :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("a", accountId).param("limit", limit);
        if (before != null) {
            spec = spec.param("before", before);
        }
        return spec.query((rs, i) -> new EntryRow(
                rs.getLong("id"),
                rs.getObject("transaction_id", UUID.class),
                rs.getLong("delta_minor"),
                rs.getTimestamp("created_at").toInstant())).list();
    }
}

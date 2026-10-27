-- Reconciliation checks for LedgerFlow. Every query should return zero rows (or 0) on a healthy database.
--   psql -h localhost -U ledger -d ledgerflow -f scripts/audit.sql

\echo '1. Transactions whose legs do not sum to zero (expect 0 rows)'
SELECT transaction_id, SUM(delta_minor) AS imbalance
FROM ledger_entries
GROUP BY transaction_id
HAVING SUM(delta_minor) <> 0;

\echo '2. Accounts whose stored balance differs from the sum of their entries (expect 0 rows)'
SELECT a.id, a.balance_minor AS stored, COALESCE(SUM(e.delta_minor), 0) AS from_entries
FROM accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
GROUP BY a.id, a.balance_minor
HAVING a.balance_minor <> COALESCE(SUM(e.delta_minor), 0);

\echo '3. Customer accounts below zero (expect 0 rows)'
SELECT id, balance_minor FROM accounts WHERE NOT allow_negative AND balance_minor < 0;

\echo '4. Total balance across all accounts, including treasury (expect 0)'
SELECT COALESCE(SUM(balance_minor), 0) AS total_money_in_system FROM accounts;

\echo '5. Transactions without exactly two legs (expect 0 rows)'
SELECT transaction_id, COUNT(*) AS legs
FROM ledger_entries
GROUP BY transaction_id
HAVING COUNT(*) <> 2;

\echo '6. Outbox backlog (events not yet published; normally 0 a few seconds after traffic stops)'
SELECT COUNT(*) AS unpublished FROM outbox_events WHERE published_at IS NULL;

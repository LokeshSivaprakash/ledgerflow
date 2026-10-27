# LedgerFlow walkthrough: launch it, test it, read it

Work through this in order. About 45 minutes the first time.

## 1. Launch

Once it is running, open http://localhost:8080/ for the web UI (sign in with any name). The Concurrency lab tab runs the overdraft race and the retry storm from the browser.

You need Java 21, Maven 3.9+, PostgreSQL 16 (or Docker), plus `curl`, `jq` and `xargs` for the demo script. On Windows use WSL or Git Bash for the scripts.

**Option A: Docker** (starts Postgres and the app)

```bash
docker compose up --build
```

**Option B: local PostgreSQL, no Docker**

```bash
# once: create the role and database the defaults expect
psql -U postgres -c "CREATE ROLE ledger LOGIN PASSWORD 'ledger'"
createdb -U postgres -O ledger ledgerflow

# run the app (the token endpoint is for local demos only)
LEDGER_DEV_TOKEN_ENDPOINT=true mvn spring-boot:run
```

The app is ready when this prints `{"status":"UP",...}`:

```bash
curl localhost:8080/actuator/health
```

Liquibase creates the tables on first start. Open `http://localhost:8080/swagger-ui.html` to see every endpoint.

## 2. Test

**Automated tests (16, against real PostgreSQL)**

```bash
createdb -U postgres ledgerflow_test          # once
mvn test                                      # defaults: localhost:5432, postgres/postgres
```

Override with `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD` if your setup differs.

**End-to-end demo against the running app (23 checks)**

```bash
./scripts/demo.sh
```

It opens accounts, transfers money, retries with the same key, tries to overdraw, tries to read another user's account, then races 20 parallel transfers and 10 parallel retries. Each step prints `PASS` or `FAIL`.

**Reconciliation audit against the database**

```bash
PGPASSWORD=ledger psql -h localhost -U ledger -d ledgerflow -f scripts/audit.sql
```

Every check should return zero rows (or 0). Run it after the demo, and again after you break something on purpose (section 4).

**Do it by hand once**, so the API feels familiar:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/dev/token -H 'Content-Type: application/json' -d '{"subject":"alice"}' | jq -r .token)

curl -s -X POST localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"currency":"USD","initialDepositMinor":10000}'
# make a second account the same way, then:

curl -i -X POST localhost:8080/api/transfers -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: my-first-transfer' -H 'Content-Type: application/json' \
  -d '{"fromAccountId":"<alice id>","toAccountId":"<other id>","amountMinor":2500,"currency":"USD"}'
```

Run the transfer twice and compare the status code and headers.

## 3. Read the code

Follow one request, `POST /api/transfers`, from the top:

| Step | File | What happens |
|---|---|---|
| 1 | `config/SecurityConfig.java` | Spring Security validates the JWT (HS256). No valid token means `401`. The subject becomes the caller's identity. |
| 2 | `transfer/TransferController.java` | Checks the `Idempotency-Key` header (8 to 128 characters) and validates the body (positive amount, 3-letter currency). Maps "replayed" to `200` and "new" to `201`. |
| 3 | `transfer/LedgerService.transfer` | One database transaction around everything below. If anything throws, everything rolls back. |
| 4 | `transfer/IdempotencyRepository.tryClaim` | `INSERT ... ON CONFLICT DO NOTHING` claims the key. If another request holds it, PostgreSQL makes this one wait for that transaction to finish. Then the stored response is replayed, or `422` if the body differs. |
| 5 | `account/AccountRepository.lockAll` | `SELECT ... ORDER BY id FOR UPDATE` locks both accounts, always in id order. This is what prevents overdrafts and deadlocks. |
| 6 | `LedgerService.transfer` | Checks: caller owns the source (else `404`), currencies match, enough funds. |
| 7 | `LedgerService.post` | Inserts one `ledger_transactions` row, two `ledger_entries` (minus and plus), updates both balances, and inserts an `outbox_events` row, all in the same transaction. |
| 8 | `IdempotencyRepository.complete` | Stores the response JSON under the key, then the transaction commits. |
| 9 | `outbox/OutboxPublisher` | A scheduled job reads unpublished events with `FOR UPDATE SKIP LOCKED`, hands them to `EventPublisher`, and marks them published. The default `LoggingEventPublisher` writes them to the app log. |

Errors are `ApiException` objects turned into RFC 7807 responses by `common/GlobalExceptionHandler`. The schema is in `src/main/resources/db/changelog/001-core.sql`; note the `CHECK` constraints, which are a second line of defence behind the Java checks.

### API reference

| Method and path | Purpose | Success | Errors |
|---|---|---|---|
| `POST /api/dev/token` | Mint a token for any subject (dev only) | `200` `{token}` | not registered unless `LEDGER_DEV_TOKEN_ENDPOINT=true` |
| `POST /api/accounts` | Open an account, optionally funded from the treasury | `201` `{id, currency, balanceMinor}` | `400` validation |
| `GET /api/accounts/{id}` | Read your own account | `200` | `404` if missing or not yours |
| `GET /api/accounts/{id}/entries?limit=50&before=<entryId>` | Ledger entries, newest first | `200` `{entries, nextBefore}` | `404` |
| `POST /api/transfers` + `Idempotency-Key` | Move money | `201` new, `200` replay (`Idempotent-Replayed: true`) | `400` bad key, same account or validation; `404` account; `422` `INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `IDEMPOTENCY_KEY_REUSED` |
| `GET /actuator/health`, `/actuator/prometheus` | Health probes and metrics | `200` | |

Amounts are integers in minor units (`2500` is 25.00 USD). Never use floating point for money.

## 4. Learn by breaking it

Each experiment shows why a piece of the design exists. Undo the change after each one.

1. **Remove the row lock.** In `AccountRepository.lockAll`, delete `FOR UPDATE` from the SQL, then run `mvn test -Dtest='LedgerFlowTest#concurrent*+opposing*'`. Tests fail because transfers overdraw or conflict. This is the point of the lock.
2. **Remove the idempotency check.** In `LedgerService.transfer`, change the `if (idempotency.tryClaim(...) == 0)` condition to `if (false)`, rebuild, restart, run `./scripts/demo.sh`. Steps 3, 4 and 8 fail (a retry moves money again, and the 10-way retry storm creates 10 transfers), and the step 5 balance check fails as a knock-on. Then run `audit.sql`: it stays clean, because every duplicate is itself a valid, balanced transaction. The ledger cannot tell a duplicate payment from a real one; only idempotency prevents that. Restore the line afterwards.
3. **Break an entry on purpose** (in `psql`): `UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = '<id>';` then run `audit.sql`. Check 2 flags the account and check 4 shows a total of 1 instead of 0. Subtract the 1 again to repair it. That is reconciliation: the stored balance no longer matches the entries.
4. **Watch the outbox.** Run a transfer and look in the app log for `outbox event type=ledger.transaction.posted`. Stop the app between a transfer and the next poll to see unpublished rows with `SELECT * FROM outbox_events WHERE published_at IS NULL;`.

## 5. Questions to be ready for

- Why store a balance on the account row and also keep entries? What breaks if they disagree, and how would you detect it? (`audit.sql` check 2)
- Why lock accounts in id order? Show the deadlock you would get otherwise.
- What happens if the server crashes right after the transfer commits but before the client gets the response? (The retry with the same key replays the stored result.)
- Why is a failed request (for example insufficient funds) allowed to reuse its idempotency key?
- What delivery guarantee does the outbox give, and what must consumers do about it?
- What would you change to handle 10 times the load? (The treasury row serialises deposits; read replicas for balances; partitioning entries; the Kafka `EventPublisher` is already there; add partitions and consumer groups.)

## 6. Kafka mode (optional)

```bash
docker compose -f docker-compose.yml -f docker-compose.kafka.yml up --build
```

Make a transfer, then read the topic:

```bash
docker compose -f docker-compose.yml -f docker-compose.kafka.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic ledger.transactions --from-beginning
```

Then stop the kafka container (`docker compose ... stop kafka`), make a transfer, and watch the app log show publish failures while `outbox_events` keeps the unpublished row. Start Kafka again and the event arrives. That is the outbox guarantee in action.

## 7. What has and has not been verified

- Verified: all 19 tests pass against PostgreSQL 16 (Kafka delivery tested against an embedded broker), `scripts/demo.sh` passes 23 of 23, `scripts/audit.sql` is clean, and `Dockerfile` plus `docker-compose.yml` ran end to end on Windows with Docker Desktop.
- Web UI: driven end to end in headless Chromium against the jar. The Docker build that bundles it (new Node stage) has not been run yet.
- Not yet run: `docker-compose.kafka.yml` and the GitHub Actions workflow. Expect a possible first-run fix.
- Not built: load-test numbers. Do not quote performance figures until you measure them on your own machine.

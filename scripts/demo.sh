#!/usr/bin/env bash
# Walks through every guarantee LedgerFlow makes and checks the result of each step.
# Needs: curl, jq, xargs. The app must be running with LEDGER_DEV_TOKEN_ENDPOINT=true.
#   ./scripts/demo.sh                      # against http://localhost:8080
#   BASE=http://localhost:9090 ./scripts/demo.sh
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
RUN="$(date +%s)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAIL=0

step()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
check() { # check "name" want got
  if [[ "$2" == "$3" ]]; then PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"
  else FAIL=$((FAIL + 1)); printf '  FAIL  %s (want %s, got %s)\n' "$1" "$2" "$3"; fi
}

token() { curl -sf -X POST "$BASE/api/dev/token" -H 'Content-Type: application/json' -d "{\"subject\":\"$1\"}" | jq -r .token; }

# call TOKEN METHOD PATH [BODY] [IDEMPOTENCY_KEY]  ->  sets CODE, BODY, and writes response headers to $TMP/h
call() {
  local tok="$1" method="$2" path="$3" body="${4:-}" key="${5:-}"
  local args=(-s -D "$TMP/h" -o "$TMP/b" -w '%{http_code}' -X "$method" "$BASE$path")
  [[ -n "$tok" ]] && args+=(-H "Authorization: Bearer $tok")
  [[ -n "$key" ]] && args+=(-H "Idempotency-Key: $key")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  CODE="$(curl "${args[@]}")"
  BODY="$(cat "$TMP/b")"
}

transfer_body() { # from to amount
  printf '{"fromAccountId":"%s","toAccountId":"%s","amountMinor":%s,"currency":"USD"}' "$1" "$2" "$3"
}

balance() { call "$1" GET "/api/accounts/$2"; jq -r .balanceMinor <<<"$BODY"; }

curl -sf "$BASE/actuator/health" >/dev/null || { echo "App is not reachable at $BASE"; exit 2; }
ALICE="$(token "alice-$RUN")"
BOB="$(token "bob-$RUN")"
[[ -n "$ALICE" && "$ALICE" != null ]] || { echo "Could not get a token. Start the app with LEDGER_DEV_TOKEN_ENDPOINT=true"; exit 2; }

step "1. Open accounts (alice funded with 100.00 USD, bob empty)"
call "$ALICE" POST /api/accounts '{"currency":"USD","initialDepositMinor":10000}'; A="$(jq -r .id <<<"$BODY")"
check "alice account created" 201 "$CODE"
call "$BOB" POST /api/accounts '{"currency":"USD","initialDepositMinor":0}'; B="$(jq -r .id <<<"$BODY")"
check "bob account created" 201 "$CODE"

step "2. Transfer 25.00 from alice to bob"
call "$ALICE" POST /api/transfers "$(transfer_body "$A" "$B" 2500)" "demo-$RUN-0001"
check "transfer accepted" 201 "$CODE"
TX1="$(jq -r .transactionId <<<"$BODY")"
check "alice balance" 7500 "$(balance "$ALICE" "$A")"
check "bob balance" 2500 "$(balance "$BOB" "$B")"

step "3. Retry with the same Idempotency-Key: same answer, money moves once"
call "$ALICE" POST /api/transfers "$(transfer_body "$A" "$B" 2500)" "demo-$RUN-0001"
check "replay returns 200" 200 "$CODE"
check "replay header present" true "$(grep -qi '^idempotent-replayed: true' "$TMP/h" && echo true || echo false)"
check "same transaction id" "$TX1" "$(jq -r .transactionId <<<"$BODY")"
check "alice balance unchanged" 7500 "$(balance "$ALICE" "$A")"

step "4. Same key, different amount is rejected"
call "$ALICE" POST /api/transfers "$(transfer_body "$A" "$B" 9999)" "demo-$RUN-0001"
check "status 422" 422 "$CODE"
check "error code" IDEMPOTENCY_KEY_REUSED "$(jq -r .code <<<"$BODY")"

step "5. Overdraft is rejected and nothing moves"
call "$ALICE" POST /api/transfers "$(transfer_body "$A" "$B" 999999)" "demo-$RUN-0002"
check "status 422" 422 "$CODE"
check "error code" INSUFFICIENT_FUNDS "$(jq -r .code <<<"$BODY")"
check "alice balance unchanged" 7500 "$(balance "$ALICE" "$A")"

step "6. Access control"
call "" GET "/api/accounts/$A"
check "no token gives 401" 401 "$CODE"
call "$BOB" GET "/api/accounts/$A"
check "bob reading alice's account gives 404" 404 "$CODE"
call "$BOB" POST /api/transfers "$(transfer_body "$A" "$B" 100)" "demo-$RUN-0003"
check "bob spending alice's money gives 404" 404 "$CODE"

step "7. Race: 20 parallel transfers of 0.10 from an account holding exactly 1.00"
call "$ALICE" POST /api/accounts '{"currency":"USD","initialDepositMinor":100}'; R="$(jq -r .id <<<"$BODY")"
BODYJSON="$(transfer_body "$R" "$B" 10)"
CODES="$(seq 1 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/transfers" \
  -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: race-$RUN-{}" -H 'Content-Type: application/json' -d "$BODYJSON" | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
echo "  status counts: $CODES"
check "exactly 10 succeed" true "$([[ "$CODES" == *" 10 201"* ]] && echo true || echo false)"
check "exactly 10 rejected" true "$([[ "$CODES" == *" 10 422"* ]] && echo true || echo false)"
check "source drained to exactly 0" 0 "$(balance "$ALICE" "$R")"

step "8. Retry storm: 10 parallel requests with one Idempotency-Key"
call "$ALICE" POST /api/accounts '{"currency":"USD","initialDepositMinor":1000}'; S="$(jq -r .id <<<"$BODY")"
STORM="$(seq 1 10 | xargs -P 10 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/transfers" \
  -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: storm-$RUN" -H 'Content-Type: application/json' \
  -d "$(transfer_body "$S" "$B" 100)" | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
echo "  status counts: $STORM"
check "exactly one request created the transfer" true "$([[ "$STORM" == *" 1 201"* ]] && echo true || echo false)"
check "source debited once (10.00 to 9.00)" 900 "$(balance "$ALICE" "$S")"

step "9. Ledger entries, newest first, paged"
call "$ALICE" GET "/api/accounts/$A/entries?limit=2"
check "status 200" 200 "$CODE"
jq -c '.entries[] | {entryId, deltaMinor}' <<<"$BODY" | sed 's/^/  /'

printf '\n%s passed, %s failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]

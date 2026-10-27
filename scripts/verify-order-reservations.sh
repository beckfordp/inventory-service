#!/usr/bin/env bash
set -euo pipefail

# Verifies the bulk order-reservation endpoints end-to-end (track
# order-reservations_20261027, TD-7.1/7.2): brings up Postgres + the real
# service, then drives real HTTP traffic to confirm a bulk reserve is
# all-or-nothing and reports every failed line, a repeated reserve is
# idempotent, the ledger records each line, and release restores stock exactly
# once and is safe to repeat.
#
# Usage: ./scripts/verify-order-reservations.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

BASE=http://localhost:8080
FAILED=0
SBT_PID=""

cleanup() {
  echo
  echo "Cleaning up..."
  [ -n "$SBT_PID" ] && kill "$SBT_PID" >/dev/null 2>&1 || true
  # sbt forks the app JVM; make sure nothing is left holding :8080.
  lsof -ti:8080 2>/dev/null | xargs kill -9 >/dev/null 2>&1 || true
  docker compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 120); do
    status="$(curl -s -o /dev/null -w '%{http_code}' $BASE/health/ready || true)"
    [ "$status" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL: inventory-service did not become ready in time." >&2
  return 1
}

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    echo "   OK: ${label} (got ${actual})"
  else
    echo "   FAIL: ${label} - expected ${expected}, got ${actual}" >&2
    FAILED=1
  fi
}

# POST a JSON body; prints "<body>\n<status>".
post() {
  if [ $# -ge 2 ]; then
    curl -s -w '\n%{http_code}' -X POST "$BASE$1" -H "Content-Type: application/json" -d "$2"
  else
    curl -s -w '\n%{http_code}' -X POST "$BASE$1"
  fi
}

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

levels() {
  curl -s "$BASE/inventorys?sku=$1" | json "'%d/%d' % (d[0]['quantityAvailable'], d[0]['quantityReserved'])"
}

ledger() {
  docker compose exec -T postgres psql -U inventory -d inventory -tA -c \
    "select status, count(*) from order_reservations where order_id = '$1' group by status order by status" | tr '\n' ' ' | sed 's/ $//'
}

uuid() { python3 -c "import uuid; print(uuid.uuid4())"; }

# JSON builders - kept out of "$(...)" so no escaped quotes nest inside a
# command substitution (macOS bash 3.2 misparses those).
stock() { printf '{"sku":"%s","quantityAvailable":%s}' "$1" "$2"; }
item() { printf '{"orderItemId":"%s","sku":"%s","quantity":%s}' "$1" "$2" "$3"; }
order() { printf '{"orderId":"%s","items":[%s]}' "$1" "$2"; }

echo "1. docker compose up -d postgres (fresh volumes)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d --wait postgres
echo "   OK: postgres ready"

echo
echo "2. Starting inventory-service (sbt run) in the background..."
sbt run </dev/null >/tmp/inventory-service-verify-order-reservations.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: inventory-service ready on :8080"

echo
echo "3. Seed stock: A=5, B=1..."
A="verify-a-$(date +%s)"; B="verify-b-$(date +%s)"
stock_a="$(stock "$A" 5)"; stock_b="$(stock "$B" 1)"
check "create A returns 201" "201" "$(post /inventorys "$stock_a" | tail -1)"
check "create B returns 201" "201" "$(post /inventorys "$stock_b" | tail -1)"

ORDER="$(uuid)"; ITEM_A="$(uuid)"; ITEM_B="$(uuid)"; ITEM_X="$(uuid)"

echo
echo "4. Bulk reserve A:2, B:3, unknown:1 -> 409 listing B and unknown, nothing reserved..."
rejected="$(order "$ORDER" "$(item "$ITEM_A" "$A" 2),$(item "$ITEM_B" "$B" 3),$(item "$ITEM_X" no-such-sku 1)")"
resp="$(post /inventorys/order-reservations "$rejected")"
check "status" "409" "$(echo "$resp" | tail -1)"
check "failures" "insufficient_stock,unknown_sku" "$(echo "$resp" | sed '$d' | json "','.join(f['reason'] for f in d['failures'])")"
check "A untouched" "5/0" "$(levels "$A")"
check "no ledger rows" "" "$(ledger "$ORDER")"

echo
echo "5. Bulk reserve A:2, B:1 -> 201, stock moved, ledger active..."
body="$(order "$ORDER" "$(item "$ITEM_A" "$A" 2),$(item "$ITEM_B" "$B" 1)")"
resp="$(post /inventorys/order-reservations "$body")"
check "status" "201" "$(echo "$resp" | tail -1)"
first_body="$(echo "$resp" | sed '$d')"
check "A" "3/2" "$(levels "$A")"
check "B" "0/1" "$(levels "$B")"
check "ledger" "active|2" "$(ledger "$ORDER")"

echo
echo "6. Repeat the same reserve -> 201, same body, no double reserve..."
resp="$(post /inventorys/order-reservations "$body")"
check "status" "201" "$(echo "$resp" | tail -1)"
check "same body" "$first_body" "$(echo "$resp" | sed '$d')"
check "A unchanged" "3/2" "$(levels "$A")"

echo
echo "7. Release -> 204, stock restored, ledger released..."
check "status" "204" "$(post "/inventorys/order-reservations/$ORDER/release" | tail -1)"
check "A" "5/0" "$(levels "$A")"
check "B" "1/0" "$(levels "$B")"
check "ledger" "released|2" "$(ledger "$ORDER")"

echo
echo "8. Release again, unknown and non-UUID -> 204, no change..."
check "repeat" "204" "$(post "/inventorys/order-reservations/$ORDER/release" | tail -1)"
check "A still" "5/0" "$(levels "$A")"
check "unknown" "204" "$(post "/inventorys/order-reservations/$(uuid)/release" | tail -1)"
check "non-UUID" "204" "$(post /inventorys/order-reservations/nope/release | tail -1)"

echo
echo "9. Invalid request -> 400..."
empty="$(order "$(uuid)" "")"
check "empty items" "400" "$(post /inventorys/order-reservations "$empty" | tail -1)"

echo
echo "10. OpenAPI documents both endpoints..."
docs="$(curl -s $BASE/docs/docs.yaml)"
check "reserve documented" "yes" "$(echo "$docs" | grep -q '/inventorys/order-reservations:' && echo yes || echo no)"
check "release documented" "yes" "$(echo "$docs" | grep -q '/inventorys/order-reservations/{orderId}/release:' && echo yes || echo no)"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

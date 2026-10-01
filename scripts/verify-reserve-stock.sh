#!/usr/bin/env bash
set -euo pipefail

# Verifies the POST /inventorys/reservations endpoint end-to-end (track
# reserve-stock_20261001): brings up Postgres + the real service, then drives
# real HTTP traffic through it to confirm a reservation decrements/increments
# the right fields, insufficient stock is rejected with 409 and persists no
# mutation, and an unknown sku is rejected with 404 - not just that the
# unit/integration test suite passes.
#
# Usage: ./scripts/verify-reserve-stock.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FAILED=0
SBT_PID=""

cleanup() {
  echo
  echo "Cleaning up..."
  [ -n "$SBT_PID" ] && kill "$SBT_PID" >/dev/null 2>&1 || true
  docker compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 90); do
    status="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health/ready || true)"
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

echo "1. docker compose up -d (fresh volumes)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: postgres starting"

echo
echo "2. Starting inventory-service (sbt run) in the background..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/inventory-service-verify.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: inventory-service ready on :8080"

echo
echo "3. Create inventory with quantityAvailable=100..."
sku="verify-sku-$(date +%s)"
create_response="$(curl -s -w '\n%{http_code}' -X POST http://localhost:8080/inventorys \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantityAvailable\":100}")"
create_status="$(echo "$create_response" | tail -1)"
create_body="$(echo "$create_response" | sed '$d')"
check "POST /inventorys returns 201" "201" "$create_status"
inventory_id="$(echo "$create_body" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")"

echo
echo "4. Reserve 30 units: decrements available, increments reserved..."
reserve_response="$(curl -s -w '\n%{http_code}' -X POST http://localhost:8080/inventorys/reservations \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantity\":30}")"
reserve_status="$(echo "$reserve_response" | tail -1)"
reserve_body="$(echo "$reserve_response" | sed '$d')"
check "POST /inventorys/reservations returns 200" "200" "$reserve_status"
available="$(echo "$reserve_body" | python3 -c "import json,sys; print(json.load(sys.stdin)['quantityAvailable'])")"
reserved="$(echo "$reserve_body" | python3 -c "import json,sys; print(json.load(sys.stdin)['quantityReserved'])")"
check "quantityAvailable is 70 after reserving 30" "70" "$available"
check "quantityReserved is 30 after reserving 30" "30" "$reserved"

echo
echo "5. Reserve more than available (80, only 70 left): rejected with 409, no mutation..."
conflict_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys/reservations \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantity\":80}")"
check "over-reservation returns 409" "409" "$conflict_status"
after_rejected="$(curl -s "http://localhost:8080/inventorys/${inventory_id}")"
unchanged_available="$(echo "$after_rejected" | python3 -c "import json,sys; print(json.load(sys.stdin)['quantityAvailable'])")"
check "quantityAvailable still 70 after the rejected 80-unit attempt" "70" "$unchanged_available"

echo
echo "6. Reserve against an unknown sku: rejected with 404..."
not_found_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys/reservations \
  -H "Content-Type: application/json" -d '{"sku":"no-such-sku","quantity":1}')"
check "unknown sku returns 404" "404" "$not_found_status"

echo
echo "7. Health and docs endpoints..."
health="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health)"
check "GET /health returns 200" "200" "$health"
docs="$(curl -s -L -o /dev/null -w '%{http_code}' http://localhost:8080/docs)"
check "GET /docs returns 200" "200" "$docs"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

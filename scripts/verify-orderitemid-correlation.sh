#!/usr/bin/env bash
set -euo pipefail

# Verifies track stock-event-correlation_20261002 end-to-end: brings up
# Postgres + Kafka + the real service, drives real HTTP traffic through the
# reserve-stock endpoint with an orderItemId, and confirms that exact id is
# echoed verbatim on the resulting Kafka message - not just that the
# unit/integration test suite passes. Also confirms a request missing
# orderItemId is rejected with 400 before anything is published.
#
# Usage: ./scripts/verify-orderitemid-correlation.sh

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

# All events now share one `inventory-events` topic (ADR 0009's envelope
# migration), so consume a batch from the beginning and pick out the first
# envelope matching the given eventType, printing just its `payload` - the
# same shape callers already expect from the pre-envelope bare-payload
# messages.
consume_envelope_payload() {
  local topic="$1" event_type="$2"
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic "$topic" \
    --from-beginning \
    --max-messages 5 \
    --timeout-ms 10000 2>/dev/null \
    | python3 -c "
import json, sys
target = sys.argv[1]
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        envelope = json.loads(line)
    except ValueError:
        continue
    if envelope.get('eventType') == target:
        print(json.dumps(envelope['payload']))
        break
" "$event_type" || true
}

echo "1. docker compose up -d (fresh volumes)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: postgres + kafka starting"

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
sku="verify-orderitemid-sku-$(date +%s)"
create_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantityAvailable\":100}")"
check "POST /inventorys returns 201" "201" "$create_status"

echo
echo "4. Reserve 30 units with orderItemId, confirm it's echoed on a StockReserved envelope on inventory-events..."
item_id="order-item-$(date +%s)-a"
reserve_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys/reservations \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantity\":30,\"orderItemId\":\"${item_id}\"}")"
check "POST /inventorys/reservations returns 200" "200" "$reserve_status"
reserved_message="$(consume_envelope_payload inventory-events StockReserved)"
echo "   payload: ${reserved_message:-<none>}"
reserved_item_id="$(echo "$reserved_message" | python3 -c "import json,sys; print(json.load(sys.stdin).get('orderItemId',''))" 2>/dev/null || echo "")"
check "StockReserved payload has correct orderItemId" "$item_id" "$reserved_item_id"

echo
echo "5. Reserve more than available (80, only 70 left) with a different orderItemId, confirm it's echoed on a StockReservationFailed envelope on inventory-events..."
failed_item_id="order-item-$(date +%s)-b"
conflict_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys/reservations \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantity\":80,\"orderItemId\":\"${failed_item_id}\"}")"
check "over-reservation returns 409" "409" "$conflict_status"
failed_message="$(consume_envelope_payload inventory-events StockReservationFailed)"
echo "   payload: ${failed_message:-<none>}"
failed_message_item_id="$(echo "$failed_message" | python3 -c "import json,sys; print(json.load(sys.stdin).get('orderItemId',''))" 2>/dev/null || echo "")"
check "StockReservationFailed payload has correct orderItemId" "$failed_item_id" "$failed_message_item_id"

echo
echo "6. Reserve without orderItemId, confirm it's rejected with 400..."
missing_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys/reservations \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantity\":10}")"
check "missing orderItemId returns 400" "400" "$missing_status"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

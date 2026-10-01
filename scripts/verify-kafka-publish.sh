#!/usr/bin/env bash
set -euo pipefail

# Verifies US-5.1 (track kafka-publish_20261001) end-to-end: brings up
# Postgres + Kafka + the real service, drives real HTTP traffic through the
# existing reserve-stock endpoint, and confirms the right Kafka message is
# actually produced on the right topic - not just that the unit/integration
# test suite passes.
#
# Usage: ./scripts/verify-kafka-publish.sh

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

# Consumes up to one message from a topic, printing it (empty if none within
# the timeout). Runs the broker's own console consumer inside the container.
consume_one() {
  local topic="$1"
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic "$topic" \
    --from-beginning \
    --max-messages 1 \
    --timeout-ms 10000 2>/dev/null || true
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
sku="verify-kafka-sku-$(date +%s)"
create_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantityAvailable\":100}")"
check "POST /inventorys returns 201" "201" "$create_status"

echo
echo "4. Reserve 30 units (expect 200) and confirm inventory.stock-reserved..."
reserve_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys/reservations \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantity\":30}")"
check "POST /inventorys/reservations returns 200" "200" "$reserve_status"
reserved_message="$(consume_one inventory.stock-reserved)"
echo "   message: ${reserved_message:-<none>}"
reserved_sku="$(echo "$reserved_message" | python3 -c "import json,sys; print(json.load(sys.stdin).get('sku',''))" 2>/dev/null || echo "")"
reserved_quantity="$(echo "$reserved_message" | python3 -c "import json,sys; print(json.load(sys.stdin).get('quantity',''))" 2>/dev/null || echo "")"
check "inventory.stock-reserved message has correct sku" "$sku" "$reserved_sku"
check "inventory.stock-reserved message has correct quantity" "30" "$reserved_quantity"

echo
echo "5. Reserve more than available (80, only 70 left, expect 409) and confirm inventory.stock-reservation-failed..."
conflict_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/inventorys/reservations \
  -H "Content-Type: application/json" -d "{\"sku\":\"${sku}\",\"quantity\":80}")"
check "over-reservation returns 409" "409" "$conflict_status"
failed_message="$(consume_one inventory.stock-reservation-failed)"
echo "   message: ${failed_message:-<none>}"
failed_sku="$(echo "$failed_message" | python3 -c "import json,sys; print(json.load(sys.stdin).get('sku',''))" 2>/dev/null || echo "")"
failed_quantity="$(echo "$failed_message" | python3 -c "import json,sys; print(json.load(sys.stdin).get('quantity',''))" 2>/dev/null || echo "")"
check "inventory.stock-reservation-failed message has correct sku" "$sku" "$failed_sku"
check "inventory.stock-reservation-failed message has correct quantity" "80" "$failed_quantity"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

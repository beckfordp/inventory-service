#!/usr/bin/env bash
set -euo pipefail

# Verifies inventory-service's AsyncAPI surface after US-21.4 added the
# DispatchConfirmed subscription alongside OrderCancelled:
#   - build-time spec generation produces a document containing both
#     subscriptions;
#   - the live /docs/asyncapi.json route serves both and no retired event
#     types.
#
# Usage: ./scripts/verify-kafka-asyncapi-docs.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

INVENTORY_SERVICE_URL="http://localhost:8080"
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
    status="$(curl -s -o /dev/null -w '%{http_code}' "${INVENTORY_SERVICE_URL}/health" || true)"
    [ "$status" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL: inventory-service did not become ready in time." >&2
  return 1
}

echo "1. sbt asyncApiGenerate (two subscriptions)..."
if sbt asyncApiGenerate; then
  echo "   OK: task succeeded"
else
  echo "FAIL: asyncApiGenerate should succeed with an empty channel list (via the build.sbt override)" >&2
  FAILED=1
fi
SPEC_FILE="target/asyncapi-docs-spec/asyncapi.json"
if [ ! -f "$SPEC_FILE" ]; then
  echo "FAIL: expected a generated spec with two channels" >&2
  FAILED=1
elif ! jq -e '.channels["order-events"] and .channels["fulfilment-events"]' "$SPEC_FILE" >/dev/null; then
  echo "FAIL: generated spec does not contain both subscriptions" >&2
  FAILED=1
else
  echo "   OK: generated spec contains both subscriptions"
fi

echo
echo "2. docker compose up -d (fresh volumes, postgres + kafka)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   waiting for postgres to be healthy..."
for _ in $(seq 1 60); do
  health="$(docker compose ps --format json postgres 2>/dev/null | python3 -c "import json,sys; print(json.load(sys.stdin).get('Health',''))" 2>/dev/null || true)"
  [ "$health" = "healthy" ] && break
  sleep 1
done

echo
echo "3. Starting inventory-service (sbt run)..."
sbt run >/tmp/inventory-service-asyncapi-verify.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: inventory-service ready on :8080"

echo
echo "4. Checking the live raw-spec route (/docs/asyncapi.json)..."
SPEC="$(curl -s "${INVENTORY_SERVICE_URL}/docs/asyncapi.json")"
for expected_event in "OrderCancelled" "DispatchConfirmed"; do
  if echo "$SPEC" | grep -q "$expected_event"; then
    echo "   OK: live spec contains \"${expected_event}\""
  else
    echo "FAIL: live spec is missing \"${expected_event}\"" >&2
    FAILED=1
  fi
done
for retired in "StockReserved" "StockReservationFailed"; do
  if echo "$SPEC" | grep -q "$retired"; then
    echo "FAIL: live spec still mentions retired event ${retired}" >&2
    FAILED=1
  else
    echo "   OK: live spec does not mention \"${retired}\""
  fi
done

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "One or more checks failed — see FAIL lines above." >&2
  exit 1
fi

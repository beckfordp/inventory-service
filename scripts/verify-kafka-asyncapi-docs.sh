#!/usr/bin/env bash
set -euo pipefail

# Verifies inventory-service's AsyncAPI surface after TD-7.5 retired its only
# two events. With AsyncApiChannels empty:
#   - build-time spec/HTML generation is deliberately disabled (build.sbt's
#     asyncApiGenerate override — the AsyncAPI validator rejects a document
#     with no channels), so no spec or HTML files are produced;
#   - the live /docs/asyncapi.json route still serves a 200 with no retired
#     event types in it.
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

echo "1. sbt asyncApiGenerate (empty channel list — should succeed and produce no spec)..."
if sbt asyncApiGenerate; then
  echo "   OK: task succeeded"
else
  echo "FAIL: asyncApiGenerate should succeed with an empty channel list (via the build.sbt override)" >&2
  FAILED=1
fi
if [ -f "target/asyncapi-docs-spec/asyncapi.json" ]; then
  echo "FAIL: expected no generated spec while there are no channels" >&2
  FAILED=1
else
  echo "   OK: no generated spec"
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
echo "4. Checking the live raw-spec route (/docs/asyncapi.json) has no retired events..."
SPEC="$(curl -s "${INVENTORY_SERVICE_URL}/docs/asyncapi.json")"
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

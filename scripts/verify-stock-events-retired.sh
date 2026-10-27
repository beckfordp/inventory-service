#!/usr/bin/env bash
# Live verification for track retire-stock-events_20261027 (TD-7.5).
# Proves against the real local cluster that:
#   - POST /inventorys/reservations is gone (405 - the path falls through to
#     GET /inventorys/{id}'s method-not-allowed);
#   - the bulk reserve/release path still works and is idempotent;
#   - no new records reach the `inventory-events` topic (offset unchanged);
#   - the two stock-event schema-registry artifacts are gone;
#   - /docs/asyncapi.json still serves and names no retired event.
# Requires kubectl context orbstack and a deployed inventory-service.
set -euo pipefail

NAMESPACE="${NAMESPACE:-gluon-local}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:18082}"
REGISTRY_URL="${REGISTRY_URL:-http://apicurio-registry.gluon-local.svc.cluster.local:8080}"
PIDS=()
TMP_DIR="$(mktemp -d)"
cleanup() {
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "== TD-7.5 live verification =="
kubectl -n "$NAMESPACE" port-forward svc/inventory-service 18082:8080 >/dev/null 2>&1 &
PIDS+=("$!")
for _ in $(seq 1 30); do
  curl -sf "$INVENTORY_URL/health/ready" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "$INVENTORY_URL/health/ready" >/dev/null || {
  echo "FAIL: inventory-service never became ready" >&2
  exit 1
}

SKU="$(curl -sf "$INVENTORY_URL/inventorys" | python3 -c '
import json, sys
rows = json.load(sys.stdin)
stocked = [r for r in rows if r["quantityAvailable"] > 0]
if not stocked:
    sys.exit("no inventory row has quantityAvailable > 0")
print(stocked[0]["sku"])
')"
echo "-- using sku $SKU"

echo "-- offset of inventory-events before --"
OFFSET_BEFORE="$(kubectl exec -n "$NAMESPACE" deploy/kafka -- \
  /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
  --topic inventory-events)"
echo "   $OFFSET_BEFORE"

echo "-- old per-item endpoint is gone --"
STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "$INVENTORY_URL/inventorys/reservations" -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$SKU\",\"quantity\":1,\"orderItemId\":\"verify-item\"}")"
[ "$STATUS" = "405" ] || {
  echo "FAIL: expected 405 for the retired endpoint, got $STATUS" >&2
  exit 1
}
echo "   405 (method not allowed)"

ORDER_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
ITEM_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
BODY="{\"orderId\":\"$ORDER_ID\",\"items\":[{\"orderItemId\":\"$ITEM_ID\",\"sku\":\"$SKU\",\"quantity\":1}]}"

echo "-- bulk reserve still works --"
STATUS="$(curl -s -o "$TMP_DIR/reserve.json" -w '%{http_code}' -X POST \
  "$INVENTORY_URL/inventorys/order-reservations" \
  -H 'Content-Type: application/json' -d "$BODY")"
[ "$STATUS" = "201" ] || {
  echo "FAIL: expected 201 from bulk reserve, got $STATUS" >&2
  cat "$TMP_DIR/reserve.json" >&2
  exit 1
}
echo "   201"

echo "-- repeating the same orderId is idempotent --"
STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "$INVENTORY_URL/inventorys/order-reservations" \
  -H 'Content-Type: application/json' -d "$BODY")"
[ "$STATUS" = "201" ] || {
  echo "FAIL: expected 201 on replay, got $STATUS" >&2
  exit 1
}
echo "   201"

echo "-- release is idempotent (204 twice) --"
for _ in 1 2; do
  STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$INVENTORY_URL/inventorys/order-reservations/$ORDER_ID/release")"
  [ "$STATUS" = "204" ] || {
    echo "FAIL: expected 204 on release, got $STATUS" >&2
    exit 1
  }
done
echo "   204, 204"

echo "-- no new inventory-events records --"
OFFSET_AFTER="$(kubectl exec -n "$NAMESPACE" deploy/kafka -- \
  /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
  --topic inventory-events)"
echo "   $OFFSET_AFTER"
[ "$OFFSET_AFTER" = "$OFFSET_BEFORE" ] || {
  echo "FAIL: inventory-events advanced after a reserve/release that should publish nothing" >&2
  exit 1
}

echo "-- retired registry artifacts are gone --"
ARTIFACTS="$(curl -sf "$REGISTRY_URL/apis/registry/v3/groups/gluon/artifacts?limit=100")"
for retired in inventory-events-StockReserved inventory-events-StockReservationFailed; do
  if echo "$ARTIFACTS" | grep -q "$retired"; then
    echo "FAIL: registry still contains $retired" >&2
    exit 1
  fi
done
echo "   neither artifact present"

echo "-- /docs/asyncapi.json serves without the retired events --"
DOCS="$(curl -sf "$INVENTORY_URL/docs/asyncapi.json")"
for retired in StockReserved StockReservationFailed; do
  if echo "$DOCS" | grep -q "$retired"; then
    echo "FAIL: asyncapi docs still mention $retired" >&2
    exit 1
  fi
done
echo "   clean"

echo "All TD-7.5 live checks passed."

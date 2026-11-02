#!/usr/bin/env bash
# Live verification for Checkout Journey 7's shipped ledger guard (US-21.4).
#
# Starts throwaway Postgres and Kafka plus inventory-service, then proves:
#   - DispatchConfirmed moves active lines to `shipped` and decrements
#     quantity_reserved only;
#   - OrderCancelled after shipping releases nothing and flags
#     cancel_after_dispatch;
#   - DispatchConfirmed after a release flags dispatch_after_release.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SERVICE_PORT="${SERVICE_PORT:-18082}"
METRICS_PORT="${METRICS_PORT:-19092}"
PG_PORT="${PG_PORT:-55434}"
KAFKA_PORT="${KAFKA_PORT:-59094}"
PG_CONTAINER="inventory-verify-postgres-$$"
KAFKA_CONTAINER="inventory-verify-kafka-$$"
SERVICE_LOG="$(mktemp -t inventory-verify-log.XXXXXX)"
SBT_PID=""

cleanup() {
  if [ -n "$SBT_PID" ]; then
    pkill -P "$SBT_PID" 2>/dev/null || true
    kill "$SBT_PID" 2>/dev/null || true
    wait "$SBT_PID" 2>/dev/null || true
  fi
  docker rm -f "$PG_CONTAINER" "$KAFKA_CONTAINER" >/dev/null 2>&1 || true
  rm -f "$SERVICE_LOG"
}
trap cleanup EXIT

uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }

psql_query() {
  docker exec "$PG_CONTAINER" psql -U inventory -d inventory -tAc "$1"
}

produce() {
  local topic="$1" payload="$2"
  printf '%s\n' "$payload" | docker exec -i "$KAFKA_CONTAINER" \
    /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic "$topic" >/dev/null
}

dispatch_envelope() {
  local order_id="$1" tracking="$2" id
  id="$(uuid)"
  printf '{"eventId":"%s","eventType":"DispatchConfirmed","timestamp":"2026-11-02T10:00:00Z","source":"verify-shipped-guard","correlationId":"%s","payload":{"orderId":"%s","trackingNo":"%s"}}' \
    "$id" "$id" "$order_id" "$tracking"
}

cancel_envelope() {
  local order_id="$1" id
  id="$(uuid)"
  printf '{"eventId":"%s","eventType":"OrderCancelled","timestamp":"2026-11-02T10:00:00Z","source":"verify-shipped-guard","correlationId":"%s","payload":{"orderId":"%s","customerId":"verify-customer","timestamp":"2026-11-02T10:00:00Z","reason":"customer"}}' \
    "$id" "$id" "$order_id"
}

wait_for_shipped() {
  local order_id="$1"
  for _ in $(seq 1 60); do
    local rows
    rows="$(curl -sf "http://localhost:$SERVICE_PORT/inventorys/order-reservations?orderId=$order_id&status=shipped" || true)"
    if printf '%s' "$rows" | grep -q '"status":"shipped"'; then return 0; fi
    sleep 1
  done
  echo "FAIL: order $order_id never reached shipped" >&2
  echo "rows: $(curl -sS "http://localhost:$SERVICE_PORT/inventorys/order-reservations?orderId=$order_id" || true)" >&2
  grep -iE 'Failed to decode|Exception|reconciliation|shipped' "$SERVICE_LOG" | tail -30 >&2 || true
  return 1
}

wait_for_reason() {
  local order_id="$1" expected="$2"
  for _ in $(seq 1 60); do
    local reason
    reason="$(psql_query "select reason from inventory_reconciliation where order_id = '$order_id'" || true)"
    if [ "$reason" = "$expected" ]; then return 0; fi
    sleep 1
  done
  echo "FAIL: order $order_id reconciliation reason was not '$expected'" >&2
  return 1
}

levels() {
  local sku="$1"
  curl -sf "http://localhost:$SERVICE_PORT/inventorys?sku=$sku" |
    python3 -c 'import json,sys; a=json.load(sys.stdin)[0]; print("{},{}".format(a["quantityAvailable"], a["quantityReserved"]))' 
}

echo "== US-21.4 shipped ledger live verification =="
docker run -d --rm --name "$PG_CONTAINER" \
  -p "$PG_PORT:5432" \
  -e POSTGRES_DB=inventory -e POSTGRES_USER=inventory \
  -e POSTGRES_PASSWORD=inventory postgres:16-alpine >/dev/null
docker run -d --rm --name "$KAFKA_CONTAINER" \
  -p "$KAFKA_PORT:29092" \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:29092 \
  -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://localhost:9092,EXTERNAL://localhost:$KAFKA_PORT" \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk apache/kafka:3.8.0 >/dev/null

for _ in $(seq 1 90); do
  docker exec "$PG_CONTAINER" psql -U inventory -d inventory -c 'select 1' >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$PG_CONTAINER" psql -U inventory -d inventory -c 'select 1' >/dev/null 2>&1 || { echo "FAIL: Postgres"; exit 1; }
for _ in $(seq 1 60); do
  docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-broker-api-versions.sh \
    --bootstrap-server localhost:9092 >/dev/null 2>&1 && break
  sleep 1
done

echo "-- starting inventory-service --"
POSTGRES_HOST=localhost POSTGRES_PORT="$PG_PORT" \
  POSTGRES_DB=inventory POSTGRES_USER=inventory POSTGRES_PASSWORD=inventory \
  KAFKA_BOOTSTRAP_SERVERS="localhost:$KAFKA_PORT" \
  INVENTORY_SERVICE_PORT="$SERVICE_PORT" \
  INVENTORY_SERVICE_METRICS_PORT="$METRICS_PORT" \
  sbt -batch run >"$SERVICE_LOG" 2>&1 </dev/null &
SBT_PID=$!

for _ in $(seq 1 120); do
  curl -sf "http://localhost:$SERVICE_PORT/health/ready" >/dev/null 2>&1 && break
  kill -0 "$SBT_PID" 2>/dev/null || { echo "FAIL: service exited" >&2; tail -40 "$SERVICE_LOG" >&2; exit 1; }
  sleep 1
done
curl -sf "http://localhost:$SERVICE_PORT/health/ready" >/dev/null || {
  echo "FAIL: service not ready" >&2; tail -40 "$SERVICE_LOG" >&2; exit 1
}

SKU="verify-sku-$(uuid)"
curl -sS -o /dev/null -w '%{http_code}' -X POST \
  "http://localhost:$SERVICE_PORT/inventorys" -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$SKU\",\"quantityAvailable\":10}" | grep -q 201 || {
  echo "FAIL: seed inventory" >&2; exit 1
}

reserve() {
  local order_id="$1" item_id
  item_id="$(uuid)"
  curl -sS -o /dev/null -w '%{http_code}' -X POST \
    "http://localhost:$SERVICE_PORT/inventorys/order-reservations" \
    -H 'Content-Type: application/json' \
    -d "{\"orderId\":\"$order_id\",\"items\":[{\"orderItemId\":\"$item_id\",\"sku\":\"$SKU\",\"quantity\":2}]}"
}

echo "-- dispatch ships the reservation --"
SHIP_ORDER="$(uuid)"
[ "$(reserve "$SHIP_ORDER")" = "201" ] || { echo "FAIL: reserve ship order" >&2; exit 1; }
produce fulfilment-events "$(dispatch_envelope "$SHIP_ORDER" TRK-VERIFY)"
wait_for_shipped "$SHIP_ORDER"
[ "$(levels "$SKU")" = "8,0" ] || { echo "FAIL: levels after ship $(levels "$SKU")" >&2; exit 1; }
echo "   OK: 2 units shipped; available 8, reserved 0"

echo "-- cancel after dispatch releases nothing and flags reconciliation --"
produce order-events "$(cancel_envelope "$SHIP_ORDER")"
wait_for_reason "$SHIP_ORDER" "cancel_after_dispatch"
[ "$(levels "$SKU")" = "8,0" ] || { echo "FAIL: cancel returned stock: $(levels "$SKU")" >&2; exit 1; }
echo "   OK: cancel_after_dispatch flagged, stock unchanged"

echo "-- dispatch after release flags reconciliation --"
RELEASE_ORDER="$(uuid)"
[ "$(reserve "$RELEASE_ORDER")" = "201" ] || { echo "FAIL: reserve release order" >&2; exit 1; }
curl -sS -o /dev/null -w '%{http_code}' -X POST \
  "http://localhost:$SERVICE_PORT/inventorys/order-reservations/$RELEASE_ORDER/release" |
  grep -q 204 || { echo "FAIL: release" >&2; exit 1; }
produce fulfilment-events "$(dispatch_envelope "$RELEASE_ORDER" TRK-VERIFY-2)"
wait_for_reason "$RELEASE_ORDER" "dispatch_after_release"
[ "$(levels "$SKU")" = "8,0" ] || { echo "FAIL: levels after release $(levels "$SKU")" >&2; exit 1; }
echo "   OK: dispatch_after_release flagged; stock returned once"

echo
echo "All US-21.4 live checks passed."

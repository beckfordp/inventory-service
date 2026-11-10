#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Live verification for TD-22: inventory-service publishes no events, so its
# outbox table, Debezium connector, cleanup CronJob and `inventory-events`
# topic are gone. Also confirms the retired registry/AsyncAPI names stay
# absent and the service still serves its AsyncAPI subscription docs.
set -euo pipefail

NAMESPACE="${NAMESPACE:-gluon-local}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:18082}"
KAFKA_CONNECT_URL="${KAFKA_CONNECT_URL:-http://localhost:18083}"
FAILED=0
PIDS=()
cleanup() {
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

fail() { echo "FAIL: $1" >&2; FAILED=1; }

echo "== TD-22 live verification =="
kubectl -n "$NAMESPACE" port-forward svc/inventory-service 18082:8080 >/dev/null 2>&1 &
PIDS+=("$!")
kubectl -n "$NAMESPACE" port-forward svc/kafka-connect 18083:8083 >/dev/null 2>&1 &
PIDS+=("$!")
for _ in $(seq 1 30); do
  curl -sf "$INVENTORY_URL/health/ready" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "$INVENTORY_URL/health/ready" >/dev/null || {
  echo "FAIL: inventory-service never became ready" >&2
  exit 1
}

echo "-- retired connector is gone --"
CODE="$(curl -s -o /dev/null -w '%{http_code}' \
  "$KAFKA_CONNECT_URL/connectors/inventory-outbox-connector")"
[ "$CODE" = "404" ] && echo "   OK: 404" || fail "connector still present (HTTP $CODE)"

echo "-- retired cleanup CronJob is gone --"
if kubectl -n "$NAMESPACE" get cronjob inventory-outbox-cleanup >/dev/null 2>&1; then
  fail "inventory-outbox-cleanup CronJob still exists"
else
  echo "   OK: absent"
fi

echo "-- retired topic is gone --"
if kubectl exec -n "$NAMESPACE" deploy/kafka -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic inventory-events >/dev/null 2>&1; then
  fail "inventory-events topic still exists"
else
  echo "   OK: absent"
fi

echo "-- retired outbox table is gone --"
TABLE="$(kubectl exec -n "$NAMESPACE" deploy/inventory-postgres -- \
  psql -U inventory -d inventory -tAc "select to_regclass('public.outbox_events')" | tr -d '[:space:]')"
[ -z "$TABLE" ] && echo "   OK: absent" || fail "outbox_events table still exists"

echo "-- retired registry artifacts are gone --"
REGISTRY_URL="${REGISTRY_URL:-http://apicurio-registry.gluon-local.svc.cluster.local:8080}"
for id in inventory-events-StockReserved inventory-events-StockReservationFailed; do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' \
    "$REGISTRY_URL/apis/registry/v3/groups/gluon/artifacts/$id")"
  [ "$CODE" = "404" ] && echo "   OK: $id absent" || fail "$id still present (HTTP $CODE)"
done

echo "-- AsyncAPI docs do not name the retired events --"
BODY="$(curl -sf "$INVENTORY_URL/docs/asyncapi.json")"
for retired in StockReserved StockReservationFailed; do
  ! echo "$BODY" | grep -q "$retired" &&
    echo "   OK: $retired absent" || fail "$retired still documented"
done

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

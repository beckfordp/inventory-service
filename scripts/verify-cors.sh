#!/usr/bin/env bash
set -euo pipefail

# Verifies CORS support end-to-end (track cors_20261010, TD-1.3): brings up
# Postgres + Kafka and a real sbt run instance, drives real HTTP traffic
# through it to confirm Access-Control-Allow-Origin on a plain GET and the
# expected Allow-Methods/Allow-Headers on an OPTIONS preflight - not just
# that the test suite passes. Also serves as TD-1.3's "confirm affected"
# step: inventory-service wasn't independently browser-confirmed to lack
# CORS before this track.
#
# Usage: ./scripts/verify-cors.sh

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

check_contains() {
  local label="$1" needle="$2" haystack="$3"
  if echo "$haystack" | grep -qi "$needle"; then
    echo "   OK: ${label} (contains '${needle}')"
  else
    echo "   FAIL: ${label} - expected to contain '${needle}', got '${haystack}'" >&2
    FAILED=1
  fi
}

echo "1. docker compose up -d (fresh volumes)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: postgres/kafka starting"

echo
echo "2. Starting inventory-service (sbt run) in the background..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run >/tmp/inventory-service-verify-cors.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: inventory-service ready on :8080"

echo
echo "3. GET /inventorys with an Origin header receives Access-Control-Allow-Origin..."
get_headers="$(curl -s -D - -o /dev/null -H 'Origin: http://example.com' http://localhost:8080/inventorys)"
get_status="$(echo "$get_headers" | head -1 | tr -d '\r' | awk '{print $2}')"
check "GET /inventorys returns 200" "200" "$get_status"
check_contains "GET /inventorys sends Access-Control-Allow-Origin" "access-control-allow-origin" "$get_headers"
credentials_count="$(echo "$get_headers" | grep -ci 'access-control-allow-credentials' || true)"
check "GET /inventorys omits Access-Control-Allow-Credentials" "0" "$credentials_count"

echo
echo "4. OPTIONS preflight for POST /inventorys receives the expected allow-methods/headers..."
preflight_headers="$(curl -s -D - -o /dev/null -X OPTIONS \
  -H 'Origin: http://example.com' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Content-Type' \
  http://localhost:8080/inventorys)"
preflight_status="$(echo "$preflight_headers" | head -1 | tr -d '\r' | awk '{print $2}')"
check "OPTIONS /inventorys returns a success status" "200" "$preflight_status"
check_contains "OPTIONS /inventorys allows POST" "post" "$preflight_headers"
check_contains "OPTIONS /inventorys allows Content-Type" "content-type" "$preflight_headers"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi

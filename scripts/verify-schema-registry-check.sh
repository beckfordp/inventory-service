#!/usr/bin/env bash
set -euo pipefail

# Verifies inventory-service's schemaRegistryCheck after TD-7.5 retired its
# only two events (StockReserved/StockReservationFailed) and their registry
# artifacts. Mirrors the other services' scripts: runs the real
# AsyncApiChannels registrations through the sbt task against the real local
# Apicurio instance (gluon's infra/k8s/local-infra), under the shared "gluon"
# group.
#
# Proves: the task succeeds with an empty channel list (a no-op); the retired
# artifacts are gone from the registry; a second run is still a no-op.
#
# Requires GITHUB_ACTOR/GITHUB_TOKEN (read:packages) to resolve
# sbt-purekafka-docs from GitHub Packages - derived here from `gh auth
# token` if not already set.
#
# Usage: ./scripts/verify-schema-registry-check.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export GITHUB_ACTOR="${GITHUB_ACTOR:-beckfordp}"
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"

FAILED=0

echo "1. First run (empty channel list - should be a successful no-op)..."
if sbt --error schemaRegistryCheck; then
  echo "   OK: first run succeeded"
else
  echo "FAIL: first run should have succeeded" >&2
  FAILED=1
fi

echo
echo "2. Checking the registry no longer has the retired stock-event artifacts..."
ARTIFACTS_URL="http://apicurio-registry.gluon-local.svc.cluster.local:8080/apis/registry/v3/groups/gluon/artifacts?limit=100"
RESPONSE="$(curl -s "$ARTIFACTS_URL")"
for retired in "inventory-events-StockReserved" "inventory-events-StockReservationFailed"; do
  if echo "$RESPONSE" | grep -q "$retired"; then
    echo "FAIL: registry still contains retired artifact: ${retired}" >&2
    FAILED=1
  else
    echo "   OK: registry does not contain \"${retired}\""
  fi
done

echo
echo "3. Second, identical run (should still be a no-op, not an error)..."
if sbt --error schemaRegistryCheck; then
  echo "   OK: second run succeeded"
else
  echo "FAIL: second identical run should have succeeded" >&2
  FAILED=1
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "One or more checks failed - see FAIL lines above." >&2
  exit 1
fi

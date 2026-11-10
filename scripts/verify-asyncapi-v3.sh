#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Verifies asyncapi-v3_20261101 (gluon TD-19.3): with purekafka 0.6.0 the
# AsyncAPI document is 3.0.0 and documents its two subscriptions
# (OrderCancelled, DispatchConfirmed); schemaRegistryCheck still passes
# against gluon's restored registry. build.sbt disables HTML generation
# (asyncApiGenerate is overridden), so only the spec is generated and checked
# here.
#   scripts/verify-asyncapi-v3.sh   (needs the local k8s Apicurio)
set -euo pipefail
cd "$(dirname "$0")/.."

export GITHUB_ACTOR="${GITHUB_ACTOR:-beckfordp}"
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
SPEC="target/asyncapi-docs-spec/asyncapi.json"
mkdir -p target

echo "== tests, formatting, generated spec =="
sbt -batch -Dsbt.supershell=false scalafmtCheck Test/scalafmtCheck test asyncApiGenerateSpec \
  < /dev/null > target/verify-asyncapi-v3-sbt.log 2>&1 \
  || { echo "FAIL: sbt (see target/verify-asyncapi-v3-sbt.log)"; exit 1; }
echo "ok   tests, scalafmt, asyncApiGenerateSpec"

[ "$(jq -r .asyncapi "$SPEC")" = "3.0.0" ] || { echo "FAIL: spec is not 3.0.0"; exit 1; }
CHANNELS="$(jq -r '[.channels | keys[]] | sort | join(",")' "$SPEC")"
OPERATIONS="$(jq -r '[.operations | keys[]] | sort | join(",")' "$SPEC")"
[ "$CHANNELS" = "fulfilment-events,order-events" ] || { echo "FAIL: channels are $CHANNELS"; exit 1; }
[ "$OPERATIONS" = "fulfilment-events,order-events" ] || { echo "FAIL: operations are $OPERATIONS"; exit 1; }
echo "ok   spec is 3.0.0 with both subscriptions"

./scripts/verify-schema-registry-check.sh < /dev/null > target/verify-asyncapi-v3-registry.log 2>&1 \
  || { echo "FAIL: verify-schema-registry-check.sh (see target/verify-asyncapi-v3-registry.log)"; exit 1; }
echo "ok   schemaRegistryCheck against gluon's restored registry"

echo "PASS: AsyncAPI 3.0 docs verified"

#!/usr/bin/env bash
set -euo pipefail

# Verifies the GET /inventorys list/filter-by-sku endpoint, added for
# gshop's admin-screen_20261010 track's inventory-management feature.
# Runs the full sbt test suite (route, in-memory store, and
# testcontainers-backed Postgres store tests all cover the new endpoint).
#
# Usage: ./scripts/verify-list-endpoint.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "== inventory-service: GET /inventorys list/filter verification =="
sbt -batch test

echo "All checks passed."

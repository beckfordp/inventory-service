#!/usr/bin/env bash
set -euo pipefail

# Verifies the REST docs convention end-to-end (track rest-docs_20261101,
# gluon TD-20.6): brings up Postgres and a real sbt run instance, fetches the
# served OpenAPI spec at /docs/docs.yaml, and checks it carries every
# endpoint's summary/description/tag, the parameter and error descriptions,
# DTO field descriptions and body examples, and that each endpoint lists only
# the error statuses it can return - not just that InventoryDocsSuite's
# in-process Docs.undocumented check passes.
#
# Requires: sbt, Docker, python3 with PyYAML, GITHUB_TOKEN/GITHUB_ACTOR set
# (purerestlib resolution).
#
# Usage: ./scripts/verify-rest-docs.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SBT_PID=""
SPEC="$(mktemp)"

cleanup() {
  echo
  echo "Cleaning up..."
  if [ -n "$SBT_PID" ]; then
    pkill -9 -P "$SBT_PID" >/dev/null 2>&1 || true
    kill -9 "$SBT_PID" >/dev/null 2>&1 || true
  fi
  docker compose down -v >/dev/null 2>&1 || true
  rm -f "$SPEC"
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 120); do
    status="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health/ready || true)"
    [ "$status" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL: inventory-service did not become ready in time." >&2
  return 1
}

echo "1. docker compose up -d postgres (fresh volumes)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d --wait postgres
echo "   OK: postgres ready"

echo
echo "2. Starting inventory-service (sbt run) in the background..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run </dev/null >/tmp/inventory-service-verify-rest-docs.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: inventory-service ready on :8080"

echo
echo "3. GET /docs/docs.yaml..."
status="$(curl -s -o "$SPEC" -w '%{http_code}' http://localhost:8080/docs/docs.yaml)"
[ "$status" = "200" ] || { echo "FAIL: /docs/docs.yaml returned $status" >&2; exit 1; }
echo "   OK: served (200)"

echo
echo "4. Checking the spec..."
python3 -I - "$SPEC" <<'PY'
import sys, yaml

spec = yaml.safe_load(open(sys.argv[1]))
paths, schemas = spec["paths"], spec["components"]["schemas"]
failed = False

def check(label, ok):
    global failed
    print(("   OK: " if ok else "   FAIL: ") + label, file=sys.stdout if ok else sys.stderr)
    failed |= not ok

# A body is documented if its media type carries an example, or its schema
# (inline or via a component $ref) does.
def has_example(container):
    for media in container.get("content", {}).values():
        if "example" in media:
            return True
        schema = media.get("schema", {})
        if "example" in schema:
            return True
        ref = schema.get("$ref", "").rsplit("/", 1)[-1]
        if ref and "example" in schemas.get(ref, {}):
            return True
    return False

def params(op):
    return {p["name"]: p for p in op.get("parameters", [])}

# Statuses each endpoint may list (success + its narrowed errors; tapir adds
# 400 for undecodable inputs on its own).
allowed = {
    ("/inventorys", "post"): {"201", "400", "409"},
    ("/inventorys", "get"): {"200"},
    ("/inventorys/{id}", "get"): {"200", "400", "404"},
    ("/inventorys/{id}", "patch"): {"200", "400", "404"},
    ("/inventorys/{id}", "put"): {"200", "400", "404"},
    ("/inventorys/{id}", "delete"): {"204", "400", "404"},
    ("/inventorys/order-reservations", "post"): {"201", "400", "409"},
    ("/inventorys/order-reservations/{orderId}/release", "post"): {"204"},
    ("/health", "get"): {"200"},
    ("/health/ready", "get"): {"200", "503"},
}
for (path, method), statuses in allowed.items():
    op = paths.get(path, {}).get(method)
    name = f"{method.upper()} {path}"
    if op is None:
        check(f"{name} documented", False)
        continue
    check(f"{name} has summary/description/tag",
          bool(op.get("summary")) and bool(op.get("description")) and bool(op.get("tags")))
    for p in op.get("parameters", []):
        check(f"{name} parameter '{p['name']}' described with example",
              bool(p.get("description")) and ("example" in p or "example" in p.get("schema", {})))
    responses = op.get("responses", {})
    check(f"{name} lists only {sorted(statuses)} (got {sorted(responses)})",
          set(responses) <= statuses)
    for code, r in responses.items():
        if code.startswith("2"):
            continue
        check(f"{name} {code} described", bool(r.get("description")))

# Named parameters: presence, not only per-parameter quality, so a dropped
# description/example or a missing parameter fails.
check("GET /inventorys describes query 'sku'",
      "sku" in params(paths["/inventorys"]["get"]))
for method in ("get", "patch", "put", "delete"):
    check(f"{method.upper()} /inventorys/{{id}} describes path 'id'",
          "id" in params(paths["/inventorys/{id}"][method]))
check("POST release describes path 'orderId'",
      "orderId" in params(paths["/inventorys/order-reservations/{orderId}/release"]["post"]))

create = paths["/inventorys"]["post"]
check("POST /inventorys lists 400/409", {"400", "409"} <= set(create["responses"]))
check("POST /inventorys request body has an example",
      has_example(create.get("requestBody", {})))
check("POST /inventorys 201 has an example body",
      has_example(create["responses"]["201"]))
check("POST /inventorys 409 has an example body",
      has_example(create["responses"].get("409", {})))

get_one = paths["/inventorys/{id}"]["get"]
check("GET /inventorys/{id} lists 404", "404" in get_one["responses"])
check("GET /inventorys/{id} 200 has an example body",
      has_example(get_one["responses"]["200"]))
check("GET /inventorys/{id} 404 has an example body",
      has_example(get_one["responses"].get("404", {})))

list_op = paths["/inventorys"]["get"]
check("GET /inventorys 200 has an example body",
      has_example(list_op["responses"]["200"]))

for method in ("patch", "put"):
    op = paths["/inventorys/{id}"][method]
    check(f"{method.upper()} /inventorys/{{id}} request body has an example",
          has_example(op.get("requestBody", {})))
    check(f"{method.upper()} /inventorys/{{id}} 200 has an example body",
          has_example(op["responses"]["200"]))

reserve = paths["/inventorys/order-reservations"]["post"]
check("POST order-reservations lists 400/409",
      {"400", "409"} <= set(reserve["responses"]))
check("POST order-reservations request body has an example",
      has_example(reserve.get("requestBody", {})))
check("POST order-reservations 201 has an example body",
      has_example(reserve["responses"]["201"]))
check("POST order-reservations 409 has an example body",
      has_example(reserve["responses"].get("409", {})))
check("GET /health/ready lists 503",
      "503" in paths["/health/ready"]["get"]["responses"])

for name in ["CreateInventoryRequest", "UpdateInventoryRequest",
             "InventoryResponse", "ErrorResponse", "ReserveOrderRequest",
             "ReserveOrderItemRequest", "OrderReservationResponse",
             "ReservationLineResponse", "ReservationRejectedResponse",
             "ReservationFailureResponse"]:
    props = schemas.get(name, {}).get("properties", {})
    # A field that only references another component is described by it
    # (tapir folds a single-use field's description into the component).
    def described(s):
        ref = s.get("$ref", "").rsplit("/", 1)[-1]
        return bool(s.get("description")) or bool(ref and schemas.get(ref, {}).get("description"))
    missing = [f for f, s in props.items() if not described(s)]
    check(f"schema {name} fields all described"
          + (f" (missing: {missing}: {[props[f] for f in missing]})" if missing else ""),
          bool(props) and not missing)

sys.exit(1 if failed else 0)
PY

echo
echo "All checks passed."

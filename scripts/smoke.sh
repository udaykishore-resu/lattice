#!/usr/bin/env bash
# End-to-end smoke test against a running Lattice (default http://localhost:8080).
set -euo pipefail
BASE="${1:-http://localhost:8080}"
KEY_HEADER=()
[[ -n "${LATTICE_API_KEY:-}" ]] && KEY_HEADER=(-H "x-lattice-key: ${LATTICE_API_KEY}")

echo "== health";  curl -fsS "$BASE/health"; echo
echo "== graphs";  curl -fsS "${KEY_HEADER[@]}" "$BASE/api/graphs" | python3 -m json.tool | head -40
echo "== node search: account"
curl -fsS "${KEY_HEADER[@]}" "$BASE/api/nodes/search?q=account" | python3 -m json.tool

echo "== execute credit-decision (approve path)"
RESP=$(curl -fsS "${KEY_HEADER[@]}" -H 'content-type: application/json' \
  -d '{"seed":{"applicantId":"good-42","requestedAmount":5000,"statedIncome":90000},"idempotencyKey":"smoke-good-42","correlationId":"smoke"}' \
  "$BASE/api/graphs/demos/credit-decision/execute")
echo "$RESP" | python3 -m json.tool
EXEC_ID=$(echo "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["executionId"])')

echo "== execute again with same idempotency key (must return same executionId)"
curl -fsS "${KEY_HEADER[@]}" -H 'content-type: application/json' \
  -d '{"seed":{"applicantId":"good-42","requestedAmount":5000,"statedIncome":90000},"idempotencyKey":"smoke-good-42"}' \
  "$BASE/api/graphs/demos/credit-decision/execute" | python3 -c "import sys,json;r=json.load(sys.stdin);assert r['executionId']=='$EXEC_ID', r;print('idempotent ok')"

echo "== execute credit-decision (decline path)"
curl -fsS "${KEY_HEADER[@]}" -H 'content-type: application/json' \
  -d '{"seed":{"applicantId":"bad-7","requestedAmount":20000,"statedIncome":30000}}' \
  "$BASE/api/graphs/demos/credit-decision/execute" | python3 -m json.tool

echo "== execution lookup"
curl -fsS "${KEY_HEADER[@]}" "$BASE/api/executions/demos/$EXEC_ID" | python3 -c 'import sys,json;r=json.load(sys.stdin);print(r["status"], [n["id"]+":"+n["status"] for n in r["nodes"]])'

echo "== recent executions"
curl -fsS "${KEY_HEADER[@]}" "$BASE/api/graphs/demos/credit-decision/executions?limit=5" | python3 -m json.tool
echo "smoke ok"

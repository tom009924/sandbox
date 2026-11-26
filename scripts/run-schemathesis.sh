#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

SERVER_URL="${SERVER_URL:-http://127.0.0.1:8082}"
OPENAPI_SPEC="${OPENAPI_SPEC:-docs/dist/openapi.yaml}"
SCHEMATHESIS_CHECKS="${SCHEMATHESIS_CHECKS:-not_a_server_error}"
SCHEMATHESIS_MAX_EXAMPLES="${SCHEMATHESIS_MAX_EXAMPLES:-20}"
TIMESTAMP="$(date +%s)"
FUZZ_USERNAME="${FUZZ_USERNAME:-fuzz-user-${TIMESTAMP}}"
FUZZ_PASSWORD="${FUZZ_PASSWORD:-Password123!}"

if [ ! -f "${OPENAPI_SPEC}" ]; then
  echo "ERROR: OpenAPI spec not found at ${OPENAPI_SPEC}. Run 'npm run bundle:openapi' first." >&2
  exit 1
fi

echo "==> Bootstrapping fuzz user (${FUZZ_USERNAME}) against ${SERVER_URL}..."
CREDENTIALS_JSON="$(printf '{"username":"%s","password":"%s"}' "${FUZZ_USERNAME}" "${FUZZ_PASSWORD}")"

REGISTER_STATUS="$(curl -sS -o /dev/null -w "%{http_code}" \
  -X POST "${SERVER_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "${CREDENTIALS_JSON}")"

if [ "${REGISTER_STATUS}" != "201" ] && [ "${REGISTER_STATUS}" != "200" ] && [ "${REGISTER_STATUS}" != "409" ]; then
  echo "ERROR: Failed to register fuzz user (HTTP ${REGISTER_STATUS}). Is the API running at ${SERVER_URL}?" >&2
  exit 1
fi

LOGIN_RESPONSE="$(curl -sS -f \
  -X POST "${SERVER_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "${CREDENTIALS_JSON}")"

TOKEN="$(printf '%s' "${LOGIN_RESPONSE}" | node -e '
const fs = require("fs");
const data = JSON.parse(fs.readFileSync(0, "utf8"));
if (!data.token) {
  process.exit(1);
}
process.stdout.write(data.token);
')"

if [ -z "${TOKEN}" ]; then
  echo "ERROR: Failed to extract JWT token from /api/auth/login response." >&2
  exit 1
fi

ARGS=(
  run "${OPENAPI_SPEC}"
  --url "${SERVER_URL}"
  --header "Authorization: Bearer ${TOKEN}"
  --checks "${SCHEMATHESIS_CHECKS}"
  --max-examples "${SCHEMATHESIS_MAX_EXAMPLES}"
)

if [ -n "${SCHEMATHESIS_SEED:-}" ]; then
  ARGS+=(--seed "${SCHEMATHESIS_SEED}")
fi

if [ -n "${SCHEMATHESIS_JUNIT_PATH:-}" ]; then
  mkdir -p "$(dirname "${SCHEMATHESIS_JUNIT_PATH}")"
  ARGS+=(--report-junit-path "${SCHEMATHESIS_JUNIT_PATH}")
fi

ARGS+=("$@")

echo "==> Running Schemathesis against ${SERVER_URL} (spec: ${OPENAPI_SPEC}, checks: ${SCHEMATHESIS_CHECKS}, max-examples: ${SCHEMATHESIS_MAX_EXAMPLES})..."

if command -v schemathesis >/dev/null 2>&1; then
  exec schemathesis "${ARGS[@]}"
elif command -v uvx >/dev/null 2>&1; then
  exec uvx schemathesis "${ARGS[@]}"
elif command -v docker >/dev/null 2>&1; then
  exec docker run --rm --network host \
    -v "${PROJECT_ROOT}:/work" \
    -w /work \
    schemathesis/schemathesis:stable \
    "${ARGS[@]}"
else
  echo "ERROR: Neither 'schemathesis', 'uvx', nor 'docker' is installed." >&2
  echo "Install Schemathesis with: uv tool install schemathesis" >&2
  exit 1
fi

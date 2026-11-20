#!/usr/bin/env bash
set -euo pipefail

SERVER_URL="${SERVER_URL:-http://localhost:8080}"
OPENAPI_SPEC="${OPENAPI_SPEC:-docs/dist/openapi.admin.yaml}"
MAX_EXAMPLES="${MAX_EXAMPLES:-100}"
ADMIN_API_KEY="${ADMIN_API_KEY:-admin-secret-key}"
SCHEMATHESIS_IMAGE="${SCHEMATHESIS_IMAGE:-schemathesis/schemathesis:stable}"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

if [[ ! -f "${OPENAPI_SPEC}" ]]; then
  echo "==> Bundling OpenAPI spec (${OPENAPI_SPEC} not found)..."
  npm run bundle:openapi
fi

if [[ -z "${SCHEMATHESIS_AUTH_TOKEN:-}" ]]; then
  TIMESTAMP="$(date +%s)"
  USERNAME="${SCHEMATHESIS_USERNAME:-schemathesis-${TIMESTAMP}}"
  PASSWORD="${SCHEMATHESIS_PASSWORD:-Password123!}"
  PAYLOAD=$(printf '{"username":"%s","password":"%s"}' "${USERNAME}" "${PASSWORD}")

  echo "==> Registering temporary user '${USERNAME}' at ${SERVER_URL}..."
  curl -fsS -o /dev/null -X POST "${SERVER_URL}/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "${PAYLOAD}" || true

  echo "==> Logging in as '${USERNAME}'..."
  LOGIN_RESPONSE=$(curl -fsS -X POST "${SERVER_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "${PAYLOAD}")
  SCHEMATHESIS_AUTH_TOKEN=$(node -e "const d = JSON.parse(process.argv[1]); process.stdout.write(d.token ?? '');" "${LOGIN_RESPONSE}")
  if [[ -z "${SCHEMATHESIS_AUTH_TOKEN}" ]]; then
    echo "Failed to obtain JWT token from ${SERVER_URL}/api/auth/login" >&2
    exit 1
  fi
fi

echo "==> Running Schemathesis OpenAPI fuzzing against ${SERVER_URL} using ${OPENAPI_SPEC}..."

exec docker run --rm --network host \
  -v "${PROJECT_ROOT}:/workspace:ro" \
  -w /workspace \
  -e PYTHONPATH=/workspace \
  -e SCHEMATHESIS_HOOKS=scripts.schemathesis_hooks \
  -e SCHEMATHESIS_AUTH_TOKEN="${SCHEMATHESIS_AUTH_TOKEN}" \
  -e SCHEMATHESIS_ADMIN_KEY="${ADMIN_API_KEY}" \
  "${SCHEMATHESIS_IMAGE}" run "${OPENAPI_SPEC}" \
  --url "${SERVER_URL}" \
  --checks all \
  --max-examples "${MAX_EXAMPLES}" \
  "$@"

#!/usr/bin/env bash
set -euo pipefail

SERVER_URL="${SERVER_URL:-http://localhost:8080}"
TIMESTAMP="$(date +%s)"
ARAZZO_USERNAME="${ARAZZO_USERNAME:-arazzo-user-${TIMESTAMP}}"
ARAZZO_PASSWORD="${ARAZZO_PASSWORD:-Password123!}"

INPUT_JSON=$(printf '{"username":"%s","password":"%s"}' "$ARAZZO_USERNAME" "$ARAZZO_PASSWORD")

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

echo "==> Running Arazzo workflow tests against ${SERVER_URL} (username: ${ARAZZO_USERNAME})..."

exec npx redocly respect docs/workflows/todo-workflows.arazzo.yaml \
  --server "${SERVER_URL}" \
  --input "${INPUT_JSON}" \
  "$@"

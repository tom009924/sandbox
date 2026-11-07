#!/usr/bin/env bash
set -euo pipefail

# Cache directory for Trivy database
CACHE_DIR="${TRIVY_CACHE_DIR:-${HOME}/.cache/trivy}"
mkdir -p "${CACHE_DIR}"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Running Trivy security scan (cache: ${CACHE_DIR})..."

docker run --rm \
  -v "${CACHE_DIR}:/root/.cache/" \
  -v "${PROJECT_ROOT}:/project" \
  -w /project \
  aquasec/trivy:latest fs "$@" .


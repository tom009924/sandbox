#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORTS_DIR="${DEPSCAN_REPORTS_DIR:-build/reports/depscan}"
SCAN_TYPES="${DEPSCAN_TYPES:-gradle,nodejs}"
IMAGE="${DEPSCAN_IMAGE:-ghcr.io/owasp-dep-scan/dep-scan:latest}"

mkdir -p "${PROJECT_ROOT}/${REPORTS_DIR}"

echo "==> Running OWASP dep-scan (types: ${SCAN_TYPES}, reports: ${REPORTS_DIR})..."

if command -v depscan &> /dev/null; then
  depscan \
    --src "${PROJECT_ROOT}" \
    --reports-dir "${PROJECT_ROOT}/${REPORTS_DIR}" \
    --type "${SCAN_TYPES}" \
    --explain \
    "$@"
elif command -v docker &> /dev/null && docker info &> /dev/null; then
  docker run --rm \
    -e "FETCH_LICENSE=${FETCH_LICENSE:-false}" \
    -v "${PROJECT_ROOT}:/app" \
    "${IMAGE}" \
    depscan \
    --src /app \
    --reports-dir "/app/${REPORTS_DIR}" \
    --type "${SCAN_TYPES}" \
    --explain \
    "$@"
else
  echo "Error: Neither 'depscan' CLI nor a running 'docker' daemon was found." >&2
  exit 1
fi

echo "✓ OWASP dep-scan completed. Reports saved to: ${REPORTS_DIR}"

#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORTS_DIR="${DEPSCAN_REPORTS_DIR:-build/reports/depscan}"
SCAN_TYPES="${DEPSCAN_TYPES:-gradle,nodejs}"
IMAGE="${DEPSCAN_IMAGE:-ghcr.io/owasp-dep-scan/dep-scan:latest}"
VDB_CACHE_DIR="${DEPSCAN_VDB_DIR:-/tmp/depscan-vdb}"
VDB_SCOPE="${DEPSCAN_VDB_SCOPE:-app}"

mkdir -p "${PROJECT_ROOT}/${REPORTS_DIR}" "${VDB_CACHE_DIR}"

echo "==> Running OWASP dep-scan (types: ${SCAN_TYPES}, vdb-scope: ${VDB_SCOPE}, reports: ${REPORTS_DIR})..."

if command -v depscan &> /dev/null; then
  export VDB_HOME="${VDB_CACHE_DIR}"
  if command -v depscan-vdb &> /dev/null; then
    depscan-vdb download --scope "${VDB_SCOPE}"
  fi
  depscan \
    --src "${PROJECT_ROOT}" \
    --reports-dir "${PROJECT_ROOT}/${REPORTS_DIR}" \
    --type "${SCAN_TYPES}" \
    --explain \
    "$@"
elif command -v docker &> /dev/null && docker info &> /dev/null; then
  docker run --rm \
    -e "FETCH_LICENSE=${FETCH_LICENSE:-false}" \
    -e "VDB_HOME=/vdb" \
    -v "${PROJECT_ROOT}:/app" \
    -v "${VDB_CACHE_DIR}:/vdb" \
    "${IMAGE}" \
    sh -c 'depscan-vdb download --scope "$1" && shift && depscan --src /app --reports-dir "/app/$1" --type "$2" --explain "${@:3}"' \
    _ "${VDB_SCOPE}" "${REPORTS_DIR}" "${SCAN_TYPES}" "$@"
else
  echo "Error: Neither 'depscan' CLI nor a running 'docker' daemon was found." >&2
  exit 1
fi

echo "✓ OWASP dep-scan completed. Reports saved to: ${REPORTS_DIR}"

#!/usr/bin/env bash
set -euo pipefail

OUTPUT_FILE="${1:-bom.cyclonedx.json}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Generating CycloneDX SBOM: ${OUTPUT_FILE}..."

if command -v trivy &> /dev/null; then
  trivy fs --format cyclonedx --output "${OUTPUT_FILE}" "${PROJECT_ROOT}"
elif command -v docker &> /dev/null && docker info &> /dev/null; then
  docker run --rm \
    -v "${PROJECT_ROOT}:/project" \
    -w /project \
    aquasec/trivy:latest fs --format cyclonedx --output "/project/${OUTPUT_FILE}" .
else
  echo "Error: Neither 'trivy' CLI nor running 'docker' daemon was found." >&2
  exit 1
fi

echo "✓ SBOM generated successfully at: ${OUTPUT_FILE}"

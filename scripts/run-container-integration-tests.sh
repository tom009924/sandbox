#!/usr/bin/env bash
set -euo pipefail

COMPOSE_CMD=(docker compose --profile wiremock -f docker-compose.yml -f docker-compose.test.yml)

cleanup() {
  "${COMPOSE_CMD[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "${SKIP_BOOT_JAR:-false}" != "true" ]; then
  ./gradlew :bootJar --no-daemon
fi

if ! "${COMPOSE_CMD[@]}" up --build -d --wait; then
  echo "Failed to start container integration test stack. Logs:"
  "${COMPOSE_CMD[@]}" logs
  exit 1
fi

if ! ./gradlew :integration-tests:test --no-daemon "$@"; then
  echo "Container integration tests failed. Service logs:"
  "${COMPOSE_CMD[@]}" logs identity-service todo-service api-gateway
  exit 1
fi

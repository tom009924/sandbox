#!/usr/bin/env bash
set -euo pipefail

COMPOSE_CMD=(docker compose --profile wiremock -f docker-compose.yml -f docker-compose.test.yml)

cleanup() {
  "${COMPOSE_CMD[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "${SKIP_BOOT_JAR:-false}" != "true" ]; then
  ./gradlew :bootJar :services:spanner-proxy:bootJar :integration-tests:testClasses --no-daemon --max-workers=2
elif [ ! -f services/spanner-proxy/build/libs/app.jar ]; then
  ./gradlew :services:spanner-proxy:bootJar :integration-tests:testClasses --no-daemon --max-workers=2
else
  ./gradlew :integration-tests:testClasses --no-daemon --max-workers=2
fi

if ! "${COMPOSE_CMD[@]}" up --build -d --wait; then
  echo "Failed to start container integration test stack. Logs:"
  "${COMPOSE_CMD[@]}" logs
  exit 1
fi

if ! ./gradlew :integration-tests:test :integration-tests:propertyTest -x compileTestKotlin --no-daemon -Dorg.gradle.jvmargs="-Xmx256m -XX:MaxMetaspaceSize=256m" "$@"; then
  echo "Container integration tests failed. Service logs:"
  "${COMPOSE_CMD[@]}" logs identity-service todo-service api-gateway
  exit 1
fi

if [ "${SKIP_ARAZZO:-false}" != "true" ]; then
  if ! SERVER_URL=http://127.0.0.1:8082 npm run test:arazzo; then
    echo "Arazzo E2E via containerized api-gateway failed. Service logs:"
    "${COMPOSE_CMD[@]}" logs identity-service todo-service api-gateway
    exit 1
  fi
fi

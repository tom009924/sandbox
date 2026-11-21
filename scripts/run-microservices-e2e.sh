#!/usr/bin/env bash
set -euo pipefail

IDENTITY_JAR="${IDENTITY_JAR:-services/identity-service/build/libs/app.jar}"
TODO_JAR="${TODO_JAR:-build/libs/app.jar}"
GATEWAY_JAR="${GATEWAY_JAR:-services/api-gateway/build/libs/app.jar}"

IDENTITY_PID=""
TODO_PID=""
GATEWAY_PID=""

cleanup() {
  kill "${IDENTITY_PID:-}" "${TODO_PID:-}" "${GATEWAY_PID:-}" 2>/dev/null || true
}
trap cleanup EXIT

wait_for_health() {
  local name="$1"
  local url="$2"
  local log_file="$3"
  local retries="${4:-30}"

  for _ in $(seq 1 "$retries"); do
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" "$url" || true)
    if [ "$code" = "200" ]; then
      echo "$name is healthy ($url)"
      return 0
    fi
    sleep 2
  done

  echo "$name failed to become healthy ($url). Logs:"
  cat "$log_file"
  return 1
}

SPANNER_DATABASE="${IDENTITY_SPANNER_DATABASE:-identity-database}" \
  java -jar "$IDENTITY_JAR" > identity-service.log 2>&1 &
IDENTITY_PID=$!
wait_for_health "identity-service" "http://127.0.0.1:8081/actuator/health" "identity-service.log"

IDENTITY_SERVICE_ENABLED=true \
IDENTITY_SERVICE_GRPC_HOST=127.0.0.1 \
IDENTITY_SERVICE_GRPC_PORT=9091 \
  java -jar "$TODO_JAR" > todo-api.log 2>&1 &
TODO_PID=$!
wait_for_health "todo-api" "http://127.0.0.1:8080/actuator/health" "todo-api.log"

IDENTITY_GRPC_HOST=127.0.0.1 \
IDENTITY_GRPC_PORT=9091 \
TODO_SERVICE_BASE_URL=http://127.0.0.1:8080 \
  java -jar "$GATEWAY_JAR" > api-gateway.log 2>&1 &
GATEWAY_PID=$!
wait_for_health "api-gateway" "http://127.0.0.1:8082/actuator/health" "api-gateway.log"

if ! GATEWAY_BASE_URL=http://127.0.0.1:8082 node scripts/test-microservices-e2e.mjs; then
  echo "Combined microservices E2E test failed."
  echo "=== identity-service.log ==="
  cat identity-service.log
  echo "=== todo-api.log ==="
  cat todo-api.log
  echo "=== api-gateway.log ==="
  cat api-gateway.log
  exit 1
fi

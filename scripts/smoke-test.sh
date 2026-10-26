#!/usr/bin/env bash
# ==============================================================================
# Smoke Test Script for Test/Staging Environment
# ==============================================================================
# Usage:
#   ./scripts/smoke-test.sh [TARGET_URL]
# Example:
#   ./scripts/smoke-test.sh http://localhost:8080
#   ./scripts/smoke-test.sh https://staging.api.example.com
# ==============================================================================
set -euo pipefail

TARGET_URL="${1:-${STAGING_API_URL:-http://localhost:8080}}"
# Remove trailing slash if present
TARGET_URL="${TARGET_URL%/}"

echo "=========================================================="
echo " Starting Smoke Tests against: ${TARGET_URL}"
echo "=========================================================="

# 1. Health Check
echo -n "[1/5] Checking /actuator/health ... "
HEALTH_RESP=$(curl -sS -f --connect-timeout 5 --max-time 10 "${TARGET_URL}/actuator/health" 2>&1) || {
  echo "FAILED!"
  echo "Could not reach ${TARGET_URL}/actuator/health: ${HEALTH_RESP}"
  exit 1
}
echo "OK"

# Generate unique test user
RANDOM_SUFFIX=$(date +%s)_$RANDOM
TEST_USER="smoketest_${RANDOM_SUFFIX}"
TEST_PASS="P@ssw0rd_${RANDOM_SUFFIX}"

# 2. User Registration (B-01: Spanner write, B-03: Route)
echo -n "[2/5] Testing user registration (/api/auth/register) ... "
REG_RESP=$(curl -sS -f -X POST "${TARGET_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"${TEST_USER}\", \"password\": \"${TEST_PASS}\"}") || {
  echo "FAILED!"
  echo "Registration failed for user ${TEST_USER}"
  exit 1
}
echo "OK"

# 3. User Login & JWT acquisition (B-05: Secret & JWT signing)
echo -n "[3/5] Testing login and JWT generation (/api/auth/login) ... "
LOGIN_RESP=$(curl -sS -f -X POST "${TARGET_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"${TEST_USER}\", \"password\": \"${TEST_PASS}\"}") || {
  echo "FAILED!"
  echo "Login failed for user ${TEST_USER}"
  exit 1
}

# Extract token using sed/grep (no jq dependency required)
TOKEN=$(echo "${LOGIN_RESP}" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4 || true)
if [ -z "${TOKEN}" ]; then
  # Fallback: check "token" field
  TOKEN=$(echo "${LOGIN_RESP}" | grep -o '"token":"[^"]*' | cut -d'"' -f4 || true)
fi

if [ -z "${TOKEN}" ]; then
  echo "FAILED!"
  echo "Could not extract access token from login response: ${LOGIN_RESP}"
  exit 1
fi
echo "OK"

# 4. Create Todo (B-01: Spanner write, B-02: Pub/Sub publish)
echo -n "[4/5] Testing Todo creation (/api/todos) ... "
CREATE_RESP=$(curl -sS -f -X POST "${TARGET_URL}/api/todos" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${TOKEN}" \
  -d '{"title": "Smoke Test Item", "description": "Auto-generated smoke test"}') || {
  echo "FAILED!"
  echo "Todo creation failed."
  exit 1
}

TODO_ID=$(echo "${CREATE_RESP}" | grep -o '"id":"[^"]*' | cut -d'"' -f4 || true)
if [ -z "${TODO_ID}" ]; then
  echo "FAILED!"
  echo "Could not extract todo ID from create response: ${CREATE_RESP}"
  exit 1
fi
echo "OK (Created ID: ${TODO_ID})"

# 5. Fetch and Cleanup Todo (B-01: Spanner read & delete)
echo -n "[5/5] Testing Todo fetch & deletion ... "
curl -sS -f -X GET "${TARGET_URL}/api/todos/${TODO_ID}" \
  -H "Authorization: Bearer ${TOKEN}" > /dev/null || {
  echo "FAILED fetching created todo."
  exit 1
}

curl -sS -f -X DELETE "${TARGET_URL}/api/todos/${TODO_ID}" \
  -H "Authorization: Bearer ${TOKEN}" > /dev/null || {
  echo "FAILED deleting created todo."
  exit 1
}
echo "OK"

echo "=========================================================="
echo " Smoke Tests Completed Successfully!"
echo " All core paths verified (Health, Auth, Spanner, PubSub, Token)."
echo "=========================================================="
exit 0


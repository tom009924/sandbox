#!/usr/bin/env bash
set -euo pipefail

EMULATOR_HOST="${APIGEE_EMULATOR_HOST:-http://localhost:8088}"
TRAFFIC_PORT="${APIGEE_TRAFFIC_PORT:-8998}"
ENV_NAME="${APIGEE_ENV:-test}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUNDLE_ZIP="/tmp/apigee-bundle.zip"

APIGEE_DIR="$REPO_ROOT/infra/apigee"
if [ ! -d "$APIGEE_DIR" ]; then
  echo "Error: Apigee workspace directory not found at $APIGEE_DIR" >&2
  exit 1
fi

echo "Packaging Apigee workspace bundle from: $APIGEE_DIR"
rm -f "$BUNDLE_ZIP"
STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGE_DIR"' EXIT
mkdir -p "$STAGE_DIR/src/main"
cp -R "$APIGEE_DIR" "$STAGE_DIR/src/main/apigee"
(cd "$STAGE_DIR" && zip -r "$BUNDLE_ZIP" src/main/apigee > /dev/null)

echo "Deploying workspace bundle to Apigee Emulator ($EMULATOR_HOST) in environment '$ENV_NAME'..."
HTTP_STATUS=$(curl -s -o /tmp/apigee-deploy-response.json -w "%{http_code}" -X POST \
  "$EMULATOR_HOST/v1/emulator/deploy?environment=$ENV_NAME" \
  -H "Content-Type: application/zip" \
  --data-binary "@$BUNDLE_ZIP" || true)

if [ "$HTTP_STATUS" -ge 200 ] && [ "$HTTP_STATUS" -lt 300 ]; then
  echo "Successfully deployed Apigee bundle (HTTP $HTTP_STATUS)!"
  cat /tmp/apigee-deploy-response.json | jq . 2>/dev/null || cat /tmp/apigee-deploy-response.json
  echo ""
  echo "Test via Apigee proxy endpoint (Traffic port $TRAFFIC_PORT):"
  echo "  curl -X GET http://localhost:$TRAFFIC_PORT/v1/todos"
else
  echo "Deployment endpoint returned HTTP $HTTP_STATUS (or emulator is not yet running)."
  cat /tmp/apigee-deploy-response.json 2>/dev/null || true
  echo ""
  echo "Note: If the emulator is running, you can also deploy and trace via the VS Code Cloud Code extension."
  exit 1
fi

#!/usr/bin/env bash
set -euo pipefail

EMULATOR_HOST="${APIGEE_EMULATOR_HOST:-http://localhost:8088}"
ENV_NAME="${APIGEE_ENV:-test}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXY_DIR="$SCRIPT_DIR/../apigee/apiproxies/todo-v1"
BUNDLE_ZIP="/tmp/todo-v1.zip"

if [ ! -d "$PROXY_DIR/apiproxy" ]; then
  echo "Error: Apigee proxy directory not found at $PROXY_DIR/apiproxy" >&2
  exit 1
fi

echo "Packaging Apigee API proxy bundle from: $PROXY_DIR"
rm -f "$BUNDLE_ZIP"
(cd "$PROXY_DIR" && zip -r "$BUNDLE_ZIP" apiproxy > /dev/null)

echo "Deploying todo-v1 proxy bundle to Apigee Emulator ($EMULATOR_HOST) in environment '$ENV_NAME'..."
HTTP_STATUS=$(curl -s -o /tmp/apigee-deploy-response.json -w "%{http_code}" -X POST \
  "$EMULATOR_HOST/v1/emulator/deploy?environment=$ENV_NAME" \
  -H "Content-Type: application/zip" \
  --data-binary "@$BUNDLE_ZIP" || true)

if [ "$HTTP_STATUS" -ge 200 ] && [ "$HTTP_STATUS" -lt 300 ]; then
  echo "Successfully deployed todo-v1 proxy bundle (HTTP $HTTP_STATUS)!"
  cat /tmp/apigee-deploy-response.json | jq . 2>/dev/null || cat /tmp/apigee-deploy-response.json
  echo ""
  echo "Test via Apigee proxy endpoint:"
  echo "  curl -X GET $EMULATOR_HOST/v1/todos"
else
  echo "Deployment endpoint returned HTTP $HTTP_STATUS (or emulator is not yet running)."
  cat /tmp/apigee-deploy-response.json 2>/dev/null || true
  echo ""
  echo "Note: If the emulator is running, you can also deploy and trace via the VS Code Cloud Code extension."
fi

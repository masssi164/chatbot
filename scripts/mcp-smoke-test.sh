#!/usr/bin/env bash
set -euo pipefail

# Basic smoke test to validate LocalAI + LocalAGI + MCP streaming loop

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd python3

LOCALAGI_URL=${SMOKE_OPENAI_BASE_URL:-http://localhost:8083/v1}
BACKEND_URL=${SMOKE_BACKEND_URL:-http://localhost:8080}
OPENAI_API_KEY=${OPENAI_API_KEY:-sk-local-master}
SMOKE_MODEL=${SMOKE_MODEL:-llama-3.2-1b-instruct-q4_k_m}
SMOKE_TOOL_NAME=${SMOKE_TOOL_NAME:-status.ping}
SMOKE_PROMPT=${SMOKE_PROMPT:-"Before you answer, call the MCP tool ${SMOKE_TOOL_NAME} with any safe arguments and report its result."}
SMOKE_TIMEOUT=${SMOKE_TIMEOUT:-120}

run_with_timeout() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "$SMOKE_TIMEOUT" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$SMOKE_TIMEOUT" "$@"
  else
    "$@"
  fi
}

echo "1) Checking LocalAGI /v1/models..."
MODEL_RESPONSE=$(curl -fsS -H "Authorization: Bearer ${OPENAI_API_KEY}" "${LOCALAGI_URL}/models")
MODEL_COUNT=$(echo "$MODEL_RESPONSE" | jq '.data | length')
echo "   ✓ LocalAGI returned ${MODEL_COUNT} model(s)"

if [[ "$MODEL_COUNT" -eq 0 ]]; then
  echo "   ✗ No models available from LocalAGI. Seed LocalAI models first." >&2
  exit 1
fi

echo "2) Creating conversation..."
CONV_ID=$(curl -fsS -X POST "${BACKEND_URL}/api/conversations" \
  -H "Content-Type: application/json" \
  -d '{"title":"Smoke Test"}' | jq -r '.id')

if [[ -z "$CONV_ID" || "$CONV_ID" == "null" ]]; then
  echo "Failed to create conversation" >&2
  exit 1
fi
echo "   ✓ Conversation ${CONV_ID} created"

export SMOKE_CONV_ID="$CONV_ID"
export SMOKE_MODEL
export SMOKE_PROMPT

PAYLOAD_FILE=$(mktemp)
python3 - <<'PY' > "$PAYLOAD_FILE"
import json
import os
import sys

payload = {
    "conversationId": int(os.environ["SMOKE_CONV_ID"]),
    "title": "Smoke Test",
    "payload": {
        "model": os.environ["SMOKE_MODEL"],
        "input": f"{os.environ['SMOKE_PROMPT']}\nAssistant:"
    }
}

json.dump(payload, sys.stdout)
PY

STREAM_LOG=$(mktemp)
cleanup() {
  rm -f "$PAYLOAD_FILE" "$STREAM_LOG"
}
trap cleanup EXIT

echo "3) Streaming response and capturing SSE events..."
set +e
run_with_timeout curl -fsS -N \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -X POST "${BACKEND_URL}/api/responses/stream" \
  --data-binary "@${PAYLOAD_FILE}" | tee "$STREAM_LOG" >/dev/null
CURL_STATUS=$?
set -e

if [[ $CURL_STATUS -ne 0 ]]; then
  echo "Streaming request failed (curl exit code ${CURL_STATUS}). See ${STREAM_LOG} for details." >&2
  exit $CURL_STATUS
fi

if ! grep -q "event: response.mcp_call" "$STREAM_LOG"; then
  echo "✗ No response.mcp_call event observed. Ensure ${SMOKE_TOOL_NAME} is available and the prompt forces a tool call." >&2
  exit 1
fi

if ! grep -q "event: response.mcp_call_result" "$STREAM_LOG"; then
  echo "✗ response.mcp_call_result event missing. Inspect logs for LocalAGI/LocalAI/MCP server." >&2
  exit 1
fi

if ! grep -q "${SMOKE_TOOL_NAME}" "$STREAM_LOG"; then
  echo "⚠️ Tool call succeeded but did not mention ${SMOKE_TOOL_NAME}. Check the tool name or update SMOKE_TOOL_NAME." >&2
else
  echo "   ✓ Observed MCP tool call for ${SMOKE_TOOL_NAME}"
fi

echo "Smoke test passed."

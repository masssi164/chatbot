#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[ollama-seeder] %s\n' "$1"
}

error() {
  printf '[ollama-seeder] ERROR: %s\n' "$1" >&2
}

OLLAMA_HOST="${OLLAMA_HOST:-ollama}"
OLLAMA_PORT="${OLLAMA_PORT:-11434}"
OLLAMA_BASE_URL="${OLLAMA_API_BASE:-http://${OLLAMA_HOST}:${OLLAMA_PORT}}"
SEED_TIMEOUT="${OLLAMA_SEED_TIMEOUT:-600}"
MODELS_RAW="${OLLAMA_SEED_MODELS:-llama3.2:1b,phi3.5:3.8b,qwen2.5:1.5b}"

# Normalize models list (commas or whitespace) and remove empty entries
IFS=',' read -ra MODELS_FROM_COMMAS <<< "${MODELS_RAW// /,}"
MODELS=()
for model in "${MODELS_FROM_COMMAS[@]}"; do
  trimmed="$(echo "$model" | xargs)"
  if [[ -n "$trimmed" ]]; then
    MODELS+=("$trimmed")
  fi
done

if [[ ${#MODELS[@]} -eq 0 ]]; then
  error "No models configured to seed (OLLAMA_SEED_MODELS is empty)."
  exit 1
fi

log "Waiting for Ollama to be ready at ${OLLAMA_BASE_URL} (timeout: ${SEED_TIMEOUT}s)"
start_time=$(date +%s)
while true; do
  if curl -fsS "${OLLAMA_BASE_URL%/}/api/tags" >/dev/null 2>&1; then
    break
  fi
  current_time=$(date +%s)
  elapsed=$((current_time - start_time))
  if (( elapsed >= SEED_TIMEOUT )); then
    error "Timed out waiting for Ollama at ${OLLAMA_BASE_URL}."
    exit 1
  fi
  sleep 5
done
log "Ollama is reachable. Starting model seeding (${#MODELS[@]} models)."

pull_model() {
  local model="$1"
  log "Pulling model: ${model}"
  local response
  if ! response=$(curl -fsS -N -X POST "${OLLAMA_BASE_URL%/}/api/pull" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"${model}\"}"); then
    error "HTTP request failed while pulling ${model}."
    return 1
  fi

  if echo "$response" | jq -e 'select(.status == "error")' >/dev/null; then
    local message
    message=$(echo "$response" | jq -r 'select(.status == "error") | .error // "unknown error"' | tail -n 1)
    error "Ollama reported an error for ${model}: ${message}"
    return 1
  fi

  if ! echo "$response" | jq -e 'select(.status == "success")' >/dev/null; then
    error "Did not receive success confirmation for ${model}."
    return 1
  fi

  log "Successfully pulled ${model}."
  return 0
}

for model in "${MODELS[@]}"; do
  if ! pull_model "$model"; then
    error "Failed to seed required model: ${model}"
    exit 1
  fi
done

log "All models seeded successfully."

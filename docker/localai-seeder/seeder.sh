#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[localai-seeder] %s\n' "$1"
}

err() {
  printf '[localai-seeder] ERROR: %s\n' "$1" >&2
}

LOCALAI_SEED_MODELS="${LOCALAI_SEED_MODELS:-}"

if [[ -z "${LOCALAI_SEED_MODELS// }" ]]; then
  log "No models configured via LOCALAI_SEED_MODELS. Nothing to install."
  exit 0
fi

# Normalize comma/space separated list, trim whitespace
IFS=',' read -ra RAW_MODELS <<< "${LOCALAI_SEED_MODELS// /,}"
MODELS=()
for raw in "${RAW_MODELS[@]}"; do
  trimmed="$(echo "$raw" | xargs)"
  if [[ -n "$trimmed" ]]; then
    MODELS+=("$trimmed")
  fi
done

if [[ ${#MODELS[@]} -eq 0 ]]; then
  log "No valid models after normalization. Nothing to install."
  exit 0
fi

log "Installing ${#MODELS[@]} model(s) with local-ai CLI"
for model in "${MODELS[@]}"; do
  log "Installing model: ${model}"
  if ! local-ai models install "$model"; then
    err "Failed to install ${model}"
    exit 1
  fi
done

log "All models installed successfully."

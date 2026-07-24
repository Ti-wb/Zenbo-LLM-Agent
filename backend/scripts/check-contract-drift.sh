#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$repo_root"

compare() {
  source_file=$1
  embedded_file=$2
  if ! cmp -s "$source_file" "$embedded_file"; then
    echo "contract drift: $embedded_file does not match $source_file" >&2
    exit 1
  fi
}

compare contracts/agent-gateway/openapi.json \
  backend/internal/contractassets/assets/openapi.json
compare contracts/agent-gateway/schemas/tool-manifest.schema.json \
  backend/internal/contractassets/assets/tool-manifest.schema.json
compare contracts/agent-gateway/schemas/ws-envelope.schema.json \
  backend/internal/contractassets/assets/ws-envelope.schema.json

echo "embedded Agent Gateway contracts match normative sources"

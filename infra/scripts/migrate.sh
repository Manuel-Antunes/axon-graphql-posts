#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

eval "$(./infra/scripts/discover.sh)"

run() {
  local fn="$2"
  [ -n "$fn" ] && [ "$fn" != "None" ] || { echo "não achei a função de migração de $1" >&2; exit 1; }
  echo "==> $1  ($fn)"
  aws lambda invoke \
    --function-name "$fn" \
    --cli-binary-format raw-in-base64-out \
    --payload '{}' \
    --cli-read-timeout 300 \
    /dev/stdout
  echo
}

run "posts-api" "$POSTS_MIGRATE"
run "tagging"   "$TAGGING_MIGRATE"

echo "schemas criados."

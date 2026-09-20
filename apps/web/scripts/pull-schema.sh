#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

url="${1:-${NEXT_PUBLIC_GRAPHQL_URL:-${GRAPHQL_URL:-http://localhost:8080/graphql}}}"
echo "→ ${url}/schema.graphql"

curl -fsS "${url}/schema.graphql" -o schema.graphql.tmp
mv schema.graphql.tmp schema.graphql

echo "✓ schema.graphql ($(wc -l < schema.graphql | tr -d ' ') linhas)"

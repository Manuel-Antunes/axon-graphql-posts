#!/usr/bin/env bash
#
# Puxa o SDL do subgraph e o grava em `schema.graphql`.
#
# POR QUE UM ARQUIVO, E NÃO INTROSPECÇÃO NO CODEGEN: o `graphql-codegen` sabe introspectar uma URL,
# e se o fizesse o `pnpm codegen` passaria a exigir a API de pé — inclusive para quem só clonou o
# repositório. O schema é CONTRATO: ele muda quando alguém muda o servidor, não quando o servidor
# está ligado. Guardá-lo versionado é o que faz o diff dessa mudança aparecer no PR.
#
# É o mesmo movimento que o `schema.graphql` da raiz do posts-api já faz. A diferença é a fonte:
# aquele sai do build, este sai do ENDPOINT — porque é o que o cliente realmente vai encontrar.
#
# Uso:  ./scripts/pull-schema.sh [url-do-graphql]
#       GRAPHQL_URL=... ./scripts/pull-schema.sh
set -euo pipefail

cd "$(dirname "$0")/.."

url="${1:-${NEXT_PUBLIC_GRAPHQL_URL:-${GRAPHQL_URL:-http://localhost:8080/graphql}}}"
echo "→ ${url}/schema.graphql"

curl -fsS "${url}/schema.graphql" -o schema.graphql.tmp
mv schema.graphql.tmp schema.graphql

echo "✓ schema.graphql ($(wc -l < schema.graphql | tr -d ' ') linhas)"

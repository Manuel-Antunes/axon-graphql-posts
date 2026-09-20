#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

POSTS_API="dev.manuelantunes:quarkus-axon-graphql-posts"
TAGGING="dev.manuelantunes:axonposts-tagging"

CONFIG=native
ONLY=""
CACHE=()
PREV=""
for arg in "$@"; do
  case "$arg" in
    --native)           CONFIG=native ;;
    --no-container)     CONFIG=native ;;
    --native-container) CONFIG=native-container ;;
    --jvm)              CONFIG=jvm ;;
    --force)            CACHE=(--skip-nx-cache) ;;
    --only)             PREV="only" ;;
    *)                  [ "$PREV" = "only" ] && ONLY="$arg"; PREV="" ;;
  esac
done

if [ -n "$ONLY" ]; then
  case "$ONLY" in
    posts-api-http)   PROJECT="$POSTS_API"; TARGET=lambda-http ;;
    posts-api-sqs)    PROJECT="$POSTS_API"; TARGET=lambda-sqs ;;
    posts-api-stream) PROJECT="$POSTS_API"; TARGET=lambda-stream ;;
    tagging)          PROJECT="$TAGGING";   TARGET=lambda ;;
    *)
      echo "ERRO: artefato desconhecido '$ONLY'." >&2
      echo "      São: posts-api-http, posts-api-sqs, posts-api-stream, tagging." >&2
      exit 1
      ;;
  esac
  npx nx run "$PROJECT:$TARGET:$CONFIG" ${CACHE[@]+"${CACHE[@]}"}
else
  npx nx run-many -t lambda-http,lambda-sqs,lambda-stream,lambda \
    -c "$CONFIG" --parallel=1 ${CACHE[@]+"${CACHE[@]}"}
fi

ls -lh infra/dist

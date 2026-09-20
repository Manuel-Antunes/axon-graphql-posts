#!/usr/bin/env bash
#
# OS QUATRO ARTEFATOS — hoje um ATALHO para os alvos do Nx, que são quem sabe construí-los.
#
#   infra/dist/posts-api-http.zip     API Gateway: GraphQL, SDL, _service/_entities, OIDC
#   infra/dist/posts-api-stream.zip   o MESMO servidor, sem extensão de Lambda, atrás do Web
#                                     Adapter: é o único que mantém uma conexão SSE aberta
#   infra/dist/posts-api-sqs.zip      a VOLTA da saga (e, com outro handler, as migrations)
#   infra/dist/tagging.zip            as DUAS funções de tagueamento
#
# ESTE ARQUIVO NÃO SABE CONSTRUIR NADA, e isso é o ponto. Perfil do Maven, montagem do zip, JDK e
# cache são dos alvos `lambda-*`, em `apps/*/project.json` — os mesmos que o `sst deploy` invoca e
# os mesmos que alguém roda na máquina. Uma definição só, como no `apps/web`. O que sobrou aqui é a
# tradução das flags antigas para os alvos, mais a única coisa que um atalho ainda resolve: rodar os
# quatro EM SÉRIE.
#
# Em série porque os perfis do Maven escrevem todos em `target/function.zip`, então dois builds em
# paralelo se apagam. O Nx não sabe disso — quem garante é o `--parallel=1` abaixo, e no deploy é o
# `dependsOn` encadeado de `infra/aws/support/functions.ts`.
#
#   ./infra/scripts/package.sh                      # os quatro, nativos (GraalVM da máquina)
#   ./infra/scripts/package.sh --jvm                # sem binário nativo
#   ./infra/scripts/package.sh --native-container   # no builder image do Mandrel
#   ./infra/scripts/package.sh --only tagging       # um artefato só
#   ./infra/scripts/package.sh --force              # ignora o cache do Nx
#
# Sem o atalho, é uma linha:
#
#   npx nx run-many -t lambda-http,lambda-sqs,lambda-stream,lambda -c native --parallel=1
#
# QUAL CONFIGURAÇÃO USAR: `native` compila com a GraalVM DESTA máquina, e é o default — num Mac o
# binário resultante é Mach-O, ótimo para rodar aqui e inútil para o Lambda, que quer ELF/Linux.
# Para o artefato que vai para a AWS de um Mac, é `--native-container`.
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
    # O nome antigo do mesmo caminho: "sem container" é compilar com a GraalVM local.
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

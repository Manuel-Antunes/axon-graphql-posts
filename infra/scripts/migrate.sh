#!/usr/bin/env bash
#
# Cria o schema dos DOIS event stores. Uma vez, depois do primeiro deploy — e de novo sempre que uma
# migration nova entrar.
#
# POR QUE ISTO É UM PASSO, E NÃO A PARTIDA DA APLICAÇÃO
# `quarkus.flyway.migrate-at-start` é `false` pela razão medida que está no application.properties: o
# recorder do Axon toca o EntityManager antes de o Flyway ter a vez, e com `validate` contra banco
# vazio a aplicação morre com `missing table [accounts]`.
#
# Em Lambda essa decisão deixa de ser contorno e vira a única correta. Migration na partida de uma
# função que escala para N ambientes de execução seria N tentativas concorrentes de alterar o mesmo
# schema — o Flyway tem lock, então não haveria corrupção: haveria cada cold start esperando o lock de
# outro, dentro do timeout de uma invocação que alguém está esperando.
#
# É o mesmo zip das funções de fila, com QUARKUS_LAMBDA_HANDLER=flyway-migrate. Ver
# FlywayMigrationLambda.
set -euo pipefail
cd "$(dirname "$0")/../.."

eval "$(./infra/scripts/discover.sh)"

run() { # $1 = rótulo, $2 = nome da função
  local fn="$2"
  [ -n "$fn" ] && [ "$fn" != "None" ] || { echo "não achei a função de migração de $1" >&2; exit 1; }
  echo "==> $1  ($fn)"
  # `raw-in-base64-out` porque o CLI v2 espera o payload em base64 por default. O handler ignora a
  # entrada; o que importa é não falhar na codificação antes de invocar.
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

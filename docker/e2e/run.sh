#!/usr/bin/env bash
# Sobe a infraestrutura e as DUAS aplicações, roda o teste da saga coreografada e desliga tudo.
#
# POR QUE ESTE TESTE NÃO É UM @QuarkusTest
# ========================================
# Porque o que ele prova é justamente o que um @QuarkusTest não consegue montar: dois PROCESSOS
# separados, com event stores separados, conversando por um broker. Dentro de uma JVM só, "dois
# serviços" seria dublagem — e a dublagem é exatamente o que a suíte do posts-api já faz
# (`InProcessTagAssignment`), de propósito e declaradamente.
#
# O outro motivo é isolamento: consistência eventual faz mensagem em voo CRUZAR a fronteira do teste, e
# a suíte isola cada método com `truncate`. Foi medido — 54 eventos publicados, 36 entregues, sete
# testes estourando espera. Aqui não há ninguém com quem disputar.
#
#   ./docker/e2e/run.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
LOGS=${E2E_LOGS:-$ROOT/target/e2e}
mkdir -p "$LOGS"

POSTS_PID=""; TAGGING_PID=""
cleanup() {
  echo "### encerrando as aplicações"
  [ -n "$POSTS_PID" ] && kill "$POSTS_PID" 2>/dev/null
  [ -n "$TAGGING_PID" ] && kill "$TAGGING_PID" 2>/dev/null
  wait 2>/dev/null
}
trap cleanup EXIT

echo "### 1. infraestrutura (Postgres, Keycloak, RabbitMQ) + migrations fora do processo"
docker compose up -d --wait postgres keycloak rabbitmq > /dev/null 2>&1 || {
  echo "compose falhou"; docker compose ps; exit 1; }

# O banco do serviço de tagueamento é criado por `docker/postgres/init/02-tagging-database.sql` — mas o
# `initdb` do Postgres roda SÓ quando o volume está vazio. Num volume que já existe (o caso de qualquer
# máquina que rodou o compose antes desta migration existir) o script nunca roda, e o sintoma é o Flyway
# girando em `connectRetries` contra um banco que não existe, sem dizer por quê. Garantir aqui é uma
# linha; `create database` não aceita `if not exists`, daí o guard.
docker exec quarkus-axonposts-postgres psql -U axonposts -d postgres -tAc \
  "select 1 from pg_database where datname = 'axonposts_tagging'" 2>/dev/null | grep -q 1 || {
  echo "    criando o banco axonposts_tagging (volume anterior ao script de init)"
  docker exec quarkus-axonposts-postgres psql -U axonposts -d postgres -q -c \
    "create database axonposts_tagging owner axonposts" > /dev/null; }

docker compose up --exit-code-from flyway-posts flyway-posts > "$LOGS/flyway-posts.log" 2>&1 || {
  echo "as migrations do posts-api falharam:"; tail -30 "$LOGS/flyway-posts.log"; exit 1; }
docker compose up --exit-code-from flyway-tagging flyway-tagging > "$LOGS/flyway-tagging.log" 2>&1 || {
  echo "as migrations do tagging falharam:"; tail -30 "$LOGS/flyway-tagging.log"; exit 1; }
echo "    migrations aplicadas nos dois bancos"

echo "### 2. um estado limpo nos dois bancos"
# Os dois juntos, e o event store com eles: store e read model incoerentes fariam o CreateTag falhar
# contra um agregado que existe no stream e não existe na tabela.
docker exec quarkus-axonposts-postgres psql -U axonposts -d axonposts -q -c \
  "truncate table post_tags, posts, tags, accounts, authors, users cascade;
   truncate table aggregateevententry, tokenentry, axon_message_inbox;" > /dev/null
docker exec quarkus-axonposts-postgres psql -U axonposts -d axonposts_tagging -q -c \
  "truncate table aggregateevententry, tokenentry, axon_message_inbox;" > /dev/null
# APAGA as filas em vez de purgar, e a diferença importa: purgar tira as mensagens e deixa os BINDINGS.
# Bindings são duráveis e sobrevivem a redesenho de topologia — um `tagging.*.*` de uma versão anterior
# continua pendurado na fila e faz `list_bindings` mentir sobre o desenho atual. Apagadas, as aplicações
# as redeclaram na partida com exatamente os bindings que elas declaram hoje.
#
# Inclui as filas de topologias anteriores: duráveis, sem consumidor, acumulando cópia de tudo. Quem for
# depurar veria uma fila com centenas de mensagens e concluiria que a entrega parou.
for queue in axonposts.posts-api.post-completed \
             axonposts.tagging.post-precreated axonposts.tagging.post-changes \
             axonposts.posts.inbox axonposts.tagging.inbox \
             axonposts.events.in axonposts.tagging.in axonposts.consumer.in; do
  docker exec quarkus-axonposts-rabbitmq rabbitmqctl -q delete_queue "$queue" > /dev/null 2>&1
done

echo "### 3. empacotando (LIMPO)"
# `clean` de propósito: um teste ponta a ponta tem de medir o que um build do zero produz, não o que
# sobrou do anterior. É o mesmo build que a integração contínua faria.
# O log do build vai para fora de `target/`, porque `clean` apaga `target/` — inclusive o diretório de
# logs deste script, se ele estiver lá.
BUILD_LOG=$(mktemp -t axonposts-e2e-build)
./mvnw -q clean package -DskipTests > "$BUILD_LOG" 2>&1 || {
  echo "build falhou:"; grep -E "\[ERROR\]" "$BUILD_LOG" | head -20; exit 1; }
mkdir -p "$LOGS"
cp "$BUILD_LOG" "$LOGS/build.log"

echo "### 4. subindo posts-api (8080) e tagging (sem porta)"
COMMON_ENV=(
  "RABBITMQ_HOST=localhost" "RABBITMQ_PORT=5672"
  "RABBITMQ_USERNAME=guest" "RABBITMQ_PASSWORD=guest"
  "QUARKUS_OTEL_SDK_DISABLED=true"
)
env "${COMMON_ENV[@]}" \
  QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/axonposts \
  QUARKUS_DATASOURCE_USERNAME=axonposts QUARKUS_DATASOURCE_PASSWORD=axonposts \
  KEYCLOAK_ISSUER_URI=http://localhost:8081/realms/axon-posts \
  java -jar apps/posts-api/target/quarkus-app/quarkus-run.jar > "$LOGS/posts-api.log" 2>&1 &
POSTS_PID=$!

env "${COMMON_ENV[@]}" \
  QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/axonposts_tagging \
  QUARKUS_DATASOURCE_USERNAME=axonposts QUARKUS_DATASOURCE_PASSWORD=axonposts \
  ${E2E_TAGGING_JAVA_OPTS:-} java -jar apps/tagging/target/quarkus-app/quarkus-run.jar > "$LOGS/tagging.log" 2>&1 &
TAGGING_PID=$!

# O posts-api tem /q/health. O tagging NÃO tem porta nenhuma — o sinal de que ele subiu é a linha do
# Quarkus no log dele, e é o único sinal que existe. É a consequência aceita de ser um serviço que não
# responde a ninguém.
UP_POSTS=0; UP_TAGGING=0
for _ in $(seq 1 90); do
  [ "$UP_POSTS" = 0 ] && curl -sf http://localhost:8080/q/health > /dev/null 2>&1 && UP_POSTS=1
  [ "$UP_TAGGING" = 0 ] && grep -q "started in" "$LOGS/tagging.log" 2>/dev/null && UP_TAGGING=1
  [ "$UP_POSTS" = 1 ] && [ "$UP_TAGGING" = 1 ] && break
  sleep 1
done
[ "$UP_POSTS" = 1 ] || { echo "posts-api não subiu:";
  grep -viE "^\s+at " "$LOGS/posts-api.log" | tail -20; exit 1; }
[ "$UP_TAGGING" = 1 ] || { echo "tagging não subiu:";
  grep -viE "^\s+at " "$LOGS/tagging.log" | tail -20; exit 1; }
echo "    posts-api: $(grep -oE 'started in [0-9.]+s' "$LOGS/posts-api.log" | head -1)"
echo "    tagging:   $(grep -oE 'started in [0-9.]+s' "$LOGS/tagging.log" | head -1)"

echo "### 5. a topologia que as duas aplicações declararam"
docker exec quarkus-axonposts-rabbitmq rabbitmqctl -q list_bindings source_name routing_key destination_name \
  2>/dev/null | grep axonposts | sed 's/^/    /'

echo "### 6. o teste"
node docker/e2e/saga-choreography.mjs
RESULT=$?

if [ $RESULT -ne 0 ]; then
  echo
  echo "### log do tagging (últimas linhas relevantes)"
  grep -viE "^\s+at " "$LOGS/tagging.log" | tail -25
fi
exit $RESULT

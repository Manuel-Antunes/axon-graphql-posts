#!/usr/bin/env bash
# Smoke test da POC: builda, sobe a app, abre subscriptions GraphQL via SSE, dispara
# mutations e confere que os eventos chegam nos streams. Tudo fica em .poc-logs/.
#
# Uso:  bash scripts/poc-smoke.sh            (porta 8080)
#       PORT=9090 bash scripts/poc-smoke.sh
#       SKIP_BUILD=1 bash scripts/poc-smoke.sh (reaproveita target/*.jar)
set -u
cd "$(dirname "$0")/.."

PORT="${PORT:-8080}"
URL="http://localhost:$PORT/graphql"
LOG=".poc-logs"
rm -rf "$LOG"; mkdir -p "$LOG"
: > "$LOG/STATUS"

step() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG/STATUS"; }
gql()  { curl -s -X POST "$URL" -H 'Content-Type: application/json' -d "$1"; }
# o id do post, e não o da tag: a resposta tem vários "id" e um sed guloso pega o último
post_id() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["data"];print(next(iter(d.values()))["id"])' "$1"; }
sse()  { curl -s -N -X POST "$URL" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d "$1"; }
finish() {
  step "encerrando (app=$APP_PID subs=$SUB_PIDS)"
  [ -n "${SUB_PIDS:-}" ] && kill $SUB_PIDS 2>/dev/null
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  sleep 3
  { for f in "$LOG"/[0-9]*; do echo "=================== $f"; cat "$f"; echo; done; } > "$LOG/ALL.txt" 2>&1
  step "DONE"
  touch "$LOG/DONE"
}
APP_PID=""; SUB_PIDS=""
trap finish EXIT

# ---- 0. ambiente ----------------------------------------------------------------------------
{ echo "== java"; java -version 2>&1; echo "== JAVA_HOME=${JAVA_HOME:-}"; echo "== PATH=$PATH"; } > "$LOG/00-env.txt" 2>&1
step "java: $(java -version 2>&1 | head -1)"

# ---- 1. build --------------------------------------------------------------------------------
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  step "build: ./mvnw -B package (primeira vez baixa o Maven + dependências, pode levar alguns minutos)"
  ./mvnw -B package > "$LOG/01-build.txt" 2>&1
  BUILD_EXIT=$?
  echo "exit=$BUILD_EXIT" >> "$LOG/01-build.txt"
  step "build exit=$BUILD_EXIT"
  if [ "$BUILD_EXIT" != "0" ]; then step "BUILD FALHOU — veja $LOG/01-build.txt"; exit 1; fi
fi

JAR=$(ls target/*.jar 2>/dev/null | grep -v '\.original' | head -1)
[ -n "$JAR" ] || { step "jar não encontrado em target/"; exit 1; }

# ---- 2. sobe a app ---------------------------------------------------------------------------
mkdir -p data; rm -f data/posts.db data/posts.db-journal data/posts.db-wal data/posts.db-shm
step "start: java -jar $JAR"
java -jar "$JAR" --server.port="$PORT" > "$LOG/02-app.txt" 2>&1 &
APP_PID=$!

READY=0
for i in $(seq 1 120); do
  if curl -s -o /dev/null -X POST "$URL" -H 'Content-Type: application/json' -d '{"query":"{ posts(first: 1) { edges { node { id } } } }"}'; then READY=1; break; fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then break; fi
  sleep 1
done
[ "$READY" = "1" ] || { step "APP NÃO SUBIU — veja $LOG/02-app.txt"; exit 1; }
step "app pronta em $URL"

# ---- 3. subscriptions (SSE) em background --------------------------------------------------
sse '{"query":"subscription { onPostCreated { id title author version } }"}' > "$LOG/10-sse-onPostCreated.txt" 2>&1 &
SUB_PIDS="$!"
sse '{"query":"subscription { onPostUpdated { id title content version } }"}' > "$LOG/11-sse-onPostUpdated-all.txt" 2>&1 &
SUB_PIDS="$SUB_PIDS $!"
sleep 2
step "subscriptions abertas: onPostCreated, onPostUpdated(sem filtro)"

# ---- 4. commands: createPost A e B ---------------------------------------------------------
gql '{"query":"mutation { createPost(input:{title:\"Axon + GraphQL over SSE\", content:\"primeiro post\", author:\"manuel\"}) { id title author createdAt version tags { id name } } }"}' > "$LOG/20-createPost-A.txt"
A=$(post_id "$LOG/20-createPost-A.txt")
gql '{"query":"mutation { createPost(input:{title:\"Segundo post\", content:\"outro conteúdo\", author:\"manuel\"}) { id title author version tags { id name } } }"}' > "$LOG/21-createPost-B.txt"
B=$(post_id "$LOG/21-createPost-B.txt")
step "criados A=$A B=$B"

# subscription filtrada por tópico (postId = A)
sse "{\"query\":\"subscription { onPostUpdated(postId: \\\"$A\\\") { id title content version } }\"}" > "$LOG/12-sse-onPostUpdated-only-A.txt" 2>&1 &
SUB_PIDS="$SUB_PIDS $!"
sleep 2
step "subscription aberta: onPostUpdated(postId: A)"

# ---- 5. commands: updatePost ---------------------------------------------------------------
gql "{\"query\":\"mutation { updatePost(input:{id: \\\"$A\\\", title: \\\"A — título editado\\\"}) { id title content updatedAt version } }\"}" > "$LOG/30-updatePost-A-title.txt"
gql "{\"query\":\"mutation { updatePost(input:{id: \\\"$B\\\", content: \\\"B — conteúdo editado\\\"}) { id title content version } }\"}" > "$LOG/31-updatePost-B-content.txt"
gql "{\"query\":\"mutation { updatePost(input:{id: \\\"$A\\\", content: \\\"A — conteúdo editado\\\"}) { id title content version } }\"}" > "$LOG/32-updatePost-A-content.txt"
step "updates disparados (A título, B conteúdo, A conteúdo)"

# ---- 6. erros esperados --------------------------------------------------------------------
gql '{"query":"mutation { updatePost(input:{id: \"nao-existe\", title: \"x\"}) { id } }"}' > "$LOG/40-error-updatePost-notFound.txt"
gql "{\"query\":\"mutation { updatePost(input:{id: \\\"$A\\\"}) { id } }\"}" > "$LOG/41-error-updatePost-noChanges.txt"
gql '{"query":"mutation { createPost(input:{title:\"   \", content:\"c\", author:\"a\"}) { id } }"}' > "$LOG/42-error-createPost-blankTitle.txt"
# Bean Validation na borda: título longo demais e update com título só de espaços
LONG=$(python3 -c 'print("x"*201)')
gql "{\"query\":\"mutation { createPost(input:{title:\\\"$LONG\\\", content:\\\"c\\\", author:\\\"a\\\"}) { id } }\"}" > "$LOG/43-error-createPost-longTitle.txt"
gql "{\"query\":\"mutation { updatePost(input:{id: \\\"$A\\\", title: \\\"   \\\"}) { id } }\"}" > "$LOG/44-error-updatePost-blankTitle.txt"
step "erros de validação disparados (title longo, title em branco no update)"

# ---- 7. queries ----------------------------------------------------------------------------
gql "{\"query\":\"{ post(id: \\\"$A\\\") { id title content author createdAt updatedAt version tags { id name } } }\"}" > "$LOG/50-query-post-A.txt"
gql '{"query":"{ posts(first: 10) { edges { cursor node { id title version tags { name } } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"}' > "$LOG/51-query-posts.txt"
# paginação: 1ª página com first:1, depois after = endCursor da primeira
gql '{"query":"{ posts(first: 1) { edges { cursor node { id title } } pageInfo { hasNextPage endCursor } } }"}' > "$LOG/53-connection-page1.txt"
CURSOR=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["posts"]["pageInfo"]["endCursor"])' "$LOG/53-connection-page1.txt")
gql "{\"query\":\"{ posts(first: 1, after: \\\"$CURSOR\\\") { edges { cursor node { id title } } pageInfo { hasNextPage } } }\"}" > "$LOG/54-connection-page2.txt"
step "cursor connection: página 1 + página 2 (after=$CURSOR)"
gql '{"query":"{ post(id: \"nao-existe\") { id } }"}' > "$LOG/52-query-post-missing.txt"
step "queries executadas"

sleep 2
# ---- 8. resumo -----------------------------------------------------------------------------
{
  echo "onPostCreated            -> $(grep -c -E '^event: ?next' "$LOG/10-sse-onPostCreated.txt") eventos (esperado 2)"
  # 2 atribuições da tag padrão (uma por post criado) + 3 updates explícitos
  echo "onPostUpdated (todos)    -> $(grep -c -E '^event: ?next' "$LOG/11-sse-onPostUpdated-all.txt") eventos (esperado 5)"
  # aberta depois das criações, então só vê os 2 updates explícitos de A
  echo "onPostUpdated (só A)     -> $(grep -c -E '^event: ?next' "$LOG/12-sse-onPostUpdated-only-A.txt") eventos (esperado 2)"
  echo "tag Untagged no createPost A -> $(grep -c 'Untagged' "$LOG/20-createPost-A.txt") (esperado 1)"
  echo "tag Untagged no createPost B -> $(grep -c 'Untagged' "$LOG/21-createPost-B.txt") (esperado 1)"
} | tee "$LOG/60-summary.txt" | while read -r l; do step "$l"; done

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ -z "${API:-}" ] || [ -z "${ISSUER:-}" ]; then
  echo "==> descobrindo os endereços da stack"
  eval "$(./infra/scripts/discover.sh)"
fi
API="${API%/}"
: "${API:?não achei a URL da API}"
: "${ISSUER:?não achei o issuer}"

echo "    api    = $API"
echo "    issuer = $ISSUER"

gql() {
  local body auth vars
  vars="${2:-}"
  [ -n "$vars" ] || vars='{}'
  body="$(jq -nc --arg q "$1" --argjson v "$vars" '{query:$q,variables:$v}')"
  auth="Authorization: Bearer ${3:-}"
  if [ -n "${3:-}" ]; then
    curl -sS -X POST "$API/graphql" -H 'Content-Type: application/json' -H "$auth" -d "$body"
  else
    curl -sS -X POST "$API/graphql" -H 'Content-Type: application/json' -d "$body"
  fi
}

fail() { echo "FALHOU: $*" >&2; exit 1; }

echo
echo "==> 1. o schema é servido (e o subgraph se declara)"
SDL=$(gql '{ _service { sdl } }' '{}' | jq -r '.data._service.sdl // empty')
[ -n "$SDL" ] || fail "_service { sdl } não respondeu"
echo "$SDL" | grep -q '@key' || fail 'o SDL não traz @key — a federação não está ligada'
echo "    OK: SDL servido, com as diretivas de federação"

echo
echo "==> 2. query pública responde SEM token"
ANON=$(gql '{ posts(first: 1) { edges { node { id } } } }')
echo "$ANON" | jq -e '.data.posts' >/dev/null || fail "a query pública falhou: $ANON"
echo "    OK: a query posts responde anonimamente (quarkus.http.auth.proactive=false)"

echo
echo "==> 3. mutation SEM token é recusada"
DENIED=$(gql 'mutation { createPost(input:{title:"x",content:"y"}) { id } }')
echo "$DENIED" | jq -e '.errors[0].extensions.code == "UNAUTHORIZED"' >/dev/null \
  || fail "esperava UNAUTHORIZED, veio: $DENIED"
echo "    OK: UNAUTHORIZED, com mensagem (o ErrorTranslationInterceptor roda por fora da segurança)"

echo
echo "==> 4. token do Cognito"
TOKEN=$(aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --client-id "$CLIENT_ID" \
  --auth-parameters USERNAME=manuel@example.com,PASSWORD=segredo123 \
  --query 'AuthenticationResult.IdToken' --output text 2>/dev/null)
[ -n "$TOKEN" ] && [ "$TOKEN" != "None" ] || fail "não consegui um ID token do pool $USER_POOL"
echo "    OK: ID token para manuel@example.com (grupo author)"

echo
echo "==> 5. createPost: nasce na versão 1, SEM tag"
CREATED=$(gql 'mutation($i:CreatePostInput!){ createPost(input:$i){ id version tags{ edges{ node{ name } } } } }' \
  "$(jq -nc --arg t "post da AWS $(date +%s)" '{i:{title:$t,content:"a saga atravessando SNS e SQS"}}')" \
  "$TOKEN")
POST_ID=$(echo "$CREATED" | jq -r '.data.createPost.id // empty')
[ -n "$POST_ID" ] || fail "createPost falhou: $CREATED"
V1=$(echo "$CREATED" | jq -r '.data.createPost.version')
TAGS1=$(echo "$CREATED" | jq -r '[.data.createPost.tags.edges[].node.name] | join(",")')
echo "    id=$POST_ID versão=$V1 tags=[$TAGS1]"
[ "$V1" = "1" ] || fail "esperava versão 1 na criação, veio $V1"
[ -z "$TAGS1" ] || fail "esperava NENHUMA tag na versão 1, veio [$TAGS1]"
echo "    OK: PostPreCreated — o post existe e ainda não está completo"

echo
echo "==> 6. a saga fecha: SNS -> SQS -> tagging -> SNS -> SQS -> posts-api"
echo "    (a primeira volta paga cold start de DUAS funções JVM)"
DEADLINE=$((SECONDS + 180))
while [ $SECONDS -lt $DEADLINE ]; do
  NOW=$(gql 'query($id:ID!){ post(id:$id){ version tags{ edges{ node{ name } } } } }' \
    "$(jq -nc --arg id "$POST_ID" '{id:$id}')")
  V=$(echo "$NOW" | jq -r '.data.post.version // 0')
  TAGS=$(echo "$NOW" | jq -r '[.data.post.tags.edges[].node.name] | join(",")')
  if [ "$V" = "2" ]; then
    echo "    versão=$V tags=[$TAGS]  (após ${SECONDS}s)"
    [ "$TAGS" = "Untagged" ] || fail "esperava a tag Untagged, veio [$TAGS]"
    echo "    OK: PostCreated — a decisão do OUTRO serviço voltou e a projeção materializou"
    break
  fi
  printf '.'
  sleep 5
done
[ "${V:-0}" = "2" ] || fail "a saga não fechou em 180s (última versão vista: ${V:-?})"

echo
echo "==> 7. a atualização também atravessa (posts.PostUpdated -> a fila de réplica)"
UPD=$(gql 'mutation($i:UpdatePostInput!){ updatePost(input:$i){ version } }' \
  "$(jq -nc --arg id "$POST_ID" '{i:{id:$id,title:"título alterado na AWS"}}')" "$TOKEN")
V3=$(echo "$UPD" | jq -r '.data.updatePost.version // empty')
[ -n "$V3" ] || fail "updatePost falhou: $UPD"
echo "    OK: versão=$V3"

echo
echo "================================================================"
echo "  A SAGA FECHOU NA AWS. post=$POST_ID"
echo "================================================================"

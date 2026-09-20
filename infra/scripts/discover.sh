#!/usr/bin/env bash
#
# Descobre os endereços da stack pela AWS, e não pelo SST.
#
# POR QUE NÃO `sst outputs`: ele NÃO EXISTE no SST 4.17.1 — `npx sst outputs` imprime o help. Os
# outputs só aparecem na saída do `sst deploy`, e um script que precise deles depois do deploy teria
# de guardá-los em algum lugar. Perguntar à AWS é mais simples e não inventa um segundo lugar da
# verdade: os nomes têm prefixo estável porque é o Pulumi que os gera a partir do nome do recurso.
#
# Uso:  eval "$(./infra/scripts/discover.sh)"
#       -> define API, ISSUER, USER_POOL, CLIENT_ID, POSTS_MIGRATE, TAGGING_MIGRATE
set -euo pipefail

fn() { aws lambda list-functions \
        --query "Functions[?starts_with(FunctionName,'$1-')].FunctionName | [0]" --output text; }

API=$(aws apigatewayv2 get-apis \
        --query "Items[?starts_with(Name,'PostsApiGateway')].ApiEndpoint | [0]" --output text)

# O user pool. O nome é gerado pelo SST a partir do nome do componente, com prefixo estável.
POOL=$(aws cognito-idp list-user-pools --max-results 60 \
        --query "UserPools[?starts_with(Name,'axonposts-dev-Users')].Id | [0]" --output text)
CLIENT=$(aws cognito-idp list-user-pool-clients --user-pool-id "$POOL" --max-results 60 \
        --query "UserPoolClients[0].ClientId" --output text)
REGION=$(aws configure get region 2>/dev/null || echo "${AWS_REGION:-us-east-1}")

echo "export API='${API%/}'"
echo "export USER_POOL='$POOL'"
echo "export CLIENT_ID='$CLIENT'"
echo "export ISSUER='https://cognito-idp.$REGION.amazonaws.com/$POOL'"
echo "export POSTS_MIGRATE='$(fn PostsMigrate)'"
echo "export TAGGING_MIGRATE='$(fn TaggingMigrate)'"

#!/usr/bin/env bash
set -euo pipefail

fn() { aws lambda list-functions \
        --query "Functions[?starts_with(FunctionName,'$1-')].FunctionName | [0]" --output text; }

API=$(aws apigatewayv2 get-apis \
        --query "Items[?starts_with(Name,'PostsApiGateway')].ApiEndpoint | [0]" --output text)

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

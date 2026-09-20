/**
 * Põe `identity_provider: "cognito"` no ID token.
 *
 * POR QUE ISTO EXISTE
 * ===================
 * `CurrentUser.identityOf` lê a claim `identity_provider` e a entrega a `AuthProvider.fromAlias`. O
 * Keycloak a emite para identidades federadas e a omite para as nativas — e o default de `fromAlias`
 * é KEYCLOAK justamente por isso. O Cognito não a emite nunca, então sem este trigger toda identidade
 * vinda dele seria gravada como KEYCLOAK.
 *
 * Isso não seria cosmético: `uk_accounts_provider_subject` é (provider, subject), e o `sub` do Cognito
 * não é o do Keycloak para a mesma pessoa. As duas viriam como KEYCLOAK com subjects diferentes, e
 * `Authenticatable.link` recusaria a segunda com "já tem conta em KEYCLOAK". Com o provedor certo, o
 * mesmo caso vira account linking — que é o que o desenho quer.
 *
 * POR QUE V1_0
 * ============
 * Porque é o que o tier Lite do Cognito oferece, e é o que basta: V1_0 customiza o ID TOKEN, e o ID
 * token é o bearer desta aplicação (ver o Javadoc de infra/aws/cognito.ts). O V2_0, que customizaria o
 * access token, exige o plano Essentials.
 *
 * O trigger roda em TODA emissão de token. Ele não faz I/O e não decide nada — se um dia precisar
 * decidir, lembre que o custo dele entra no caminho de cada login.
 */
export const handler = async (event) => {
  event.response = {
    claimsOverrideDetails: {
      claimsToAddOrOverride: {
        identity_provider: 'cognito',
      },
    },
  };
  return event;
};

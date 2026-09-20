/// <reference path="../../../.sst/platform/config.d.ts" />

import { seed } from './seed';

/**
 * O provedor de identidade: <b>Cognito</b>, e não mais um Keycloak numa task Fargate.
 *
 * <h2>Por que trocou</h2>
 * Porque tudo de que esta aplicação precisa é um emissor OIDC. Ela é <b>apenas resource server</b>:
 * valida um JWT, lê as roles e deixa o `UserProvisioning` criar o perfil na primeira requisição. Não
 * há tela de login, não há fluxo de consentimento, não há federação social — nada que exigisse o
 * Keycloak em particular.
 *
 * O que o Keycloak custava aqui: uma task Fargate, um ALB e uma imagem no ECR, na ordem de
 * US$ 25/mês, mais um processo a operar. O Cognito resolve o mesmo problema dentro do free tier e sem
 * nada para manter de pé.
 *
 * <h2>O que o Keycloak continua sendo — e onde</h2>
 * Em DEV e em TESTE, tudo. O Dev Services sobe o container e importa
 * `docker/keycloak/realm-axon-posts.json`, os 155 testes usam aquele realm, e nada disso mudou. A
 * troca vale só para o que roda na AWS.
 *
 * Isso cria uma divergência real entre o que os testes provam e o que a produção executa, e ela está
 * concentrada em três linhas do `application-lambda.properties` (o issuer, o client id e o caminho do
 * claim de roles). É o preço declarado, e é o mesmo que quase todo sistema paga ao rodar Keycloak
 * local e um provedor gerenciado em produção.
 *
 * <h2>A DECISÃO QUE CUSTA EXPLICAR: o bearer é o ID TOKEN</h2>
 * Não é distração — é consequência de um fato do Cognito. O <b>access token</b> dele traz
 * {@code sub}, {@code username}, {@code cognito:groups}, {@code scope} e {@code client_id}, e <b>não
 * traz {@code email}</b>. E `UserProvisioning.linkOrCreate` chama `Email.of(identity.email())`: sem
 * e-mail não há como ligar a conta nem criar o perfil.
 *
 * Pôr `email` no access token exige o trigger <i>pre token generation</i> V2_0, e a documentação da
 * AWS é explícita: <i>"Event versions one, two, and three are available in the Essentials and Plus
 * feature plans"</i>. O tier Lite — o do free tier — só recebe V1_0, que customiza o ID token.
 *
 * Então há três caminhos, e o escolhido é o primeiro:
 * <ol>
 *   <li><b>mandar o ID token como bearer</b> — ele tem `email`, `name` e `cognito:groups`. Custa zero,
 *       não precisa de trigger nenhum e não toca uma linha da aplicação;</li>
 *   <li>subir para o tier Essentials e escrever um trigger V2_0 que copia `email` para o access
 *       token. É o caminho ortodoxo, e custa dinheiro por usuário ativo;</li>
 *   <li>mudar `UserProvisioning` para não depender de e-mail — que é justamente o que esta migração
 *       não faz.</li>
 * </ol>
 * O reparo honesto: o ID token é destinado ao CLIENTE, não à API. O que torna isto seguro aqui é que o
 * `aud` dele é o client id e a aplicação o verifica (`quarkus.oidc.token.audience`), então um token
 * emitido para outro client não passa. Se um dia houver mais de um client, ou M2M, o item 2 deixa de
 * ser opcional.
 */
export const users = new sst.aws.CognitoUserPool('Users', {
  // Login por e-mail, que é o que o realm do Keycloak faz e o que o `UserProvisioning` assume ao
  // ligar contas (`findByEmail`).
  usernames: ['email'],

  /*
   * O trigger que põe `identity_provider: "cognito"` no ID token.
   *
   * Sem ele a claim chega nula, `AuthProvider.fromAlias(null)` responde KEYCLOAK — o que é o certo
   * em dev e teste — e toda identidade do Cognito é gravada com o provedor errado. A consequência
   * foi medida: `uk_accounts_provider_subject` é (provider, subject), então a mesma pessoa vinda dos
   * dois emissores colide e `Authenticatable.link` recusa com "já tem conta em KEYCLOAK".
   *
   * `v1` e não `v2`: V1_0 customiza o ID token, que é o bearer desta aplicação, e é o que o tier
   * Lite oferece. V2_0 customizaria o access token e exige o plano Essentials.
   */
  triggers: {
    preTokenGeneration: 'infra/aws/identity/identity-provider.handler',
    preTokenGenerationVersion: 'v1',
  },
  transform: {
    userPool: {
      autoVerifiedAttributes: ['email'],
      // A política default do Cognito exige maiúscula, número e símbolo, e as senhas semeadas
      // são `segredo123` — as MESMAS do realm. Afrouxar aqui é o que mantém o roteiro de teste
      // idêntico nos dois ambientes; num sistema de verdade esta é a primeira linha a apagar.
      passwordPolicy: {
        minimumLength: 8,
        requireLowercase: false,
        requireNumbers: false,
        requireSymbols: false,
        requireUppercase: false,
      },
      schemas: [
        {
          name: 'email',
          attributeDataType: 'String',
          required: true,
          mutable: true,
        },
        {
          name: 'name',
          attributeDataType: 'String',
          required: false,
          mutable: true,
        },
      ],
    },
  },
});

/**
 * O client. Público e sem segredo, como o `axon-posts-api` do realm.
 *
 * `ALLOW_USER_PASSWORD_AUTH` é o que permite ao `e2e.sh` pedir um token com usuário e senha. Note que
 * isso NÃO é o `grant_type=password` do OAuth2 — o endpoint `/oauth2/token` do Cognito só aceita
 * `authorization_code`, `client_credentials` e `refresh_token`. Senha vai pela API própria dele
 * (`cognito-idp initiate-auth`), e é por isso que o roteiro de teste passou a usar a AWS CLI.
 */
export const client = users.addClient('Api', {
  transform: {
    client: {
      explicitAuthFlows: [
        'ALLOW_USER_PASSWORD_AUTH',
        'ALLOW_REFRESH_TOKEN_AUTH',
      ],
      generateSecret: false,
      // SEM mexer na validade dos tokens. Houve aqui um `idTokenValidity: 60` com
      // `tokenValidityUnits`, e o Cognito recusou a criação com
      // `InvalidParameterException: Invalid range for token validity` — declarar a UNIDADE de um
      // token sem declarar o VALOR dele deixa os dois em desacordo. Os defaults (1 h para id e
      // access, 30 dias para refresh) são exatamente o que este ambiente quer.
    },
  },
});

/**
 * Os grupos SÃO as roles. O Cognito escreve `cognito:groups` no token, e o
 * `quarkus.oidc.roles.role-claim-path` do `application-lambda.properties` aponta o Quarkus para lá —
 * o que faz `@RolesAllowed("author")` continuar valendo sem uma linha alterada na aplicação.
 *
 * Os nomes são os literais do realm (`author`, `user`), e têm de ser: `Role.AUTHOR_CLAIM` é uma
 * constante de compilação, e a regra deste projeto é que o literal do `@RolesAllowed` seja o que o
 * emissor emite.
 */
export const pool = { userPoolId: users.id };

export const authorGroup = new aws.cognito.UserGroup('AuthorGroup', {
  ...pool,
  name: 'author',
  description: 'Pode escrever posts',
});

const userGroup = new aws.cognito.UserGroup('UserGroup', {
  ...pool,
  name: 'user',
  description: 'Leitor autenticado',
});

seed('Manuel', 'manuel@example.com', 'Manuel Antunes', ['user', 'author']);
seed('Leitor', 'leitor@example.com', 'Leitor Anonimo', ['user']);
seed('Promovido', 'promovido@example.com', 'Autor Recente', ['user', 'author']);

/**
 * O issuer. O Cognito o publica como
 * {@code https://cognito-idp.<região>.amazonaws.com/<id do pool>}, e o discovery fica em
 * {@code <issuer>/.well-known/openid-configuration} — que é exatamente o que o Quarkus busca.
 *
 * Sem o problema de caixa que o ALB do Keycloak tinha: aqui o host é fixo e o `iss` é montado pelo
 * serviço, não a partir de um cabeçalho.
 */
export const issuer = $interpolate`https://cognito-idp.${aws.getRegionOutput().name}.amazonaws.com/${users.id}`;

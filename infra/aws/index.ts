/// <reference path="../../.sst/platform/config.d.ts" />

import { gateway, postsMigrate, streaming, taggingMigrate } from './compute';
import { postsDb, taggingDb } from './data';
import { router } from './edge';
import { client, issuer, users } from './identity';
import { changes, completed, postEvents, precreated } from './messaging';
import { web } from './web';

/**
 * A fachada da stack: a ordem de carga e os outputs. Nada é criado aqui.
 *
 * <h2>Os pacotes, e a seta que eles desenham</h2>
 * <pre>
 * support/    as DEFINIÇÕES — classes e tipos. Nada aqui cria recurso ao ser importado.
 *   ▲
 * network/    o VPC
 * data/       os DOIS event stores
 * messaging/  o topic, as filas e as bindings — o "exchange" traduzido
 * identity/   o user pool do Cognito, com os usuários do realm semeados
 *   ▲
 * compute/    as SEIS funções e o API Gateway. É o único que conhece todos os outros.
 *   ▲
 * edge/       O ROUTER: uma distribuição do CloudFront na frente de tudo, e as rotas das funções.
 * web/        o cliente Next.js, que consome `compute`, `identity` e `edge`.
 * </pre>
 *
 * A seta aponta sempre para o mesmo lado: quem define não conhece quem instancia, e quem instancia
 * infraestrutura de base não conhece quem a consome. `compute/platform.ts` é o único ponto onde os
 * dois lados se encontram — e é por isso que ele existe separado.
 *
 * <h2>Por que os imports são estáticos aqui e dinâmicos na raiz</h2>
 * O `sst.config.ts` da raiz faz `await import("./infra/aws")` DENTRO de `run()`, porque os módulos
 * abaixo criam recursos no topo do arquivo e um import estático lá os avaliaria antes de `app()` ter
 * rodado. Daqui para baixo não há essa restrição: `run()` já começou.
 */
export const outputs = {
  /**
   * O cliente de teste. É por aqui que se entra.
   *
   * Desde que o site ficou atrás do router, esta URL É a do router — site e API dividem uma
   * distribuição, e com isso dividirão um domínio e um certificado. Ver `edge/index.ts`.
   */
  web: web.url,

  /** O subgraph pelo ROUTER: o mesmo endereço do site, em `/graphql`. É a porta pública dele. */
  subgraph: $interpolate`${router.url}/graphql`,

  /** O subgraph pelo API Gateway — o empacotamento que NÃO faz streaming. Fica para comparação. */
  api: gateway.url,

  /**
   * A MESMA aplicação atrás do Lambda Web Adapter, com Function URL em `RESPONSE_STREAM`. É por
   * aqui que um `Accept: text/event-stream` recebe bytes conforme eles são escritos — o API
   * Gateway não faz streaming em modo nenhum.
   */
  stream: streaming.functionUrl,

  /**
   * O emissor. O token de teste NÃO sai de um endpoint OAuth2 — o Cognito só aceita senha pela API
   * própria dele:
   *
   * ```
   * aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH \
   *   --client-id <clientId> --auth-parameters USERNAME=...,PASSWORD=...
   * ```
   */
  issuer,
  userPool: users.id,
  clientId: client.id,

  /**
   * As migrations RODAM SOZINHAS no deploy (ver `compute/migrations.ts`). Os nomes ficam aqui para
   * quem precisar reexecutar à mão — `infra/scripts/migrate.sh`.
   */
  migrate: {
    posts: postsMigrate.functionName,
    tagging: taggingMigrate.functionName,
  },

  topic: postEvents.arn,
  queues: {
    precreated: precreated.url,
    changes: changes.url,
    completed: completed.url,
  },
  databases: {
    posts: postsDb.host,
    tagging: taggingDb.host,
  },
};

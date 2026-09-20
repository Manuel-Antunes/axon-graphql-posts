/// <reference path="../../../.sst/platform/config.d.ts" />

import { HttpApi, QuarkusFunction, StreamingFunction } from '../support';
import { postsEnvironment } from './environment';
import { codeBucket, platform, projects, sources } from './platform';

/**
 * A API. `posts-api` empacotado com `-Plambda-http`.
 *
 * O `quarkus-amazon-lambda-http` traduz o evento do API Gateway para uma requisição do Vert.x e
 * devolve a resposta pronta. Por ser o MESMO roteador, tudo que hoje é servido em `/graphql` continua
 * sendo: o POST, o SDL, `_service`/`_entities`, o OIDC e a GraphiQL — sem uma linha de código da
 * aplicação envolvida.
 *
 * <h2>O que NÃO atravessa</h2>
 * A resposta é UM `APIGatewayV2HTTPResponse` montado inteiro em memória. A porta de SSE e o WebSocket
 * continuam compilados e registrados, e não entregam nada aqui — medido na stack: `text/event-stream`
 * fica 25 segundos sem receber um byte, e o upgrade de WebSocket morre no load balancer com HTTP 400.
 * <p>
 * A FONTE, essa já não é problema: os handlers que notificam rodam num processor streaming, que lê o
 * event store — então o evento existe e está disponível em todo container. O que falta aqui é só o
 * transporte, e é exatamente isso que a função abaixo tem. Ver `infra/aws/README.md`.
 */
export const api = new QuarkusFunction('PostsApi', {
  platform,
  code: {
    artifact: 'posts-api-http',
    buildCommand: `npx -y nx run "${projects.postsApi}:lambda-http:native-container"`,
    output: 'infra/dist/posts-api-http.zip',
    bucket: codeBucket,
    sources: sources.postsApi,
  },
  environment: postsEnvironment,
  // 30s é o teto do API Gateway; passar disso não adianta nada.
  timeout: 30,
});

export const gateway = new HttpApi('PostsApiGateway', { handler: api });

/**
 * A MESMA aplicação, pela outra porta — a que consegue manter uma conexão aberta.
 *
 * <h2>Por que duas funções e não uma</h2>
 * Porque elas são empacotamentos diferentes do mesmo código, e cada uma serve o que a outra não
 * serve. A de cima traduz evento do API Gateway e devolve a resposta pronta; esta roda como servidor
 * HTTP atrás do Lambda Web Adapter, com a Function URL em `RESPONSE_STREAM`, e é a única onde um
 * `text/event-stream` chega ao cliente conforme é escrito.
 * <p>
 * Conviver custa pouco — uma função só é cobrada quando roda — e permite trocar de porta medindo, em
 * vez de trocar e torcer. O destino é esta virar a única; enquanto a medição não estiver feita, as
 * duas ficam.
 *
 * <h2>O timeout é 300s, e isso É o recurso</h2>
 * A função de API está presa aos 30s do API Gateway. Aqui o teto é o do Lambda, e uma subscription
 * pode durar o que durar até ele. É a diferença entre uma conexão que o transporte corta e uma que a
 * aplicação encerra.
 */
export const streaming = new StreamingFunction('PostsApiStream', {
  platform,
  code: {
    artifact: 'posts-api-stream',
    buildCommand: `npx -y nx run "${projects.postsApi}:lambda-stream:native-container"`,
    output: 'infra/dist/posts-api-stream.zip',
    bucket: codeBucket,
    sources: sources.postsApi,
  },
  environment: postsEnvironment,
  timeout: 300,
});

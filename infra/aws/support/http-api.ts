/// <reference path="../../../.sst/platform/config.d.ts" />

import type { QuarkusFunction } from './functions';

/**
 * O API Gateway na frente de uma função: a API, a integração, a rota, o stage e a permissão — cinco
 * recursos que só fazem sentido juntos, e que por isso são um componente.
 *
 * <h2>Por que não `sst.aws.ApiGatewayV2`</h2>
 * Pela mesma razão de {@code QuarkusFunction}: os componentes do SST criam a função por você, e a
 * função aqui é Java, que o `sst.aws.Function` não suporta.
 */
export class HttpApi extends $util.ComponentResource {
  readonly api: aws.apigatewayv2.Api;
  readonly stage: aws.apigatewayv2.Stage;

  constructor(
    name: string,
    args: { handler: QuarkusFunction },
    opts?: $util.ComponentResourceOptions,
  ) {
    super('axonposts:aws:HttpApi', name, {}, opts);
    const parent = { parent: this };

    this.api = new aws.apigatewayv2.Api(
      name,
      {
        protocolType: 'HTTP',
        /*
         * CORS no API GATEWAY, e não na aplicação.
         *
         * O cliente de `apps/web` roda no CloudFront e fala com esta API direto — origens
         * diferentes, então o navegador faz preflight. Configurado aqui, o `OPTIONS` é
         * respondido pelo próprio API Gateway, SEM invocar a função: um preflight não paga
         * cold start de JVM. A alternativa (`quarkus.http.cors`) faria cada preflight acordar
         * uma Lambda de 72 MB para devolver três cabeçalhos.
         *
         * `allowOrigins: ["*"]` e não a URL do site, e a razão é uma DEPENDÊNCIA CIRCULAR
         * real: o site precisa da URL desta API (para falar com ela) e esta API precisaria da
         * URL do site (para liberá-la). O Pulumi recusaria o ciclo. O curinga é aceitável
         * aqui porque esta API já é pública por desenho — `posts`, `post` e `_entities`
         * respondem sem token — e porque o que autoriza escrita é o header `Authorization`,
         * que origem nenhuma consegue forjar. Note que `allowCredentials` fica FALSO: com
         * `*`, o navegador recusaria a combinação, e este cliente não usa cookie para falar
         * com a API.
         */
        corsConfiguration: {
          allowOrigins: ['*'],
          allowMethods: ['GET', 'POST', 'OPTIONS'],
          // `accept` está aqui por causa do SSE: é o cabeçalho que escolhe a porta do
          // servidor (`text/event-stream`), e um cabeçalho não listado derruba o preflight.
          allowHeaders: ['authorization', 'content-type', 'accept'],
          maxAge: 600,
        },
      },
      parent,
    );

    const integration = new aws.apigatewayv2.Integration(
      `${name}Integration`,
      {
        apiId: this.api.id,
        integrationType: 'AWS_PROXY',
        integrationUri: args.handler.arn,
        // 2.0 é o formato que o `quarkus-amazon-lambda-http` espera
        // (`APIGatewayV2HTTPEvent`). Com 1.0 o evento chega com outra forma e o handler não
        // acha nem o método nem o caminho.
        payloadFormatVersion: '2.0',
        // 30s é o teto do API Gateway, e é o que fixa o timeout da função de API.
        timeoutMilliseconds: 30000,
      },
      parent,
    );

    new aws.apigatewayv2.Route(
      `${name}Route`,
      {
        apiId: this.api.id,
        // `$default` porque a aplicação roteia sozinha: /graphql, /graphql/schema.graphql,
        // /q/health e a GraphiQL são rotas do Vert.x. Declarar caminhos aqui duplicaria o
        // roteador que já existe.
        routeKey: '$default',
        target: $interpolate`integrations/${integration.id}`,
      },
      parent,
    );

    this.stage = new aws.apigatewayv2.Stage(
      `${name}Stage`,
      { apiId: this.api.id, name: '$default', autoDeploy: true },
      parent,
    );

    new aws.lambda.Permission(
      `${name}Permission`,
      {
        action: 'lambda:InvokeFunction',
        function: args.handler.functionName,
        principal: 'apigateway.amazonaws.com',
        sourceArn: $interpolate`${this.api.executionArn}/*/*`,
      },
      parent,
    );

    this.registerOutputs({ url: this.url });
  }

  get url(): $util.Output<string> {
    return this.stage.invokeUrl;
  }
}

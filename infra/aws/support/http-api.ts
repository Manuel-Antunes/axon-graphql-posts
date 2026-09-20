/// <reference path="../../../.sst/platform/config.d.ts" />

import type { QuarkusFunction } from './functions';

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
        corsConfiguration: {
          allowOrigins: ['*'],
          allowMethods: ['GET', 'POST', 'OPTIONS'],
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
        payloadFormatVersion: '2.0',
        timeoutMilliseconds: 30000,
      },
      parent,
    );

    new aws.apigatewayv2.Route(
      `${name}Route`,
      {
        apiId: this.api.id,
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

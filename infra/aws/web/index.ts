/// <reference path="../../../.sst/platform/config.d.ts" />

import { streaming } from '../compute';
import { router } from '../edge';
import { client, issuer } from '../identity';
import { OTEL_COLLECTOR_ARM64 } from '../support';

export const web = new sst.aws.Nextjs('Web', {
  path: 'apps/web',
  buildCommand: 'npx -y nx run web:open-next-build',

  router: { instance: router },

  server: {
    timeout: '60 seconds',
    architecture: 'arm64',
    layers: [OTEL_COLLECTOR_ARM64],
  },

  transform: {
    server: (args) => {
      args.copyFiles = [
        ...((args.copyFiles as { from: string; to?: string }[]) ?? []),
        { from: 'infra/lambda/collector.yaml', to: 'collector.yaml' },
      ];
    },
  },
  environment: {
    INFRA_PROVIDER: 'aws',

    OPENTELEMETRY_COLLECTOR_CONFIG_URI: '/var/task/collector.yaml',
    OTEL_EXPORTER_OTLP_ENDPOINT: 'http://localhost:4318',
    BETTER_STACK_URL: process.env.BETTER_STACK_URL ?? '',
    BETTER_STACK_API_KEY: process.env.BETTER_STACK_API_KEY ?? '',
    NEXT_PUBLIC_GRAPHQL_URL: streaming.functionUrl.apply(
      (url) => `${url.replace(/\/$/, '')}/graphql`,
    ),
    NEXT_PUBLIC_COGNITO_ISSUER: issuer,
    COGNITO_REGION: aws.getRegionOutput().name,
    COGNITO_CLIENT_ID: client.id,
  },
});

import { GraphQLInstrumentation } from '@opentelemetry/instrumentation-graphql';
import { registerOTel } from '@vercel/otel';

registerOTel({
  serviceName: 'axonposts-web',
  spanProcessors: ['auto'],
  propagators: ['auto'],
  instrumentationConfig: {
    fetch: {
      propagateContextUrls: [
        /lambda-url\..*\.on\.aws/,
        /execute-api\..*\.amazonaws\.com/,
      ],
    },
  },
  instrumentations: [
    'fetch',
    new GraphQLInstrumentation({
      allowValues: false,
      ignoreTrivialResolveSpans: true,
    }),
  ],
});

import { registerOTel } from '@vercel/otel';

registerOTel({
  serviceName: 'axonposts-web-edge',
  spanProcessors: ['auto'],
  propagators: ['auto'],
  instrumentations: ['fetch'],
});

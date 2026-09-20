/// <reference path="./.sst/platform/config.d.ts" />
export default $config({
  app(input) {
    return {
      name: 'axonposts',
      removal: input?.stage === 'production' ? 'retain' : 'remove',
      protect: input?.stage === 'production',
      home: 'aws',
      providers: { command: { package: '@pulumi/command', version: '1.2.1' } },
    };
  },
  async run() {
    const infra = await import('./infra/aws');
    return infra.outputs;
  },
});

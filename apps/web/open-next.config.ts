import type { OpenNextConfig } from '@opennextjs/aws/types/open-next';

const config = {
  default: {
    override: {
      wrapper: 'aws-lambda-streaming',
      converter: 'aws-apigw-v2',
      incrementalCache: 's3-lite',
      tagCache: 'dynamodb-lite',
      queue: 'sqs-lite',
      proxyExternalRequest: 'node',
    },
    minify: false,
  },

  imageOptimization: {
    install: {
      packages: ['sharp@0.33.5'],
      arch: 'arm64',
    },
  },

  buildCommand: 'exit 0',
  buildOutputPath: '.',
  appPath: '.',
  packageJsonPath: '../../',
} satisfies OpenNextConfig;

export default config;

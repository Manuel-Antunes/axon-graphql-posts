import path, { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { NextConfig } from 'next';
import { composePlugins, withNx } from '@nx/next';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const monorepoRoot = path.resolve(__dirname, '..', '..');

const nextConfig: NextConfig = {
  outputFileTracingRoot: monorepoRoot,
};

if (process.env.INFRA_PROVIDER === 'aws') {
  nextConfig.output = 'standalone';
}

const plugins = [withNx];

export default composePlugins(...plugins)(nextConfig);

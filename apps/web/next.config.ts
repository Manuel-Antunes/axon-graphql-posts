import path, { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { NextConfig } from 'next';
import { composePlugins, withNx } from '@nx/next';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const monorepoRoot = path.resolve(__dirname, '..', '..');

/**
 * As DUAS linhas que existem por causa do monorepo e do deploy — e nenhuma delas é afinamento.
 *
 * <h2>`outputFileTracingRoot`: onde o Next procura os arquivos que o servidor vai precisar</h2>
 * O rastreamento de dependências parte do diretório do projeto e sobe até achar um lockfile. Num
 * workspace pnpm, `node_modules` é um mar de symlinks para `.pnpm/` na RAIZ — fora de `apps/web`.
 * Sem apontar a raiz explicitamente, o Next escolhe um root por heurística, e o que sai disso é um
 * bundle a que faltam pacotes: o sintoma é `Cannot find module` em runtime, depois de um build que
 * imprimiu sucesso.
 *
 * <h2>`output: "standalone"` só quando o alvo é a AWS</h2>
 * É o formato que o OpenNext empacota. Ligá-lo sempre custaria tempo e um diretório a mais em todo
 * `next dev`/`next build` local, para nada. Quem acende o interruptor é a variável `INFRA_PROVIDER`,
 * declarada no `environment` do componente `sst.aws.Nextjs` (ver `infra/aws/web/index.ts`) — então
 * ela vale exatamente no build que o deploy dispara.
 *
 * O acoplamento entre as duas pontas é real e está declarado nos dois lados: `open-next.config.ts`
 * usa `buildCommand: "exit 0"`, ou seja, ele NÃO roda o `next build` — confia que o alvo `build` do
 * Nx já rodou, e com esta variável ligada. Sem ela, o OpenNext empacotaria um diretório
 * `.next/standalone` que não existe.
 */
const nextConfig: NextConfig = {
  outputFileTracingRoot: monorepoRoot,
};

if (process.env.INFRA_PROVIDER === 'aws') {
  nextConfig.output = 'standalone';
}

const plugins = [
  // Add more Next.js plugins to this list if needed.
  withNx,
];

export default composePlugins(...plugins)(nextConfig);

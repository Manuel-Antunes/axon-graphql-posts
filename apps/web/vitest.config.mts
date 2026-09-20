import { join } from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * O VITEST DESTE APP — e a primeira coisa a dizer é o que ele NÃO é: um segundo empacotador.
 *
 * Quem constrói o cliente é o Next, pelo alvo `build`; o Vite aqui só TRANSFORMA os módulos que um
 * teste importa, em memória. É por isso que não existe `vite.config` neste projeto e sim um
 * `vitest.config`, e o nome não é cosmético — ele é a fronteira entre "isto constrói o app" e "isto
 * roda os testes dele". O `@nx/vitest` infere o alvo dos dois nomes, então a escolha sobra para
 * dizer a verdade sobre o arquivo.
 *
 * <h2>Por que `.mts` e não `.ts`</h2>
 * `apps/web/package.json` não declara `"type": "module"`, então um `vitest.config.ts` seria
 * compilado como CommonJS — e o `@vitejs/plugin-react` é ESM puro. O sintoma seria um
 * `ERR_REQUIRE_ESM` na PARTIDA do Vitest, antes de qualquer teste. A extensão `.mts` força ESM, e
 * com ela `import.meta.dirname` passa a valer (ver o alias abaixo).
 *
 * <h2>O nível: UNIDADE, e o que isso exclui</h2>
 * O que se afirma aqui não sobe servidor, não abre porta e não fala com a API. Tudo que precisa dos
 * dois processos Java e de um broker é do `apps/posts-api-e2e`, que é outro app, com outro alvo
 * (`test:e2e`) — e a separação é o que permite `pnpm test` ser rápido o bastante para rodar sempre.
 */
export default defineConfig({
  /*
   * O tsconfig do app diz `jsx: "preserve"`, porque quem compila JSX em produção é o Next. Num
   * teste não há Next, então o JSX precisa de alguém: este plugin liga o runtime automático do
   * React 19 (`react/jsx-runtime`) na transformação do Vite.
   *
   * A alternativa seria `esbuild: { jsx: "automatic" }` e nenhuma dependência — funciona, e foi
   * descartada porque o plugin é o que a comunidade mantém contra as versões do React, e é dele
   * que virá o suporte ao React Compiler no dia em que o `next.config.ts` o ligar.
   */
  plugins: [react()],

  resolve: {
    /*
     * `@/*` → `src/*`, o mesmo `paths` do `tsconfig.json`. O TypeScript resolve o alias para
     * TIPAR; quem resolve para EXECUTAR é o bundler, e num teste o bundler é o Vite — ele não lê
     * o tsconfig. Sem esta linha todo import de `@/lib/...` num spec falha com
     * `Failed to resolve import`, e a mensagem não menciona tsconfig nenhum.
     *
     * `import.meta.dirname` e não `__dirname`: em `.mts` o segundo não existe, e é a forma que o
     * Vite recomenda desde que passou a carregar o config nativamente.
     */
    alias: { '@': join(import.meta.dirname, 'src') },
  },

  test: {
    /*
     * `jsdom` porque metade do que há para afirmar aqui é componente, e componente precisa de
     * DOM. O custo é pago por arquivo, então um spec de função pura (`lib/auth/claims`) paga por
     * um DOM que ele não usa — cabe, e a alternativa (dois ambientes, escolhidos por padrão de
     * caminho) seria configuração para economizar milissegundos.
     */
    environment: 'jsdom',

    /*
     * O sufixo `.spec` e mais nada: `src/gql/` é GERADO e `.next/` é build, e nem um nem outro
     * tem spec dentro — mas o default do Vitest também varreria `apps/web/node_modules`, que num
     * workspace pnpm é um mar de symlinks para a raiz.
     */
    include: ['src/**/*.spec.{ts,tsx}'],

    // O Testing Library monta num container que ele mesmo cria; sem desmontar, o segundo teste
    // de um arquivo consulta o DOM do primeiro junto. Ver o arquivo.
    setupFiles: ['./vitest.setup.ts'],

    // O mesmo par do `apps/posts-api-e2e`: na máquina, o relatório legível; na esteira, também o
    // XML que o job guarda como artefato. `target/` já é ignorado pelo git em todo o monorepo.
    reporters: process.env.CI ? ['default', 'junit'] : ['default'],
    outputFile: { junit: 'target/test-results/junit.xml' },
  },
});

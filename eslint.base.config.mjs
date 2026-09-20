// @ts-check
import nx from '@nx/eslint-plugin';
import prettier from 'eslint-config-prettier/flat';

/**
 * A BASE do ESLint — o que TODO sistema deste workspace herda, e nada além disso.
 *
 * Cada sistema tem o seu `eslint.config.mjs`, que começa por `...baseConfig` e acrescenta só o que é
 * dele: o Next no `apps/web`, as regras do Vitest no `apps/posts-api-e2e`, as do SST no `infra`. É a
 * forma que o Nx gera, e ela existe por um motivo prático — em flat config o ESLint usa UM arquivo,
 * o mais próximo do diretório de onde ele roda, e cada alvo `lint` roda com `cwd` no próprio projeto.
 * Não há herança automática entre arquivos; a herança é este import.
 *
 * O que fica AQUI é o que não pode divergir entre sistemas: o plugin do Nx, os ignores que valem em
 * toda parte, o `@nx/enforce-module-boundaries` (que lê o grafo do workspace INTEIRO, então escrevê-lo
 * duas vezes seria a mesma regra em dois lugares) e as regras de TypeScript que descrevem como este
 * monorepo escreve código.
 */
export default [
  {
    /*
     * Um bloco só com `ignores` é ignore GLOBAL. Os padrões levam `**` na frente porque são
     * resolvidos a partir do diretório de onde o ESLint roda, que é a raiz do PROJETO e não a do
     * workspace.
     */
    ignores: [
      // O Maven escreve em `target/` de oito módulos; nada ali é escrito por alguém.
      '**/target/**',
      // Saída de build e de empacotamento.
      '**/dist/**',
      '**/.next/**',
      '**/.open-next/**',
      '**/out-tsc/**',
      // Caches de ferramenta.
      '**/.nx/**',
      '**/.sst/**',
      '**/vite.config.*.timestamp*',
      '**/vitest.config.*.timestamp*',
      // Declarações GERADAS: o `sst-env.d.ts` sai do `sst deploy` e o `next-env.d.ts` do Next.
      // As regras aqui valem para o que alguém escreveu.
      '**/sst-env.d.ts',
      '**/next-env.d.ts',
    ],
  },

  // O `flat/base` traz só o plugin `@nx`; são as duas linhas abaixo que ligam as regras.
  ...nx.configs['flat/base'],
  ...nx.configs['flat/typescript'],
  ...nx.configs['flat/javascript'],

  {
    files: ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.jsx'],
    rules: {
      '@nx/enforce-module-boundaries': [
        'error',
        {
          enforceBuildableLibDependency: true,
          /*
           * A config de um sistema IMPORTA esta aqui, e esse é um import relativo que atravessa a
           * fronteira do projeto — exatamente o que a regra proíbe. Sem esta exceção, compor as
           * configs em vez de duplicá-las seria um erro de lint.
           */
          allow: ['^.*/eslint(\\.base)?\\.config\\.[cm]?[jt]s$'],
          /*
           * TAGS: uma só, e permissiva, DE PROPÓSITO. Hoje não há lib JS compartilhada neste
           * monorepo — o domínio compartilhado é Java, e quem o separa é o reator do Maven. Uma
           * matriz de `scope:`/`type:` seria fronteira desenhada contra dependência que não existe,
           * e regra que nunca dispara é regra que ninguém mantém.
           *
           * O que a regra já entrega sem tag nenhuma é o que importa hoje, e nenhum compilador
           * confere: import relativo que sai do próprio projeto, ciclo entre projetos, e import do
           * MIOLO de um pacote em vez do ponto de entrada dele.
           */
          depConstraints: [{ sourceTag: '*', onlyDependOnLibsWithTags: ['*'] }],
        },
      ],
    },
  },

  {
    files: [
      '**/*.ts',
      '**/*.tsx',
      '**/*.cts',
      '**/*.mts',
      '**/*.js',
      '**/*.jsx',
      '**/*.cjs',
      '**/*.mjs',
    ],
    rules: {
      // Um tipo importado como valor entra no bundle como import de runtime. `import type` some na
      // compilação — e é o que mantém um import de tipo de não criar dependência de verdade.
      '@typescript-eslint/consistent-type-imports': 'warn',
      '@typescript-eslint/no-namespace': 'off',
      '@typescript-eslint/consistent-type-definitions': 'error',
    },
  },

  /*
   * O PRETTIER POR ÚLTIMO, e a posição é a regra inteira: este bloco só DESLIGA coisas, então ele
   * precisa vir depois de tudo que possa ligá-las.
   *
   * É `eslint-config-prettier` e não `eslint-plugin-prettier`, e a diferença importa. O plugin
   * roda o Prettier COMO SE FOSSE uma regra de lint: cada divergência de formatação vira um erro
   * do ESLint, com a mensagem dele, e o `--fix` passa a reformatar. Custa uma passada do Prettier
   * por arquivo dentro do lint e, pior, transforma "está fora do formato" — que é máquina que
   * resolve — em ruído no meio dos achados que exigem alguém ler. É a mesma divisão que o lado
   * Java já faz entre Spotless e Error Prone.
   *
   * O config só apaga as regras de ESTILO do ESLint (as do preset do Next, as do `typescript-eslint`)
   * para que as duas ferramentas não disputem a mesma linha. Tudo que aponta DEFEITO — o
   * `enforce-module-boundaries`, as do Vitest, as do GraphQL, as de correção do Tailwind —
   * atravessa intacto, porque o Prettier não tem opinião sobre nenhuma delas.
   */
  prettier,
];

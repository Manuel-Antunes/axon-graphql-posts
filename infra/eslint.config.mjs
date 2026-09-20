// @ts-check
import baseConfig from '../eslint.base.config.mjs';

/**
 * O ESLint da INFRAESTRUTURA — SST sobre Pulumi.
 *
 * As duas regras ajustadas aqui não são ruído aceito: nas duas o SST está certo e a regra é que não
 * descreve o que ele faz. Foram medidas antes — o diretório tinha 27 violações e 25 eram delas.
 */
export default [
  ...baseConfig,

  {
    files: ['**/*.ts'],
    rules: {
      /*
       * Eram 21 das 27, e é a única sistemática.
       *
       * O `/// <reference path="../../../.sst/platform/config.d.ts" />` no topo de cada arquivo é
       * como o SST põe os tipos GERADOS dele em escopo: `$config`, `$app`, `$dev`, `sst.aws.*` e o
       * global `aws` do provider Pulumi. Não há import equivalente porque não há módulo — é um
       * arquivo de declarações. Trocar por `import` deixaria o arquivo sem tipo nenhum, que é o
       * oposto do que a regra existe para conseguir.
       */
      '@typescript-eslint/triple-slash-reference': 'off',

      /*
       * CONFIGURADA, não desligada. `interface MigratorArgs extends Omit<QuarkusFunctionArgs,
       * "timeout" | "memory"> {}` é o padrão idiomático de dar NOME a um tipo derivado numa API
       * pública — é o que faz o componente se documentar sozinho. `with-single-extends` é a opção
       * que a própria regra tem para isso, então uma interface vazia que não estende nada continua
       * sendo erro.
       */
      '@typescript-eslint/no-empty-object-type': [
        'error',
        { allowInterfaces: 'with-single-extends' },
      ],

      /*
       * A predecessora DEPRECIADA da regra acima, e ela não tem a opção. Ligada junto, acusava as
       * mesmas duas linhas uma segunda vez — o mesmo defeito contado em dobro é o jeito mais rápido
       * de alguém parar de ler a saída do lint.
       */
      '@typescript-eslint/no-empty-interface': 'off',
    },
  },

  {
    // `dist/` já está na base; aqui é o nome que este diretório usa para os zips das funções.
    ignores: ['dist/**'],
  },
];

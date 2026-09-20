// @ts-check
import vitest from '@vitest/eslint-plugin';

import baseConfig from '../../eslint.base.config.mjs';

/**
 * O ESLint deste app — a BASE mais o que só vale para um app de TESTE.
 *
 * Compor em vez de repetir é o mesmo motivo do `apps/web`: é da base que vem o
 * `@nx/enforce-module-boundaries`, a única regra que fala de arquitetura, e a mesma lista em dois
 * lugares se separa no primeiro ajuste.
 *
 * O que ele acrescenta não é estilo. As regras do `@vitest/eslint-plugin` ligadas aqui pegam os
 * quatro jeitos de uma suíte MENTIR que nenhum compilador vê — e num app cujo trabalho inteiro é
 * afirmar coisas sobre dois processos e um broker, uma suíte que mente é pior que suíte nenhuma:
 * ela fica verde enquanto a saga não fecha.
 */
export default [
  ...baseConfig,

  {
    files: ['src/specs/**/*.e2e.spec.ts'],
    plugins: { vitest },
    rules: {
      /*
       * Um `it` sem `expect` PASSA. Num teste que espera consistência eventual, é o modo de
       * falhar mais provável que existe: alguém troca uma afirmação por um `await` e a suíte
       * segue verde sem afirmar nada.
       */
      'vitest/expect-expect': 'error',

      // Um `it.only` esquecido some com o resto da suíte, e o relatório não acusa: ele diz
      // "1 passed" com a mesma cara de quem rodou tudo.
      'vitest/no-focused-tests': 'error',
      // Um `it.skip` esquecido é o mesmo problema, mais silencioso ainda.
      'vitest/no-disabled-tests': 'warn',

      // Dois `it` com o mesmo nome num arquivo cujos testes são ORDENADOS e compartilham
      // estado é convite a ler o relatório errado.
      'vitest/no-identical-title': 'error',

      // `expect` fora de um teste roda na coleta, não na execução — a falha aparece como erro
      // de arquivo, sem dizer qual afirmação era.
      'vitest/no-standalone-expect': 'error',

      /*
       * `expect(x).toBe(true)` em vez de `toBeTruthy`, etc. Vale aqui porque a mensagem de
       * falha de um matcher específico é a diferença entre "esperava true" e "esperava a
       * versão 2, veio 1" — e essa mensagem é o que alguém vai ler às 3 da manhã.
       */
      'vitest/prefer-to-be': 'error',
    },
  },

  {
    /*
     * `src/support/` NÃO é spec, e é por isso que as regras acima não o alcançam: ali mora o
     * mecanismo (a stack, os dois processos, o event store, o broker). O `include` do Vitest faz
     * a mesma separação — ver `vitest.config.ts`.
     */
    files: ['src/support/**/*.ts', 'src/global-setup.ts'],
    rules: {
      // O mecanismo CONVERSA com processos e containers: `console.log` aqui é a saída do
      // provisionamento, não depuração esquecida.
      'no-console': 'off',
    },
  },
];

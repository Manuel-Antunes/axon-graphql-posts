import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Desmontar o que o teste anterior montou — e isto NÃO é auto-limpeza esquecida.
 *
 * O `@testing-library/react` registra `afterEach(cleanup)` sozinho, mas só quando `afterEach` é
 * GLOBAL. Aqui ele não é: os specs importam `describe`/`it`/`expect` de `"vitest"` explicitamente,
 * como os do `apps/posts-api-e2e`, porque um import é o que diz de onde vem a função que está sendo
 * chamada. O preço dessa escolha é esta linha.
 *
 * O que ela evita: o `render` do Testing Library anexa um container ao `document.body` e as consultas
 * (`screen.getByText`) olham o `body` INTEIRO. Sem desmontar, o segundo teste de um arquivo enxerga o
 * DOM do primeiro — e o modo de falhar é o pior que há, porque não é uma falha: é um
 * `getByText` que encontra DOIS elementos e quebra com "found multiple elements", numa asserção que
 * está correta, apontando para um teste que não tem nada de errado.
 */
afterEach(() => {
  cleanup();
});

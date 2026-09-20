import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { VersionBadge } from './version-badge';

/**
 * A VERSÃO DO POST — e o que se afirma aqui é SEMÂNTICA DE SAGA, não texto de etiqueta.
 *
 * Neste sistema a versão não é número de auditoria: ela é o estado da coreografia entre os dois
 * serviços. `PostPreCreated` nasce na v1, sem tag; quem decide a primeira tag é o `apps/tagging`, e o
 * `PostCreated` que volta leva o agregado à v2. Daí para a frente, toda edição é uma versão a mais.
 *
 * Errar o corte entre os três casos não quebra compilação nem teste de backend — quebra a única
 * página que existe para OBSERVAR a saga, e quebra dizendo "saga fechada" sobre um post que ainda
 * está esperando o outro serviço responder.
 *
 * Este arquivo também é o que prova que o `vitest.config.mts` funciona de ponta a ponta: JSX de React
 * 19 transformado sem o Next, `jsdom` de pé, e o alias `@/` resolvido — o componente importa
 * `@/components/ui/badge` e `@/lib/utils`.
 */
describe('VersionBadge', () => {
  it('diz que a v1 está AGUARDANDO a tag do outro serviço', () => {
    render(<VersionBadge version={1} />);

    expect(screen.getByText('v1 · aguardando tag')).toBeDefined();
  });

  it('diz que a v2 FECHOU a saga', () => {
    render(<VersionBadge version={2} />);

    expect(screen.getByText('v2 · saga fechada')).toBeDefined();
  });

  it.each([3, 4, 17])('trata a v%i como edição, depois da saga', (version) => {
    render(<VersionBadge version={version} />);

    expect(screen.getByText(`v${version} · editado`)).toBeDefined();
  });

  it('trata a versão 0 como a v1: ainda não houve saga', () => {
    // `version <= 1`, e não `=== 1`. Um post lido de um cache vazio ou de uma projeção que ainda
    // não materializou chega com 0 — e 0 não é "editado", é o mesmo "ainda não fechou" da v1.
    render(<VersionBadge version={0} />);

    expect(screen.getByText('v1 · aguardando tag')).toBeDefined();
  });

  it('mantém a classe de quem o usa, sem perder a própria', () => {
    // O `cn` funde as duas listas; trocá-lo por uma concatenação crua faria a classe de fora
    // passar a CONVIVER com a de dentro em vez de vencê-la, e o defeito seria visual.
    render(<VersionBadge version={2} className="ml-auto" />);

    const badge = screen.getByText('v2 · saga fechada');

    expect(badge.className).toContain('ml-auto');
    expect(badge.className).toContain('font-mono');
  });
});

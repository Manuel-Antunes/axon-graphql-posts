import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { VersionBadge } from './version-badge';

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
    render(<VersionBadge version={0} />);

    expect(screen.getByText('v1 · aguardando tag')).toBeDefined();
  });

  it('mantém a classe de quem o usa, sem perder a própria', () => {
    render(<VersionBadge version={2} className="ml-auto" />);

    const badge = screen.getByText('v2 · saga fechada');

    expect(badge.className).toContain('ml-auto');
    expect(badge.className).toContain('font-mono');
  });
});

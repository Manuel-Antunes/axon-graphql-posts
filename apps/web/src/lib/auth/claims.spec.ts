import { describe, expect, it } from 'vitest';

import {
  AUTHOR_GROUP,
  isAuthor,
  publicSession,
  readClaims,
  toSession,
} from './claims';

function jwt(payload: Record<string, unknown>): string {
  const segment = (value: object) =>
    Buffer.from(JSON.stringify(value)).toString('base64url');
  return [
    segment({ alg: 'RS256', typ: 'JWT' }),
    segment(payload),
    'assinatura-que-este-lado-nao-confere',
  ].join('.');
}

const EXPIRES_AT_SECONDS = 1_800_000_000;

const claims = {
  'sub': 'b3f1c0de-0000-4000-8000-000000000001',
  'email': 'manuel@example.com',
  'name': 'Manuel Antunes',
  'exp': EXPIRES_AT_SECONDS,
  'iss': 'https://cognito-idp.us-east-1.amazonaws.com/us-east-1_abc123',
  'aud': 'axon-posts-web',
  'cognito:groups': [AUTHOR_GROUP],
  'identity_provider': 'cognito',
};

describe('readClaims', () => {
  it('decodifica o payload de um ID token', () => {
    expect(readClaims(jwt(claims))).toEqual(claims);
  });

  it('lê acentos como UTF-8, e não como bytes soltos', () => {
    const token = jwt({
      ...claims,
      name: 'João Ámaro',
      email: 'joão@example.com',
    });

    expect(readClaims(token)?.name).toBe('João Ámaro');
  });

  it('decodifica o alfabeto base64URL, que não é o do base64', () => {
    const token = jwt({ ...claims, name: '~~~?~~~?' });

    expect(readClaims(token)?.name).toBe('~~~?~~~?');
  });

  it.each([
    ['não tem três partes', 'cabecalho.payload'],
    ['não é base64 nenhum', 'a.!!!não-é-base64!!!.c'],
    ['não é JSON', `a.${Buffer.from('nem json é').toString('base64url')}.c`],
  ])('devolve null quando o token %s', (_caso, token) => {
    expect(readClaims(token)).toBeNull();
  });

  it.each([
    ['sem sub', { ...claims, sub: undefined }],
    ['sem exp', { ...claims, exp: undefined }],
    ['com exp em texto', { ...claims, exp: String(EXPIRES_AT_SECONDS) }],
  ])('devolve null para um payload %s', (_caso, payload) => {
    expect(readClaims(jwt(payload))).toBeNull();
  });
});

describe('toSession', () => {
  it('converte o exp de SEGUNDOS para milissegundos', () => {
    expect(toSession(jwt(claims))?.expiresAt).toBe(EXPIRES_AT_SECONDS * 1000);
  });

  it('traz os grupos do pool como as roles da sessão', () => {
    expect(toSession(jwt(claims))?.user).toEqual({
      sub: claims.sub,
      email: claims.email,
      name: claims.name,
      groups: [AUTHOR_GROUP],
      provider: 'cognito',
    });
  });

  it('cai no e-mail e depois no sub quando o token não traz nome', () => {
    const semNome = toSession(jwt({ ...claims, name: undefined }));
    const semNomeNemEmail = toSession(
      jwt({ ...claims, name: undefined, email: undefined }),
    );

    expect(semNome?.user.name).toBe(claims.email);
    expect(semNomeNemEmail?.user.name).toBe(claims.sub);
  });

  it('diz `desconhecido` quando o trigger não pôs o provedor', () => {
    expect(
      toSession(jwt({ ...claims, identity_provider: undefined }))?.user
        .provider,
    ).toBe('desconhecido');
  });

  it('devolve null para um token que não decodifica', () => {
    expect(toSession('isto.não.é')).toBeNull();
  });
});

describe('publicSession', () => {
  it('TIRA o token: é o que atravessa a fronteira servidor → cliente', () => {
    const server = toSession(jwt(claims));

    const publica = publicSession(server);

    expect(publica).not.toHaveProperty('idToken');
    expect(publica).toEqual({
      expiresAt: server?.expiresAt,
      user: server?.user,
    });
  });

  it('devolve null quando não há sessão', () => {
    expect(publicSession(null)).toBeNull();
  });
});

describe('isAuthor', () => {
  it('reconhece o grupo `author`, o mesmo literal que a API exige', () => {
    expect(AUTHOR_GROUP).toBe('author');
    expect(isAuthor(publicSession(toSession(jwt(claims))))).toBe(true);
  });

  it('não promove quem só tem outros grupos', () => {
    const leitor = toSession(jwt({ ...claims, 'cognito:groups': ['user'] }));

    expect(isAuthor(publicSession(leitor))).toBe(false);
  });

  it('não estoura sem sessão', () => {
    expect(isAuthor(null)).toBe(false);
  });
});

import { describe, expect, it } from 'vitest';

import {
  AUTHOR_GROUP,
  isAuthor,
  publicSession,
  readClaims,
  toSession,
} from './claims';

/**
 * As claims do ID token — e o que se afirma aqui é FRONTEIRA, não formatação.
 *
 * Três coisas passam por este arquivo e não são exercitadas em lugar nenhum:
 *
 * <ol>
 *   <li>a DECODIFICAÇÃO base64url, que não é `atob` puro — o alfabeto do JWT troca `+/` por `-_` e
 *       corta o padding, e o payload é UTF-8;</li>
 *   <li>o que `publicSession` TIRA. É a linha que impede o bearer de chegar ao navegador, e apagá-la
 *       não quebra compilação nem tela: o app continua funcionando, com o token no bundle;</li>
 *   <li>o literal `author`, que é o MESMO que o `@RolesAllowed(Role.AUTHOR_CLAIM)` da API espera.
 *       Os dois lados têm de dizer a mesma palavra, e nenhum compilador confere isso.</li>
 * </ol>
 *
 * O que NÃO se afirma aqui é assinatura, e isso é do desenho: `readClaims` decodifica, não valida —
 * quem valida é o resource server, que tem o JWKS. Ver o Javadoc de `claims.ts`.
 */

/** Um JWT de mentira: cabeçalho e payload de verdade, assinatura que ninguém confere. */
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
    // `atob` devolve uma string BINÁRIA, um caractere por byte. Sem o `TextDecoder` do
    // `decodeSegment`, "João" chegaria como "JoÃ£o" — e o defeito apareceria no nome de quem
    // está logado, não num erro.
    const token = jwt({
      ...claims,
      name: 'João Ámaro',
      email: 'joão@example.com',
    });

    expect(readClaims(token)?.name).toBe('João Ámaro');
  });

  it('decodifica o alfabeto base64URL, que não é o do base64', () => {
    // O JWT troca `+` por `-` e `/` por `_`, e corta o padding `=`. `atob` não aceita nenhuma
    // das três coisas — é por isso que `decodeSegment` desfaz as três antes de chamá-lo. Um
    // payload com `~~~?` força os dois caracteres trocados a aparecerem na codificação.
    const token = jwt({ ...claims, name: '~~~?~~~?' });

    expect(readClaims(token)?.name).toBe('~~~?~~~?');
  });

  it.each([
    ['não tem três partes', 'cabecalho.payload'],
    ['não é base64 nenhum', 'a.!!!não-é-base64!!!.c'],
    ['não é JSON', `a.${Buffer.from('nem json é').toString('base64url')}.c`],
  ])('devolve null quando o token %s', (_caso, token) => {
    // Devolver `null` e não estourar: um cookie corrompido é sessão ausente, não erro de
    // aplicação — quem chama já trata o `null` como "ninguém logado".
    expect(readClaims(token)).toBeNull();
  });

  it.each([
    ['sem sub', { ...claims, sub: undefined }],
    ['sem exp', { ...claims, exp: undefined }],
    ['com exp em texto', { ...claims, exp: String(EXPIRES_AT_SECONDS) }],
  ])('devolve null para um payload %s', (_caso, payload) => {
    // As duas claims que o resto do arquivo USA. Um token decodificável sem elas passaria daqui
    // e quebraria adiante, num `session.user.sub` indefinido.
    expect(readClaims(jwt(payload))).toBeNull();
  });
});

describe('toSession', () => {
  it('converte o exp de SEGUNDOS para milissegundos', () => {
    // O `exp` do JWT é em segundos; `Date.now()` é em milissegundos. Sem o ×1000 a sessão
    // nasceria expirada em 1970 e o `readSession` a descartaria — o sintoma seria "o login não
    // persiste", que é o mesmo sintoma de meia dúzia de outras causas.
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
    // `identity_provider` vem do trigger V1_0 do Cognito. Ele é o que separa uma conta do
    // Cognito de uma do Keycloak no `AuthProvider` da API — e a ausência dele é um estado
    // possível (um token emitido antes do trigger existir), não um erro.
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

    // `not.toHaveProperty` e não `toBeUndefined`: o que se afirma é que a CHAVE não existe. Um
    // `idToken: undefined` serializaria igual e passaria por um `toBeUndefined` — e o dia em que
    // alguém copiasse o objeto inteiro com um `...server`, o token voltaria calado.
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
    // Se este literal divergir do `Role.AUTHOR_CLAIM` do `posts-api`, a interface libera o botão
    // e a mutation responde FORBIDDEN — ou o contrário, que é pior.
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

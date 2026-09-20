/**
 * As claims que este cliente lê do token, e a leitura delas.
 *
 * <h2>Por que o bearer é o ID TOKEN</h2>
 * Porque é o que a API exige, e isso não é escolha daqui. O access token do Cognito não traz
 * {@code email}, e o `UserProvisioning` do `posts-api` chama `Email.of(identity.email())` para criar
 * ou LIGAR o perfil na primeira requisição. Pôr `email` no access token exigiria o trigger
 * <i>pre token generation</i> V2_0, que só existe nos planos Essentials/Plus.
 *
 * O que torna isso seguro do lado do servidor é `quarkus.oidc.token.audience`: o `aud` de um ID token
 * é o client id, e a API o confere. Ver o Javadoc de `infra/aws/identity/index.ts`.
 *
 * <h2>Decodificar não é validar</h2>
 * Esta função NÃO verifica assinatura, e não deve: quem valida é o resource server, que tem o JWKS.
 * Aqui as claims servem para duas coisas sem consequência de segurança — mostrar quem está logado e
 * saber QUANDO o token expira, para renová-lo antes.
 */
export interface IdTokenClaims {
  'sub': string;
  'email'?: string;
  'name'?: string;
  'exp': number;
  'iss': string;
  'aud'?: string;
  /** Os grupos do pool. São as roles: `author` e `user`, os mesmos literais do realm do Keycloak. */
  'cognito:groups'?: string[];
  /** Posto pelo trigger V1_0 de `infra/aws/identity/identity-provider.mjs`. */
  'identity_provider'?: string;
}

/** O que a aplicação mostra e usa. É o que atravessa a fronteira servidor → cliente. */
export interface SessionUser {
  sub: string;
  email: string;
  name: string;
  groups: string[];
  provider: string;
}

/**
 * O que o NAVEGADOR sabe sobre a sessão — e note o que não está aqui: o token.
 *
 * Desde que o cliente passou a falar pelo proxy (`app/api/graphql/route.ts`), quem põe o
 * `Authorization` no header é o servidor, lendo o cookie `httpOnly`. O ID token deixou de precisar
 * chegar à memória da página, e por isso deixou de estar neste tipo: uma credencial que não atravessa
 * a fronteira não pode vazar por ela.
 *
 * O que sobra é o suficiente para a interface: quem está logado, com que grupos, e até quando — que
 * é o relógio da renovação.
 */
export interface Session {
  /** Época em milissegundos. Vem do `exp`, que é em SEGUNDOS — daí o ×1000. */
  expiresAt: number;
  user: SessionUser;
}

/** A sessão do lado do SERVIDOR, que é a única que precisa do bearer. */
export type ServerSession = Session & { idToken: string };

function decodeSegment(segment: string): unknown {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(
    base64.length + ((4 - (base64.length % 4)) % 4),
    '=',
  );
  // `atob` existe nos dois lados desde o Node 16; usá-lo evita um ramo servidor/navegador aqui.
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes));
}

export function readClaims(jwt: string): IdTokenClaims | null {
  const parts = jwt.split('.');
  if (parts.length !== 3) return null;
  try {
    const claims = decodeSegment(parts[1]) as IdTokenClaims;
    return typeof claims?.sub === 'string' && typeof claims?.exp === 'number'
      ? claims
      : null;
  } catch {
    return null;
  }
}

export function toSession(idToken: string): ServerSession | null {
  const claims = readClaims(idToken);
  if (!claims) return null;
  return {
    idToken,
    expiresAt: claims.exp * 1000,
    user: {
      sub: claims.sub,
      email: claims.email ?? '',
      name: claims.name ?? claims.email ?? claims.sub,
      groups: claims['cognito:groups'] ?? [],
      provider: claims.identity_provider ?? 'desconhecido',
    },
  };
}

/** Tira o token: é o que atravessa a fronteira servidor → cliente. */
export function publicSession(session: ServerSession | null): Session | null {
  if (!session) return null;
  return { expiresAt: session.expiresAt, user: session.user };
}

/** `author` é o literal que o `@RolesAllowed(Role.AUTHOR_CLAIM)` da API espera. */
export const AUTHOR_GROUP = 'author';

export function isAuthor(session: Session | null): boolean {
  return session?.user.groups.includes(AUTHOR_GROUP) ?? false;
}

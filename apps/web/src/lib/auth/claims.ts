export interface IdTokenClaims {
  'sub': string;
  'email'?: string;
  'name'?: string;
  'exp': number;
  'iss': string;
  'aud'?: string;
  'cognito:groups'?: string[];
  'identity_provider'?: string;
}

export interface SessionUser {
  sub: string;
  email: string;
  name: string;
  groups: string[];
  provider: string;
}

export interface Session {
  expiresAt: number;
  user: SessionUser;
}

export type ServerSession = Session & { idToken: string };

function decodeSegment(segment: string): unknown {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(
    base64.length + ((4 - (base64.length % 4)) % 4),
    '=',
  );
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

export function publicSession(session: ServerSession | null): Session | null {
  if (!session) return null;
  return { expiresAt: session.expiresAt, user: session.user };
}

export const AUTHOR_GROUP = 'author';

export function isAuthor(session: Session | null): boolean {
  return session?.user.groups.includes(AUTHOR_GROUP) ?? false;
}

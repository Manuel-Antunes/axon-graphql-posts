import 'server-only';

import { cookies } from 'next/headers';

import type { ServerSession } from './claims';
import { toSession } from './claims';

const ID_TOKEN = 'axonposts.id_token';
const REFRESH_TOKEN = 'axonposts.refresh_token';

const baseCookie = {
  httpOnly: true,
  sameSite: 'lax' as const,
  path: '/',
  secure: process.env.NODE_ENV === 'production',
};

export async function storeSession(tokens: {
  idToken: string;
  refreshToken?: string;
  expiresIn: number;
}) {
  const jar = await cookies();
  jar.set(ID_TOKEN, tokens.idToken, {
    ...baseCookie,
    maxAge: tokens.expiresIn,
  });
  if (tokens.refreshToken) {
    jar.set(REFRESH_TOKEN, tokens.refreshToken, {
      ...baseCookie,
      maxAge: 60 * 60 * 24 * 30,
    });
  }
}

export async function clearSession() {
  const jar = await cookies();
  jar.delete(ID_TOKEN);
  jar.delete(REFRESH_TOKEN);
}

export async function storedRefreshToken(): Promise<string | null> {
  return (await cookies()).get(REFRESH_TOKEN)?.value ?? null;
}

export async function readSession(): Promise<ServerSession | null> {
  const raw = (await cookies()).get(ID_TOKEN)?.value;
  if (!raw) return null;
  const session = toSession(raw);
  if (!session) return null;
  return session.expiresAt <= Date.now() ? null : session;
}

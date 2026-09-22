'use server';

import { z } from 'zod';

import type { Session } from '@/lib/auth/claims';
import { publicSession, toSession } from '@/lib/auth/claims';
import {
  clearSession,
  readSession,
  storedRefreshToken,
  storeSession,
} from '@/lib/auth/cookies';
import { IdentityError } from '@/lib/auth/identity';
import { refreshTokens, signInWithPassword } from '@/lib/auth/provider';

export interface SignInState {
  status: 'idle' | 'error' | 'ok';
  message?: string;
  code?: string;
  email?: string;
  next?: string;
}

const credentials = z.object({
  email: z.email('Informe um e-mail válido.'),
  password: z.string().min(1, 'Informe a senha.'),
});

export async function signIn(
  _previous: SignInState,
  formData: FormData,
): Promise<SignInState> {
  const parsed = credentials.safeParse({
    email: String(formData.get('email') ?? '').trim(),
    password: String(formData.get('password') ?? ''),
  });

  if (!parsed.success) {
    return {
      status: 'error',
      code: 'ValidationError',
      message: parsed.error.issues[0]?.message ?? 'Credenciais inválidas.',
      email: String(formData.get('email') ?? ''),
    };
  }

  try {
    const tokens = await signInWithPassword(
      parsed.data.email,
      parsed.data.password,
    );
    await storeSession(tokens);
  } catch (error) {
    const failure =
      error instanceof IdentityError
        ? error
        : new IdentityError(
            'NetworkError',
            'Não foi possível falar com o provedor de identidade.',
          );
    return {
      status: 'error',
      code: failure.code,
      message: failure.message,
      email: parsed.data.email,
    };
  }

  return { status: 'ok', next: String(formData.get('next') ?? '/feed') };
}

export async function signOut() {
  await clearSession();
}

export async function currentSession(): Promise<Session | null> {
  const session = await readSession();
  if (session && session.expiresAt - Date.now() > 60_000)
    return publicSession(session);
  return refreshSession();
}

export async function refreshSession(): Promise<Session | null> {
  const refreshToken = await storedRefreshToken();
  if (!refreshToken) {
    await clearSession();
    return null;
  }

  try {
    const tokens = await refreshTokens(refreshToken);
    await storeSession({ ...tokens, refreshToken });
    return publicSession(toSession(tokens.idToken));
  } catch {
    await clearSession();
    return null;
  }
}

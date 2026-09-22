import 'server-only';

import type { AuthTokens, PasswordIdentityProvider } from './identity';
import { cognitoProvider } from './cognito';
import { oidcProvider } from './oidc';

export function identityProvider(): PasswordIdentityProvider {
  return oidcProvider.isConfigured() ? oidcProvider : cognitoProvider;
}

export function signInWithPassword(
  email: string,
  password: string,
): Promise<AuthTokens> {
  return identityProvider().signInWithPassword(email, password);
}

export function refreshTokens(refreshToken: string): Promise<AuthTokens> {
  return identityProvider().refreshTokens(refreshToken);
}

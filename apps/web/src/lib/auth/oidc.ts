import 'server-only';

import type { AuthTokens, PasswordIdentityProvider } from './identity';
import { IdentityError } from './identity';

const ISSUER = process.env.OIDC_ISSUER_URL ?? '';
const CLIENT_ID = process.env.OIDC_CLIENT_ID ?? '';
const CLIENT_SECRET = process.env.OIDC_CLIENT_SECRET ?? '';

interface Discovery {
  token_endpoint: string;
}

interface TokenGrant {
  access_token: string;
  id_token?: string;
  refresh_token?: string;
  expires_in: number;
}

interface TokenFailure {
  error?: string;
  error_description?: string;
}

let discovering: Promise<Discovery> | undefined;

async function discover(): Promise<Discovery> {
  const response = await fetch(
    `${ISSUER.replace(/\/$/, '')}/.well-known/openid-configuration`,
    { cache: 'no-store' },
  );
  if (!response.ok) {
    throw new IdentityError(
      'DiscoveryFailed',
      `O emissor ${ISSUER} respondeu ${response.status} à descoberta OIDC.`,
    );
  }
  const document = (await response.json()) as Partial<Discovery>;
  if (!document.token_endpoint) {
    throw new IdentityError(
      'DiscoveryFailed',
      `O emissor ${ISSUER} não anunciou um token_endpoint.`,
    );
  }
  return { token_endpoint: document.token_endpoint };
}

function tokenEndpoint(): Promise<Discovery> {
  discovering ??= discover().catch((failure: unknown) => {
    discovering = undefined;
    throw failure;
  });
  return discovering;
}

const withTheAccessTokenAsBearer = (grant: TokenGrant): AuthTokens => ({
  idToken: grant.access_token,
  accessToken: grant.access_token,
  refreshToken: grant.refresh_token,
  expiresIn: grant.expires_in,
});

async function requestGrant(
  parameters: Record<string, string>,
): Promise<AuthTokens> {
  if (!CLIENT_ID) {
    throw new IdentityError(
      'MissingConfiguration',
      'OIDC_CLIENT_ID não está configurado neste ambiente.',
    );
  }

  const { token_endpoint } = await tokenEndpoint();
  const body = new URLSearchParams({ client_id: CLIENT_ID, ...parameters });
  if (CLIENT_SECRET) {
    body.set('client_secret', CLIENT_SECRET);
  }

  const response = await fetch(token_endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body,
    cache: 'no-store',
  });

  const payload = (await response.json().catch(() => ({}))) as TokenGrant &
    TokenFailure;

  if (!response.ok || !payload.access_token) {
    throw new IdentityError(
      payload.error ?? 'UnknownError',
      payload.error_description ?? `O emissor respondeu ${response.status}.`,
    );
  }

  return withTheAccessTokenAsBearer(payload);
}

export const oidcProvider: PasswordIdentityProvider = {
  name: 'oidc',

  isConfigured: () => ISSUER !== '',

  signInWithPassword: (email, password) =>
    requestGrant({
      grant_type: 'password',
      username: email,
      password,
      scope: 'openid email profile',
    }),

  refreshTokens: (refreshToken) =>
    requestGrant({ grant_type: 'refresh_token', refresh_token: refreshToken }),
};

export const oidcConfig = {
  issuer: ISSUER,
  clientId: CLIENT_ID,
};

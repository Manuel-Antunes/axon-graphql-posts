import 'server-only';

import type { AuthTokens, PasswordIdentityProvider } from './identity';
import { IdentityError } from './identity';

const REGION = process.env.COGNITO_REGION ?? 'us-east-1';
const CLIENT_ID = process.env.COGNITO_CLIENT_ID ?? '';

export type { AuthTokens };

export class CognitoError extends IdentityError {}

interface InitiateAuthResponse {
  AuthenticationResult?: {
    IdToken: string;
    AccessToken: string;
    RefreshToken?: string;
    ExpiresIn: number;
  };
  ChallengeName?: string;
  Session?: string;
}

async function initiateAuth(
  flow: 'USER_PASSWORD_AUTH' | 'REFRESH_TOKEN_AUTH',
  parameters: Record<string, string>,
): Promise<AuthTokens> {
  if (!CLIENT_ID) {
    throw new CognitoError(
      'MissingConfiguration',
      'COGNITO_CLIENT_ID não está configurado neste ambiente.',
    );
  }

  const response = await fetch(`https://cognito-idp.${REGION}.amazonaws.com/`, {
    method: 'POST',
    headers: {
      'content-type': 'application/x-amz-json-1.1',
      'x-amz-target': 'AWSCognitoIdentityProviderService.InitiateAuth',
    },
    body: JSON.stringify({
      AuthFlow: flow,
      ClientId: CLIENT_ID,
      AuthParameters: parameters,
    }),
    cache: 'no-store',
  });

  const payload = (await response
    .json()
    .catch(() => ({}))) as InitiateAuthResponse & {
    __type?: string;
    message?: string;
  };

  if (!response.ok) {
    const code =
      (payload.__type ?? 'UnknownError').split('#').pop() ?? 'UnknownError';
    throw new CognitoError(
      code,
      payload.message ?? `Cognito respondeu ${response.status}.`,
    );
  }

  if (!payload.AuthenticationResult) {
    throw new CognitoError(
      payload.ChallengeName ?? 'ChallengeRequired',
      `O Cognito pediu um desafio (${payload.ChallengeName ?? '?'}) que este cliente não implementa.`,
    );
  }

  const result = payload.AuthenticationResult;
  return {
    idToken: result.IdToken,
    accessToken: result.AccessToken,
    refreshToken: result.RefreshToken,
    expiresIn: result.ExpiresIn,
  };
}

export function signInWithPassword(
  email: string,
  password: string,
): Promise<AuthTokens> {
  return initiateAuth('USER_PASSWORD_AUTH', {
    USERNAME: email,
    PASSWORD: password,
  });
}

export function refreshTokens(refreshToken: string): Promise<AuthTokens> {
  return initiateAuth('REFRESH_TOKEN_AUTH', { REFRESH_TOKEN: refreshToken });
}

export const cognitoProvider: PasswordIdentityProvider = {
  name: 'cognito',
  isConfigured: () => CLIENT_ID !== '',
  signInWithPassword,
  refreshTokens,
};

export const cognitoConfig = {
  region: REGION,
  clientId: CLIENT_ID,
  issuer: `https://cognito-idp.${REGION}.amazonaws.com`,
};

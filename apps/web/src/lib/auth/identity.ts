import 'server-only';

export interface AuthTokens {
  idToken: string;
  accessToken: string;
  refreshToken?: string;
  expiresIn: number;
}

export class IdentityError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = new.target.name;
  }
}

export interface PasswordIdentityProvider {
  readonly name: string;
  isConfigured(): boolean;
  signInWithPassword(email: string, password: string): Promise<AuthTokens>;
  refreshTokens(refreshToken: string): Promise<AuthTokens>;
}

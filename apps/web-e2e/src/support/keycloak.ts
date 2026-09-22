import type { Account } from './accounts';

export class Keycloak {
  constructor(
    readonly issuerUrl = process.env.OIDC_ISSUER_URL ??
      `http://localhost:${process.env.KEYCLOAK_PORT ?? 8081}/realms/axon-posts`,
    private readonly clientId = process.env.OIDC_CLIENT_ID ?? 'axon-posts-api',
  ) {}

  async isUp(): Promise<boolean> {
    try {
      return (await fetch(`${this.issuerUrl}/.well-known/openid-configuration`))
        .ok;
    } catch {
      return false;
    }
  }

  async tokenFor(account: Account): Promise<string> {
    const response = await fetch(
      `${this.issuerUrl}/protocol/openid-connect/token`,
      {
        method: 'POST',
        body: new URLSearchParams({
          grant_type: 'password',
          client_id: this.clientId,
          username: account.email,
          password: account.password,
        }),
      },
    );
    const payload = (await response.json()) as { access_token?: string };
    if (!payload.access_token) {
      throw new Error(
        `o Keycloak não devolveu token para ${account.email}: ${JSON.stringify(payload)}`,
      );
    }
    return payload.access_token;
  }
}

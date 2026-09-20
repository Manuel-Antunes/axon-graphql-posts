export const GRAPHQL_UPSTREAM =
  process.env.NEXT_PUBLIC_GRAPHQL_URL ?? 'http://localhost:8080/graphql';

export const GRAPHQL_PROXY = '/api/graphql';

export const COGNITO_ISSUER = process.env.NEXT_PUBLIC_COGNITO_ISSUER ?? '';

export function upstreamHost(): string {
  try {
    return new URL(GRAPHQL_UPSTREAM).host;
  } catch {
    return GRAPHQL_UPSTREAM;
  }
}

export const UPSTREAM_IS_LAMBDA = GRAPHQL_UPSTREAM.includes('execute-api');

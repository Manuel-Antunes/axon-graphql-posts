import { readSession } from '@/lib/auth/cookies';
import { GRAPHQL_UPSTREAM } from '@/lib/env';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

class GraphqlServerProxy {
  private readonly requestHeaders = [
    'content-type',
    'accept',
    'traceparent',
    'tracestate',
    'baggage',
  ];
  private readonly responseHeaders = ['content-type', 'cache-control'];

  async proxy(request: Request): Promise<Response> {
    const body = await request.arrayBuffer();

    const upstream = await fetch(GRAPHQL_UPSTREAM, {
      method: 'POST',
      headers: await this.outbound(request),
      body,
      cache: 'no-store',
      signal: request.signal,
    });

    return new Response(upstream.body, {
      status: upstream.status,
      headers: this.inbound(upstream),
    });
  }

  private async outbound(request: Request): Promise<Headers> {
    const headers = new Headers();
    for (const name of this.requestHeaders) {
      const value = request.headers.get(name);
      if (value) headers.set(name, value);
    }
    headers.set('content-type', 'application/json');

    const session = await readSession();
    if (session) headers.set('authorization', `Bearer ${session.idToken}`);

    return headers;
  }

  private inbound(upstream: Response): Headers {
    const headers = new Headers();
    for (const name of this.responseHeaders) {
      const value = upstream.headers.get(name);
      if (value) headers.set(name, value);
    }
    headers.set('cache-control', 'no-cache, no-store, no-transform');
    headers.set('x-accel-buffering', 'no');

    const cookies =
      (
        upstream.headers as Headers & { getSetCookie?: () => string[] }
      ).getSetCookie?.() ?? [];
    for (const cookie of cookies) headers.append('set-cookie', cookie);

    return headers;
  }
}

const proxy = new GraphqlServerProxy();

export async function POST(request: Request) {
  return proxy.proxy(request);
}

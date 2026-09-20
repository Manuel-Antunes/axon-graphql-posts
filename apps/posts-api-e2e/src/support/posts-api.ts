export interface GraphQlResponse<T> {
  data?: T;
  errors?: Array<{ message: string; extensions?: Record<string, unknown> }>;
}

export const sleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));

const AUTHOR = { username: 'manuel@example.com', password: 'segredo123' };

export class PostsApi {
  constructor(
    readonly graphqlUrl = 'http://localhost:8080/graphql',
    readonly issuerUrl = 'http://localhost:8081/realms/axon-posts',
    readonly healthUrl = 'http://localhost:8080/q/health',
  ) {}

  async isHealthy(): Promise<boolean> {
    try {
      return (await fetch(this.healthUrl)).ok;
    } catch {
      return false;
    }
  }

  async asAuthor(): Promise<AuthenticatedApi> {
    const body = new URLSearchParams({
      grant_type: 'password',
      client_id: 'axon-posts-api',
      ...AUTHOR,
    });
    const response = await fetch(
      `${this.issuerUrl}/protocol/openid-connect/token`,
      {
        method: 'POST',
        body,
      },
    );
    const json = (await response.json()) as { access_token?: string };
    if (!json.access_token) {
      throw new Error(`o Keycloak não devolveu token: ${JSON.stringify(json)}`);
    }
    return new AuthenticatedApi(this, json.access_token);
  }

  async query<T>(
    query: string,
    options: { token?: string; variables?: Record<string, unknown> } = {},
  ): Promise<GraphQlResponse<T>> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (options.token) {
      headers.Authorization = `Bearer ${options.token}`;
    }
    const response = await fetch(this.graphqlUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({ query, variables: options.variables }),
    });
    return (await response.json()) as GraphQlResponse<T>;
  }

  async subscribe<T>(query: string): Promise<SseSubscription<T>> {
    const controller = new AbortController();
    const response = await fetch(this.graphqlUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
      },
      body: JSON.stringify({ query }),
      signal: controller.signal,
    });
    if (response.status !== 200) {
      controller.abort();
      throw new Error(
        `a subscription por SSE não abriu: HTTP ${response.status}`,
      );
    }
    return new SseSubscription<T>(response, controller);
  }
}

export class AuthenticatedApi {
  constructor(
    private readonly api: PostsApi,
    readonly token: string,
  ) {}

  query<T>(
    query: string,
    variables?: Record<string, unknown>,
  ): Promise<GraphQlResponse<T>> {
    return this.api.query<T>(query, { token: this.token, variables });
  }

  async mutate<T>(
    query: string,
    variables?: Record<string, unknown>,
  ): Promise<T> {
    const response = await this.query<T>(query, variables);
    if (!response.data) {
      throw new Error(
        `a mutation não devolveu data: ${JSON.stringify(response)}`,
      );
    }
    return response.data;
  }
}

const parseWholeFrame = <T>(payload: string): GraphQlResponse<T> | null => {
  try {
    return JSON.parse(payload) as GraphQlResponse<T>;
  } catch {
    return null;
  }
};

export class SseSubscription<T> {
  readonly events: Array<GraphQlResponse<T>> = [];

  constructor(
    private readonly response: Response,
    private readonly controller: AbortController,
  ) {
    void this.consume();
  }

  get status(): number {
    return this.response.status;
  }

  async waitFor(
    predicate: (event: T) => boolean,
    timeoutMs = 60_000,
  ): Promise<T | null> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const match = this.events
        .map((event) => event.data)
        .find((data): data is T => data !== undefined && predicate(data));
      if (match) return match;
      if (Date.now() >= deadline) return null;
      await sleep(500);
    }
  }

  async awaitMatching(
    predicate: (event: T) => boolean,
    {
      timeoutMs = 60_000,
      describeFailure,
    }: {
      timeoutMs?: number;
      describeFailure?: () => string;
    } = {},
  ): Promise<T> {
    const match = await this.waitFor(predicate, timeoutMs);
    if (match === null) {
      throw new Error(
        `nenhum evento casou em ${timeoutMs}ms; ${this.events.length} evento(s) no fio\n` +
          (describeFailure?.() ?? ''),
      );
    }
    return match;
  }

  close(): void {
    this.controller.abort();
  }

  private async consume(): Promise<void> {
    const reader = this.response.body?.getReader();
    if (!reader) return;
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          if (!line.startsWith('data:')) continue;
          const payload = line.slice(5).trim();
          if (!payload) continue;
          const frame = parseWholeFrame<T>(payload);
          if (frame) this.events.push(frame);
        }
      }
    } catch (error) {
      if (!this.controller.signal.aborted) throw error;
    }
  }
}

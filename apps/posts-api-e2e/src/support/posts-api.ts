/**
 * A BORDA do `posts-api` vista de fora — o que qualquer cliente faria contra a aplicação no ar.
 *
 * Nada aqui conhece a saga. E a subscription é por SSE e não por WebSocket de propósito: a porta de
 * `interfaces/graphql/sse` é a que dispensa cliente de protocolo, e exercitá-la aqui é de graça.
 */
export interface GraphQlResponse<T> {
  data?: T;
  errors?: Array<{ message: string; extensions?: Record<string, unknown> }>;
}

export const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

/** O autor semeado no realm — a senha é a do `docker/keycloak/realm-axon-posts.json`. */
const AUTHOR = { username: "manuel@example.com", password: "segredo123" };

export class PostsApi {
  constructor(
    readonly graphqlUrl = "http://localhost:8080/graphql",
    readonly issuerUrl = "http://localhost:8081/realms/axon-posts",
    readonly healthUrl = "http://localhost:8080/q/health",
  ) {}

  async isHealthy(): Promise<boolean> {
    try {
      return (await fetch(this.healthUrl)).ok;
    } catch {
      return false;
    }
  }

  /** Autentica o autor semeado e devolve um cliente que já assina toda requisição. */
  async asAuthor(): Promise<AuthenticatedApi> {
    const body = new URLSearchParams({
      grant_type: "password",
      client_id: "axon-posts-api",
      ...AUTHOR,
    });
    const response = await fetch(`${this.issuerUrl}/protocol/openid-connect/token`, {
      method: "POST",
      body,
    });
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
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (options.token) {
      headers.Authorization = `Bearer ${options.token}`;
    }
    const response = await fetch(this.graphqlUrl, {
      method: "POST",
      headers,
      body: JSON.stringify({ query, variables: options.variables }),
    });
    return (await response.json()) as GraphQlResponse<T>;
  }

  /**
   * Abre uma subscription pela porta de SSE. O objeto devolvido é quem acumula e espera.
   *
   * RECUSA um status que não seja 200, e isso é o desenho: uma subscription que não abriu não é
   * uma subscription, e quem chama não tem o que fazer com ela. Deixar a afirmação para o teste
   * poria um `expect` dentro do hook de provisionamento — que falha como erro de hook, sem nomear
   * o que se esperava.
   */
  async subscribe<T>(query: string): Promise<SseSubscription<T>> {
    const controller = new AbortController();
    const response = await fetch(this.graphqlUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ query }),
      signal: controller.signal,
    });
    if (response.status !== 200) {
      controller.abort();
      throw new Error(`a subscription por SSE não abriu: HTTP ${response.status}`);
    }
    return new SseSubscription<T>(response, controller);
  }
}

/** O mesmo cliente, com o bearer já preso — quem escreve precisa da role `author`. */
export class AuthenticatedApi {
  constructor(
    private readonly api: PostsApi,
    readonly token: string,
  ) {}

  query<T>(query: string, variables?: Record<string, unknown>): Promise<GraphQlResponse<T>> {
    return this.api.query<T>(query, { token: this.token, variables });
  }

  /**
   * Despacha e DESEMBRULHA — uma resposta sem `data` vira exceção com o corpo inteiro na mensagem.
   *
   * Existe para que a spec não precise afirmar duas vezes a mesma coisa (que veio `data`, e depois
   * o que veio dentro dele). Quem sabe o que é uma resposta malformada é a borda, não o teste — e
   * a mensagem que ela levanta é melhor que a de um `expect` genérico, porque ela tem os `errors`.
   */
  async mutate<T>(query: string, variables?: Record<string, unknown>): Promise<T> {
    const response = await this.query<T>(query, variables);
    if (!response.data) {
      throw new Error(`a mutation não devolveu data: ${JSON.stringify(response)}`);
    }
    return response.data;
  }
}

/**
 * Uma subscription aberta, que vai acumulando o que chega.
 *
 * O laço de leitura roda solto (sem `await`) porque uma subscription NÃO TERMINA: quem a encerra é o
 * `close()`, e o `AbortError` que ele provoca é o fim esperado, não uma falha.
 */
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

  /**
   * Espera por um evento que satisfaça o predicado.
   *
   * 60s, e não 20s: a PRIMEIRA entrega de cada canal paga a conexão do emitter de saída, que é
   * lazy de propósito (resolver o `Emitter` na partida dá `SRMSG00019`). Medido — a primeira
   * mensagem foi nacked e só a reentrega fechou a saga, ~20s depois.
   */
  async waitFor(predicate: (event: T) => boolean, timeoutMs = 60_000): Promise<T | null> {
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

  /**
   * Como `waitFor`, mas FALHA em vez de devolver `null`.
   *
   * O `describeFailure` existe porque, quando a saga não fecha, a resposta não está aqui — está no
   * log do serviço que não decidiu. Quem chama é que sabe qual é esse log, então quem o fornece é
   * ele; o que esta classe garante é que a mensagem não se perca.
   */
  async awaitMatching(
    predicate: (event: T) => boolean,
    { timeoutMs = 60_000, describeFailure }: {
      timeoutMs?: number;
      describeFailure?: () => string;
    } = {},
  ): Promise<T> {
    const match = await this.waitFor(predicate, timeoutMs);
    if (match === null) {
      throw new Error(
        `nenhum evento casou em ${timeoutMs}ms; ${this.events.length} evento(s) no fio\n`
        + (describeFailure?.() ?? ""),
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
    let buffer = "";
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";
        for (const line of lines) {
          if (!line.startsWith("data:")) continue;
          const payload = line.slice(5).trim();
          if (!payload) continue;
          try {
            this.events.push(JSON.parse(payload) as GraphQlResponse<T>);
          } catch {
            /* um frame partido ao meio: o próximo `read` o completa. */
          }
        }
      }
    } catch {
      /* abortado por `close()`. */
    }
  }
}

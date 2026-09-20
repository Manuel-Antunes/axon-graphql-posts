/**
 * O RABBITMQ: as filas, pelo `rabbitmqctl`, e a publicação à mão, pela API de management.
 *
 * Publicar à mão existe por um motivo só, e não é conveniência: o teste de idempotência precisa
 * REENTREGAR uma mensagem já entregue, e de fora a única forma é montar o envelope e publicá-lo. O
 * efeito colateral é o que dá valor ao teste — montar o envelope VALIDA O FORMATO DE FIO. Se o
 * `AxonEventEnvelope` mudar de forma, é aqui que aparece.
 */
import type { Container } from "./docker";

/**
 * O envelope que o `ChannelEventOutbox` põe no fio.
 *
 * É uma classe e não um literal porque ela carrega uma regra: a MARCA DE ORIGEM. É ela que faz um
 * serviço descartar o eco do que ele mesmo publicou, então republicar com a origem do OUTRO serviço
 * é o que mantém o teste medindo o inbox e o agregado — e não a marca.
 */
export class AxonEnvelope {
  private constructor(
    readonly messageType: string,
    readonly identifier: string,
    readonly timestamp: string,
    readonly metadata: Record<string, string>,
    readonly tags: Array<{ key: string; value: string }>,
    readonly payload: string,
  ) {}

  static of({ identity, origin, tag, payload }: {
    identity: { identifier: string; messageType: string };
    origin: string;
    tag: { key: string; value: string };
    payload: unknown;
  }): AxonEnvelope {
    return new AxonEnvelope(
      identity.messageType,
      identity.identifier,
      new Date().toISOString(),
      { "axon-channel-origin": origin },
      [tag],
      // O payload vai em base64: é como o outbox o serializa.
      Buffer.from(JSON.stringify(payload)).toString("base64"),
    );
  }
}

export class Broker {
  /**
   * As filas de TODAS as topologias que este projeto já teve, e não só da atual.
   *
   * Bindings são DURÁVEIS e sobrevivem a redesenho: um `tagging.*.*` de uma versão anterior fica
   * pendurado e faz o desenho atual parecer outro. Apagar a fila — e não purgá-la — força as
   * aplicações a redeclararem exatamente o que elas declaram hoje.
   */
  private static readonly KNOWN_QUEUES = [
    "axonposts.posts-api.post-completed",
    "axonposts.tagging.post-precreated",
    "axonposts.tagging.post-changes",
    "axonposts.posts.inbox",
    "axonposts.tagging.inbox",
    "axonposts.events.in",
    "axonposts.tagging.in",
    "axonposts.consumer.in",
  ];

  constructor(
    private readonly container: Container,
    /** O exchange de saída do `posts-api` — o mesmo que o `mp.messaging.outgoing` declara. */
    private readonly exchange = "axonposts.events",
    private readonly managementUrl = "http://localhost:15672/api",
    private readonly credentials = `Basic ${Buffer.from("guest:guest").toString("base64")}`,
  ) {}

  deleteKnownQueues(): void {
    for (const queue of Broker.KNOWN_QUEUES) {
      this.container.execQuietly("rabbitmqctl", "-q", "delete_queue", queue);
    }
  }

  /** A topologia que as duas aplicações declararam ao subir — só para o log do teste. */
  bindings(): string {
    return this.container
      .execQuietly("rabbitmqctl", "-q", "list_bindings", "source_name", "routing_key", "destination_name")
      .split("\n")
      .filter((line) => line.includes("axonposts"))
      .join("\n");
  }

  /** Publica e devolve o que o broker disse sobre o ROTEAMENTO — `routed: false` é binding errado. */
  async publish(routingKey: string, envelope: AxonEnvelope): Promise<{ routed: boolean }> {
    const response = await fetch(`${this.managementUrl}/exchanges/%2F/${this.exchange}/publish`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: this.credentials },
      body: JSON.stringify({
        properties: {},
        routing_key: routingKey,
        payload: JSON.stringify(envelope),
        payload_encoding: "string",
      }),
    });
    return (await response.json()) as { routed: boolean };
  }
}

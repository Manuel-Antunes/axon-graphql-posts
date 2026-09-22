import type { Container } from './docker';

export class AxonEnvelope {
  private constructor(
    readonly messageType: string,
    readonly identifier: string,
    readonly timestamp: string,
    readonly metadata: Record<string, string>,
    readonly tags: Array<{ key: string; value: string }>,
    readonly payload: string,
  ) {}

  static of({
    identity,
    origin,
    tag,
    payload,
  }: {
    identity: { identifier: string; messageType: string };
    origin: string;
    tag: { key: string; value: string };
    payload: unknown;
  }): AxonEnvelope {
    return new AxonEnvelope(
      identity.messageType,
      identity.identifier,
      new Date().toISOString(),
      { 'axon-channel-origin': origin },
      [tag],
      Buffer.from(JSON.stringify(payload)).toString('base64'),
    );
  }
}

export class Broker {
  private static readonly KNOWN_QUEUES = [
    'axonposts.posts-api.post-completed',
    'axonposts.tagging.post-precreated',
    'axonposts.tagging.post-changes',
    'axonposts.posts.inbox',
    'axonposts.tagging.inbox',
    'axonposts.events.in',
    'axonposts.tagging.in',
    'axonposts.consumer.in',
  ];

  constructor(
    private readonly container: Container,
    private readonly exchange = 'axonposts.events',
    private readonly managementUrl = `http://localhost:${process.env.RABBITMQ_MANAGEMENT_PORT ?? 15672}/api`,
    private readonly credentials = `Basic ${Buffer.from('guest:guest').toString('base64')}`,
  ) {}

  deleteKnownQueues(): void {
    for (const queue of Broker.KNOWN_QUEUES) {
      this.container.execQuietly('rabbitmqctl', '-q', 'delete_queue', queue);
    }
  }

  bindings(): string {
    return this.container
      .execQuietly(
        'rabbitmqctl',
        '-q',
        'list_bindings',
        'source_name',
        'routing_key',
        'destination_name',
      )
      .split('\n')
      .filter((line) => line.includes('axonposts'))
      .join('\n');
  }

  async publish(
    routingKey: string,
    envelope: AxonEnvelope,
  ): Promise<{ routed: boolean }> {
    const response = await fetch(
      `${this.managementUrl}/exchanges/%2F/${this.exchange}/publish`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': this.credentials,
        },
        body: JSON.stringify({
          properties: {},
          routing_key: routingKey,
          payload: JSON.stringify(envelope),
          payload_encoding: 'string',
        }),
      },
    );
    return (await response.json()) as { routed: boolean };
  }
}

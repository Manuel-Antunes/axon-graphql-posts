import type { Client } from 'graphql-sse';
import { ApolloLink } from '@apollo/client/link';
import { print } from 'graphql';
import { createClient } from 'graphql-sse';
import { Observable } from 'rxjs';

const CONNECT_DEADLINE_MS = 45_000;

const connections = new EventTarget();

export function onSseConnected(
  operationName: string,
  listener: () => void,
): () => void {
  const handler = (event: Event) => {
    if ((event as CustomEvent<string>).detail === operationName) listener();
  };
  connections.addEventListener('connected', handler);
  return () => connections.removeEventListener('connected', handler);
}

export class GraphQLSSELink extends ApolloLink {
  private readonly client: Client;

  constructor(url: string) {
    super();
    this.client = createClient({
      url,
      retryAttempts: 5,
    });
  }

  override request(
    operation: ApolloLink.Operation,
  ): Observable<ApolloLink.Result> {
    return new Observable<ApolloLink.Result>((subscriber) => {
      let connected = false;

      const deadline = setTimeout(() => {
        if (connected) return;
        subscriber.error(
          new Error(
            `O proxy não abriu o stream em ${CONNECT_DEADLINE_MS / 1000}s. ` +
              'Veja os logs da função do Next e do posts-api.',
          ),
        );
      }, CONNECT_DEADLINE_MS);

      const dispose = this.client.subscribe(
        {
          query: print(operation.query),
          variables: operation.variables,
          operationName: operation.operationName,
          extensions: operation.extensions,
        },
        {
          next: (value) => subscriber.next(value as ApolloLink.Result),
          error: (error) => {
            clearTimeout(deadline);
            subscriber.error(error);
          },
          complete: () => {
            clearTimeout(deadline);
            subscriber.complete();
          },
        },
        {
          connected: () => {
            connected = true;
            clearTimeout(deadline);
            connections.dispatchEvent(
              new CustomEvent('connected', {
                detail: operation.operationName ?? '',
              }),
            );
          },
        },
      );

      return () => {
        clearTimeout(deadline);
        dispose();
      };
    });
  }
}

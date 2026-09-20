import { ApolloLink } from "@apollo/client/link";
import { print } from "graphql";
import { createClient, type Client } from "graphql-sse";
import { Observable } from "rxjs";

/**
 * Quanto se espera pelo primeiro byte da resposta antes de desistir.
 *
 * O número sai de uma MEDIÇÃO, não de gosto: um cold start do `posts-api` atrás do Lambda Web Adapter
 * levou 15 s e 26 s nas duas vezes em que foi cronometrado, e o proxy não tem o que repassar antes
 * disso. Com 25 s a metade ruim dessa distribuição virava erro na tela de um stream que ia abrir.
 * <p>
 * 45 s cabe dentro do teto real da conexão — o `readTimeout` de 60 s da rota do site no router (ver
 * `infra/aws/web/index.ts`) —, então desistir aqui significa mesmo que ninguém respondeu.
 */
const CONNECT_DEADLINE_MS = 45_000;

/**
 * Subscriptions por <b>Server-Sent Events</b> — contra o proxy da própria aplicação.
 *
 * <h2>O protocolo</h2>
 * Modo <i>distinct connections</i> do `graphql-sse`: uma requisição HTTP por operação, que fica
 * aberta. É o que o `posts-api` implementa em `interfaces/graphql/sse` e o que
 * `app/api/graphql/route.ts` repassa.
 *
 * <h2>O que o proxy resolve, e por que este arquivo ficou curto</h2>
 * Não há `headers` aqui: o `Authorization` é posto pelo proxy, do cookie `httpOnly`. E o proxy não
 * interpreta nada — ele faz um `fetch` e devolve `upstream.body`. O que chega aqui é o SSE que o
 * `posts-api` escreveu, byte a byte.
 *
 * <h2>O prazo existe, e o sinal dele é o `connected`</h2>
 * Um relógio, porque reconectar contra um servidor que não responde só multiplica a espera — foi
 * assim que a interface já ficou dois minutos e meio em "conectando". O sinal observado é o
 * `connected` do protocolo — a resposta COMEÇOU —, e não a chegada de um evento: uma subscription
 * legítima pode ficar horas em silêncio.
 *
 * <h2>E é esse mesmo sinal que a interface mostra</h2>
 * {@link onSseConnected} o republica por nome de operação. Sem ele a página só teria o `loading` do
 * Apollo, que fica `true` até o PRIMEIRO dado — e uma subscription aberta e quieta apareceria como
 * "conectando" para sempre, ao lado de um painel dizendo "Conectado".
 */
const connections = new EventTarget();

/**
 * Avisa quando o stream daquela operação abriu. Devolve a função que cancela a inscrição.
 * <p>
 * O nome da operação é a chave porque em modo <i>distinct connections</i> cada `subscribe` é uma
 * requisição própria, e a página tem duas ao mesmo tempo.
 */
export function onSseConnected(operationName: string, listener: () => void): () => void {
    const handler = (event: Event) => {
        if ((event as CustomEvent<string>).detail === operationName) listener();
    };
    connections.addEventListener("connected", handler);
    return () => connections.removeEventListener("connected", handler);
}

export class GraphQLSSELink extends ApolloLink {
    private readonly client: Client;

    constructor(url: string) {
        super();
        this.client = createClient({
            url,
            /*
             * RECONECTA — e isto mudou de 0 para 5 por uma razão medida.
             *
             * Zero existia quando o upstream era o API Gateway, que nunca abria o stream: ali cada
             * tentativa custava o prazo inteiro e a interface ficava minutos em "conectando".
             *
             * Com o repasse funcionando o caso é o oposto: a conexão tem FIM — a função do `posts-api`
             * tem timeout, e quando ela chega ao dele o stream acaba. Sem reconexão, isso chega ao
             * usuário como `Connection closed while having active streams` e a subscription morre ali.
             * Reconectar é o que transforma um teto de invocação numa emenda que ninguém vê.
             */
            retryAttempts: 5,
        });
    }

    override request(operation: ApolloLink.Operation): Observable<ApolloLink.Result> {
        return new Observable<ApolloLink.Result>((subscriber) => {
            let connected = false;

            const deadline = setTimeout(() => {
                if (connected) return;
                subscriber.error(
                    new Error(
                        `O proxy não abriu o stream em ${CONNECT_DEADLINE_MS / 1000}s. ` +
                            "Veja os logs da função do Next e do posts-api.",
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
                            new CustomEvent("connected", {
                                detail: operation.operationName ?? "",
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

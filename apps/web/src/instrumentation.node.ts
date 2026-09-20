import { registerOTel } from "@vercel/otel";
import { GraphQLInstrumentation } from "@opentelemetry/instrumentation-graphql";

/**
 * O SDK do OpenTelemetry do cliente web — e para onde ele exporta.
 *
 * <h2>Ele fala com `localhost`, não com o backend de observabilidade</h2>
 * A função do Next carrega a MESMA layer do coletor que as funções Java (ver `infra/aws/web/index.ts`),
 * e é o coletor que conhece o destino. O `OTEL_EXPORTER_OTLP_ENDPOINT` aponta para
 * `http://localhost:4318`, dentro do sandbox.
 *
 * Não é simetria de gosto: um Lambda é CONGELADO quando o handler retorna, e um exportador em
 * processo perde o que ainda não saiu — que é justamente o fim da requisição. A layer é uma extensão
 * do Lambda e faz o flush no gancho de fim de invocação.
 *
 * <h2>O que isto acrescenta ao trace</h2>
 * O salto que faltava. Uma operação do navegador atravessa DUAS funções — o proxy de `/api/graphql` e
 * o `posts-api` — e só a segunda aparecia. Com a instrumentação de `fetch` ativa, o `fetch` do proxy
 * propaga `traceparent` sozinho, e o span do `posts-api` passa a pendurar no da rota do Next.
 *
 * <h2>Em desenvolvimento não há coletor</h2>
 * O exportador tenta `localhost:4318`, falha e segue. É barulhento e inofensivo;
 * `OTEL_SDK_DISABLED=true` no `.env.local` silencia.
 */
registerOTel({
    // O mesmo `service.name` que aparece no backend. Sem ele todo span chega como `unknown_service`,
    // que apaga justamente a informação que um trace distribuído tem para dar: em qual lado o tempo
    // foi gasto.
    serviceName: "axonposts-web",
    spanProcessors: ["auto"],
    propagators: ["auto"],
    /*
     * A PROPAGAÇÃO NÃO É LIGADA POR DEFAULT, e descobrir isso custou um probe no backend.
     *
     * No `@vercel/otel`, `propagateContextUrls` começa VAZIO (`?? []` no código do pacote): a
     * instrumentação de `fetch` cria o span da chamada — ele aparece no trace — mas NÃO injeta o
     * `traceparent`, exceto para as URLs de deploy da Vercel. O resultado é enganoso: o salto está
     * lá, desenhado, e mesmo assim o serviço do outro lado abre um trace novo.
     *
     * MEDIDO no Better Stack antes desta linha: 317 spans de `axonposts-web` e 127 de
     * `quarkus-axon-graphql-posts` na mesma hora, e ZERO traces contendo os dois.
     *
     * A lista é explícita em vez de um curinga porque o servidor do Next também fala com o Cognito,
     * nas server actions. Mandar cabeçalho de trace para um provedor de identidade não quebra nada e
     * também não serve para nada — e o que não serve, não sai daqui.
     */
    instrumentationConfig: {
        fetch: {
            propagateContextUrls: [
                // a Function URL do `posts-api` (o que o proxy de `/api/graphql` chama)
                /lambda-url\..*\.on\.aws/,
                // e o mesmo subgraph pelo API Gateway
                /execute-api\..*\.amazonaws\.com/,
            ],
        },
    },
    instrumentations: [
        // `fetch` é o que costura o proxy ao `posts-api`: é ele que injeta o `traceparent`.
        "fetch",
        // E esta instrumenta o `graphql` que o Apollo usa para imprimir e validar documentos no lado
        // do servidor (o `PreloadQuery` dos server components). Não há execução de schema aqui — o
        // span de EXECUÇÃO é do `posts-api`, emitido pelo `TracingService` do SmallRye.
        new GraphQLInstrumentation({
            // Sem os valores das variáveis: elas carregam conteúdo de post e, no login, credencial.
            allowValues: false,
            ignoreTrivialResolveSpans: true,
        }),
    ],
});

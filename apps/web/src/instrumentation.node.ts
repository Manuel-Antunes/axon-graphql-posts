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

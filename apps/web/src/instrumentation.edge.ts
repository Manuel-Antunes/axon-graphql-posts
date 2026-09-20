import { registerOTel } from "@vercel/otel";

/**
 * O SDK do runtime EDGE — e o que ele deliberadamente não tem.
 *
 * <h2>Sem a instrumentação de GraphQL, e isso não é esquecimento</h2>
 * `@opentelemetry/instrumentation-graphql` funciona aplicando patch no módulo `graphql` em tempo de
 * carga, o que depende de APIs de módulo do Node que o runtime de edge não expõe. Importá-la aqui
 * quebra o BUILD do bundle de edge — não o runtime, o build —, e a mensagem não menciona edge.
 * <p>
 * O que sobra é o que importa neste runtime: `fetch`, que é quem propaga o `traceparent` para a
 * próxima função.
 *
 * <h2>Esta aplicação não tem rota de edge hoje</h2>
 * O arquivo existe porque a primeira que existir não deveria descobrir isso por um trace faltando.
 */
registerOTel({
    serviceName: "axonposts-web-edge",
    spanProcessors: ["auto"],
    propagators: ["auto"],
    instrumentations: ["fetch"],
});

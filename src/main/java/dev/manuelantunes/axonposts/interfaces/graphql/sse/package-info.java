/**
 * A <b>terceira porta</b> do mesmo endpoint GraphQL: subscriptions sobre Server-Sent Events.
 *
 * <h2>Por que existe: o SmallRye não serve SSE</h2>
 * O Spring for GraphQL negocia SSE no mesmo {@code POST /graphql} desde a 1.0. O SmallRye GraphQL, não —
 * nem a 2.18.5 nem a extensão do Quarkus 3.39 têm uma linha de {@code text/event-stream}: o único
 * transporte de subscription é WebSocket, em {@code graphql-transport-ws} e {@code graphql-ws}. Este
 * pacote é o que faltava, escrito contra as mesmas peças que a extensão usa.
 *
 * <h2>Onde SSE ganha do WebSocket</h2>
 * Não é performance, é <b>infraestrutura</b>. SSE é uma resposta HTTP comum que nunca termina:
 * <ul>
 *   <li>atravessa proxy, CDN e balanceador que não sabem fazer {@code Upgrade};</li>
 *   <li>o cabeçalho {@code Authorization} vai na própria requisição — no WebSocket o token precisa
 *       viajar no {@code connection_init} ou na query string, que é por onde ele vaza para o log;</li>
 *   <li>o navegador reconecta sozinho, com {@code Last-Event-ID};</li>
 *   <li>um {@code curl -N} lê o stream. Depurar WebSocket exige ferramenta.</li>
 * </ul>
 * O que se perde é o canal de volta: SSE é mão única. Para subscription isso não custa nada — quem fala
 * é o servidor —, e cancelar é fechar a conexão.
 *
 * <h2>As três classes</h2>
 * <ul>
 *   <li>{@code GraphQlOverSse} — monta a rota, e só;</li>
 *   <li>{@code GraphQlSseHandler} — negocia o {@code Accept}, executa a operação e liga o
 *       {@code Publisher} do resultado ao stream;</li>
 *   <li>{@code SseStream} — o formato do fio: {@code event}/{@code data}, keep-alive, fechamento.</li>
 * </ul>
 *
 * <h2>O que esta porta NÃO reimplementa</h2>
 * Nada da camada de cima. O {@code ExecutionService} é o mesmo, então schema, resolvers,
 * {@code @RolesAllowed}, o {@code ErrorTranslationInterceptor} e até o teto de profundidade valem igual —
 * e o {@code onOverflow().buffer(...)} das subscriptions, que conserta a demanda incremental do Axon,
 * conserta as duas portas de uma vez. Uma subscription nova não sabe por onde está sendo servida.
 */
package dev.manuelantunes.axonposts.interfaces.graphql.sse;

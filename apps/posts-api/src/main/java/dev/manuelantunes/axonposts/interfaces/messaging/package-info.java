/**
 * As portas de entrada por MENSAGEM.
 *
 * <h2>Por que isto é apresentação, e não infraestrutura</h2>
 * Porque é o mesmo papel que {@code interfaces/graphql}: o lugar onde algo de fora entra no sistema. Um
 * {@code @Incoming} é um endereço — fila e routing key — exatamente como um {@code @GraphQLApi} é um
 * caminho HTTP. Os dois recebem uma representação externa, escolhem o que o sistema deve fazer com ela,
 * e nada mais.
 * <p>
 * Ficavam em {@code infrastructure/messaging} porque mensageria "parece" infraestrutura. Parece pelo
 * transporte, e o transporte não é o critério: o que separa as camadas aqui é DIREÇÃO. Adaptador de
 * saída (o outbox, o repositório, o provedor de identidade) é infraestrutura; porta de entrada é
 * apresentação, venha ela de HTTP, de WebSocket ou de uma fila.
 * <p>
 * A consequência prática de reconhecer isso: as regras de apresentação passam a valer aqui. Um listener
 * não alcança repositório de domínio, não decide regra e não escreve no banco — ele entrega a mensagem
 * ao mecanismo de ingestão e sai do caminho, do mesmo jeito que um resolver entrega ao command gateway.
 *
 * <h2>Um listener por propósito</h2>
 * Não há um canal "pega tudo". Cada fatia que esta máquina ingere tem canal, fila e binding próprios, e
 * um arquivo aqui. Isso dá isolamento de falha (uma mensagem-veneno não derruba o fluxo vizinho), vazão
 * por fatia, e uma topologia que se lê no broker: {@code list_bindings} passa a descrever o sistema.
 */
package dev.manuelantunes.axonposts.interfaces.messaging;

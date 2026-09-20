/**
 * O endereçamento de saída para os dois brokers da AWS — uma classe por conector, exatamente como
 * {@code RabbitMqAddressing} é uma classe para {@code smallrye-rabbitmq}.
 *
 * <p>Quem escolhe entre eles não é código: é a linha
 * {@code mp.messaging.outgoing.<canal>.connector} daquele canal, que {@code OutboxRouting} lê e casa
 * com {@code ChannelAddressing.connector()}. Os três convivem no mesmo processo sem se conhecerem.
 */
package dev.manuelantunes.axonposts.infrastructure.messaging.aws;

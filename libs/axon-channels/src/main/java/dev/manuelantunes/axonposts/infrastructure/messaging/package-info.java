/**
 * O Axon falando pelos <i>channels</i> do Quarkus — e não por um cliente de broker.
 *
 * <h2>A ideia</h2>
 * Nada aqui conhece RabbitMQ, Kafka ou Pulsar. O que existe é:
 * <ul>
 *   <li>{@link dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventPublisher}, que decora
 *       o {@code EventSink} do Axon: <b>todo</b> evento publicado também vai para um channel. Não há
 *       {@code @EventHandler} por tipo de evento — é genérico sobre {@code EventMessage};</li>
 *   <li>{@link dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventSource}, um
 *       {@code SubscribableEventSource} alimentado por {@code @Incoming}: o event processor do Axon
 *       passa a ser acionado pela entrega do channel em vez da publicação local.</li>
 * </ul>
 * Trocar de broker é trocar o {@code connector} do channel em {@code application.properties}. O código
 * não muda.
 *
 * <h2>Por que o envelope vai no PAYLOAD e não em headers</h2>
 * Porque headers são a parte que <b>não</b> é portável: RabbitMQ tem
 * {@code OutgoingRabbitMQMetadata}, Kafka tem {@code OutgoingKafkaRecordMetadata}, e cada um com API
 * própria. Um envelope no corpo atravessa qualquer conector sem uma linha específica.
 * <p>
 * A única exceção é a routing key, que por definição é endereçamento do broker — e ela está isolada em
 * {@link dev.manuelantunes.axonposts.infrastructure.messaging.ChannelAddressing}, uma implementação
 * por conector. É o "mudo num lugar" pedido.
 *
 * <h2>Serialização é a do Axon, não a nossa</h2>
 * O corpo do envelope é produzido pelo {@code EventConverter} do framework — o mesmo que o event store
 * usa. Não há um segundo formato de serialização para manter, e o {@code @Event(namespace, name,
 * version)} sobrevive ao fio porque {@code MessageType} tem {@code toString()}/{@code fromString()}.
 *
 * <h2>A consequência que não dá para evitar</h2>
 * Se o processador da <b>projeção</b> for apontado para o channel, ela deixa de rodar na transação da
 * escrita: o evento sai, volta pelo broker, e só então a projeção e o command seguinte acontecem. O
 * {@code createPost} passa a responder versão 1 sem tag, em vez de versão 2 com a tag. Por isso a
 * fiação é uma propriedade ({@code axonposts.messaging.event-source}) e não uma decisão embutida: o
 * outbox pode existir sem que a projeção mude de natureza.
 */
package dev.manuelantunes.axonposts.infrastructure.messaging;

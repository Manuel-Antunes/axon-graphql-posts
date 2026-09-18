/**
 * O Axon falando pelos <i>channels</i> do Quarkus — e não por um cliente de broker.
 *
 * <h2>As duas metades</h2>
 * <ul>
 *   <li><b>SAÍDA</b>: {@link dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventDispatchInterceptor}
 *       liga-se ao ponto de extensão do próprio Axon e encaminha <b>todo</b> evento despachado, depois do
 *       commit, ao {@link dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventForwarder}.
 *       Não há {@code @EventHandler} por tipo de evento — é genérico sobre {@code EventMessage};</li>
 *   <li><b>ENTRADA</b>: {@link dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventIngestion}
 *       apenda a mensagem recebida <b>no event store local</b>, e é o store — não a fila — que alimenta
 *       os event processors. O broker volta a ser transporte.</li>
 * </ul>
 *
 * <h2>A saída tem a mesma forma que a entrada, e isso é recente</h2>
 * A entrada sempre foi "um canal por propósito", com o seletor ({@code routing-keys}) declarado no
 * próprio canal. A saída era um canal só — {@code axon-events} —, por onde todo evento passava; o
 * destino era escolhido depois, pela binding de quem consumia.
 * <p>
 * Hoje cada destino é um produtor de {@code Emitter} anotado
 * {@link dev.manuelantunes.axonposts.infrastructure.messaging.AxonOutbox} na APLICAÇÃO, que diz em
 * código <b>quais namespaces</b> saem por ele, e em configuração <b>para onde</b> — conector, exchange,
 * tópico. O que isso compra está em
 * {@link dev.manuelantunes.axonposts.infrastructure.messaging.OutboxRouting}; o resumo é que destinos em
 * protocolos diferentes — um RabbitMQ e um Kafka no mesmo serviço — deixam de ser inexprimíveis, porque
 * conector é atributo do canal e agora existe mais de um canal.
 * <p>
 * A divisão que ficou, e que vale como regra: <b>o código diz o quê, a configuração diz para onde.</b>
 *
 * <h2>Por que o envelope vai no PAYLOAD e não em headers</h2>
 * Porque headers são a parte que <b>não</b> é portável: RabbitMQ tem {@code OutgoingRabbitMQMetadata},
 * Kafka tem {@code OutgoingKafkaRecordMetadata}, e cada um com API própria. Um envelope no corpo
 * atravessa qualquer conector sem uma linha específica.
 * <p>
 * A exceção é o endereçamento, que por definição é do broker — e ele está isolado em
 * {@link dev.manuelantunes.axonposts.infrastructure.messaging.ChannelAddressing}, uma implementação por
 * conector, escolhida por canal.
 *
 * <h2>Serialização é a do Axon, não a nossa</h2>
 * O corpo do envelope é produzido pelo {@code EventConverter} do framework — o mesmo que o event store
 * usa. Não há um segundo formato de serialização para manter, e o
 * {@code @Event(namespace, name, version)} sobrevive ao fio porque {@code MessageType} tem
 * {@code toString()}/{@code fromString()}.
 */
package dev.manuelantunes.axonposts.infrastructure.messaging;

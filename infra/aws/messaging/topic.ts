/// <reference path="../../../.sst/platform/config.d.ts" />

/**
 * O "exchange": um topic SNS FIFO.
 *
 * Isto não é um desenho novo — é o `axonposts.events` do RabbitMQ, peça por peça:
 *
 * <pre>
 * exchange topic `axonposts.events`  ->  este topic SNS FIFO
 * a binding de cada fila             ->  a filter policy de cada subscription  (routing.ts)
 * a fila                             ->  a fila SQS FIFO                       (queues.ts)
 * a routing key do evento            ->  o atributo `axon-message-name`        (AwsEventAttributes)
 * </pre>
 *
 * Só a última linha exigiu código, e por um motivo concreto: SNS e SQS não têm routing key. O corpo é
 * opaco e quem seleciona é a filter policy, que só enxerga ATRIBUTOS — então o que era campo do
 * protocolo virou carga explícita.
 *
 * A consequência boa: acrescentar um consumidor é uma `subscribeQueue` em `routing.ts`. Os produtores
 * não mudam, e continuam sem nomear ninguém — que é o que "saga coreografada" quer dizer.
 *
 * <h2>FIFO não é afinamento</h2>
 * `apps/tagging` ESCREVE no stream do `Post` — é ele que apenda o `PostCreated` que completa o post.
 * Num event store em aggregate mode a posição de um append vem de ter lido o stream antes, então um
 * `PostUpdated` que ultrapasse o `PostPreCreated` do mesmo post faz o append seguinte cair em
 * `duplicate key value violates unique constraint "uk_aggregateevententry_aggregate"`.
 * <p>
 * O `MessageGroupId` é o id do agregado, e ele já existia: é o `EventAddress.orderingKey()`, que era o
 * terceiro segmento da routing key e lá não ordenava nada. Numa fila standard esta saga não funciona
 * pior — ela quebra, de forma intermitente e proporcional à carga.
 */
export const postEvents = new sst.aws.SnsTopic("PostEvents", { fifo: true });

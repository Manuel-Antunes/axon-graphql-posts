/// <reference path="../../../.sst/platform/config.d.ts" />

import { changes, completed, precreated } from './queues';
import { postEvents } from './topic';

/**
 * As "bindings": quem recebe qual evento.
 *
 * `axon-message-name` é o nome do evento sem namespace — o mesmo que a routing key
 * `posts.PostPreCreated.<postId>` selecionava, sem os dois segmentos que não selecionavam nada. Quem
 * o põe na mensagem é o `AwsEventAttributes` de `libs/axon-aws`, porque SNS e SQS não têm routing key
 * e a filter policy só enxerga ATRIBUTOS.
 *
 * <h2>`rawMessageDelivery` é obrigatório, e falha em silêncio sem ele</h2>
 * Sem raw delivery o corpo que chega na fila é o envelope do SNS
 * (`{"Type":"Notification","Message":"..."}`) e o `AxonEventEnvelope` fica aninhado dentro de uma
 * string. O sintoma é `UnrecognizedPropertyException: Type` na ingestão, e uma mensagem rejeitada por
 * lote. Os atributos — de que a própria filter policy depende — também só sobrevivem com raw.
 *
 * Ele vai por `transform` porque o `SnsTopicQueueSubscriberArgs` do SST 4.17.1 expõe `filter` e não
 * expõe `rawMessageDelivery`; o `transform.subscription` chega ao `sns.TopicSubscription` do Pulumi,
 * que o tem. Conferido na versão instalada, não suposto.
 */
const raw = { subscription: { rawMessageDelivery: true } };

postEvents.subscribeQueue('Precreated', precreated, {
  filter: { 'axon-message-name': ['PostPreCreated'] },
  transform: raw,
});

/**
 * Exatamente as três routing keys que `post-changes-in.routing-keys` lista hoje.
 *
 * EVENTO NOVO NO CICLO DE VIDA DO POST = mais um nome AQUI. Esquecer não quebra na hora: quebra no
 * próximo append do tagueamento àquele agregado, longe da causa.
 */
postEvents.subscribeQueue('Changes', changes, {
  filter: {
    'axon-message-name': ['PostUpdated', 'PostDeleted', 'PostRestored'],
  },
  transform: raw,
});

postEvents.subscribeQueue('Completed', completed, {
  filter: { 'axon-message-name': ['PostCreated'] },
  transform: raw,
});

/*
 * `PostPreCreated` não está no filtro de `Changes` porque vem pela fila que ACIONA trabalho, e
 * `PostCreated` não está em fila nenhuma do tagueamento porque é ELE quem o publica.
 *
 * E a marca de origem (`axon-channel-origin`, na metadata do envelope) o descartaria de qualquer
 * forma — é ela, e não o filtro, que impede o laço de reenvio. Duas defesas de propósito: filtro é
 * configuração, e um dia alguém acrescenta um nome aqui para depurar.
 */

/// <reference path="../../../.sst/platform/config.d.ts" />

import { Migrator } from '../support';
import { postsEnvironment, taggingEnvironment } from './environment';
import { platform } from './platform';
import { postsInbox, taggingDecide } from './workers';

/**
 * As migrations, e elas rodam SOZINHAS no deploy.
 *
 * O {@link Migrator} cria a função e a `aws.lambda.Invocation` que a chama durante o `sst deploy`.
 * O ganho é que migration que falha vira DEPLOY que falha — em vez de uma função esquecida e um
 * `missing table [accounts]` na primeira requisição, que foi exatamente como isto começou.
 *
 * São o MESMO artefato das funções de fila, com `QUARKUS_LAMBDA_HANDLER=flyway-migrate`. Um zip a
 * menos para construir, e nenhuma chance de as migrations empacotadas divergirem das que a aplicação
 * valida com `schema-management.strategy=validate`.
 */
/**
 * NESTA FUNÇÃO NÃO PODE HAVER PROCESSOR STREAMING, e a razão é o ovo e a galinha.
 *
 * Um `PooledStreamingEventProcessor` lê o EVENT STORE ao subir: o Coordinator inicializa os
 * segmentos e consulta `aggregateevententry` — a tabela que a V2 cria e que esta função existe para
 * criar. Contra um banco vazio ele tenta 30 vezes e derruba a partida inteira:
 *
 * ```
 * Coordinator: Processor [post-subscriptions]. Initializing (16) segments
 * ERROR: relation "aggregateevententry" does not exist        (SQLState 42P01)
 * ProcessRetriesExhaustedException: Tried invoking the action for 30 times
 * → AxonExtension.init falha → Runtime.ExitError, exit status 1
 * ```
 *
 * É o MESMO ovo e galinha que o `QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=none` do
 * {@link Migrator} já resolve, uma camada abaixo: aquele desliga o `validate` do Hibernate, que
 * apenas CONFERE o schema; este processor o CONSULTA, e nenhum `strategy` o alcança.
 *
 * O conserto é mover o pacote para o processor SUBSCRIBING, que se liga ao event bus e não toca no
 * banco na partida. Vale porque as duas listas PARTICIONAM: conferido no bytecode da extensão,
 * `DefaultAxonFrameworkConfigurer.eventhandlersForPoolProcessors(todos, namespacesDoSubscribing)`
 * filtra do pool o que o subscribing já reivindicou. O bloco
 * `quarkus.axon.pooledprocessor.post-subscriptions.*` do `application.properties` fica órfão aqui, e
 * isso é inofensivo: o configurador do pool é dirigido pelos handlers DESCOBERTOS, e uma entrada que
 * nenhum namespace casa nunca é consultada.
 *
 * Só vale para a função de migração. As outras cinco continuam com o pooled streaming, que é o que
 * faz a subscription atravessar containers — ver *O pacote de um event handler escolhe a ENTREGA
 * dele* no `CLAUDE.md`.
 *
 * A LISTA ESPELHA `application.properties`, e é a única duplicação desta correção: são os dois
 * namespaces com `@EventHandler` do `posts-api` (`subscribingprocessor.namespaces` mais
 * `pooledprocessor.post-subscriptions.namespaces`). Pacote NOVO com handler entra aqui também —
 * quem cobra do lado Java é `AxonWiringTest.everyPackageWithAnEventHandlerIsAssignedToAProcessor`,
 * e ele não enxerga este arquivo. Ficar de fora não dá erro: o pacote volta para um pooled e a
 * função de migração volta a morrer contra o banco vazio.
 */
const MIGRATE_WITHOUT_STREAMING_PROCESSORS = [
  'dev.manuelantunes.axonposts.application.post.projection',
  'dev.manuelantunes.axonposts.application.post.event',
].join(',');

export const postsMigrate = new Migrator('PostsMigrate', {
  platform,
  // O MESMO zip da função de fila: o que muda é `QUARKUS_LAMBDA_HANDLER`.
  code: postsInbox.code,
  environment: {
    ...postsEnvironment,
    // Variável de ambiente e não arquivo: `quarkus.axon.subscribingprocessor` é RUN_TIME (conferido
    // no bytecode: `AxonConfiguration` é `@ConfigRoot(phase = RUN_TIME)`), e o ordinal 300 do
    // ambiente ganha de qualquer `.properties` — que é o que permite este artefato servir a DUAS
    // funções com fiações de processor diferentes.
    QUARKUS_AXON_SUBSCRIBINGPROCESSOR_NAMESPACES:
      MIGRATE_WITHOUT_STREAMING_PROCESSORS,
  },
});

/**
 * `AXONPOSTS_LAMBDA_SQS_CHANNEL` aparece aqui mesmo sem haver fila nenhuma porque o Quarkus valida
 * TODOS os `@ConfigProperty` injetados na partida, incluindo o do `SqsChannelBinding` — que nesta
 * função nunca será usado. Sem o valor, ela não sobe.
 *
 * (No `posts-api` isso não é preciso: lá o canal é constante e já está no
 * `application-lambda.properties`, porque aquela aplicação tem uma fila de entrada só.)
 */
export const taggingMigrate = new Migrator('TaggingMigrate', {
  platform,
  // Idem: o artefato do `tagging`, com o handler de migração.
  code: taggingDecide.code,
  environment: {
    ...taggingEnvironment,
    AXONPOSTS_LAMBDA_SQS_CHANNEL: 'post-precreated-in',
  },
});

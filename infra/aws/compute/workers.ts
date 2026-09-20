/// <reference path="../../../.sst/platform/config.d.ts" />

import { changes, completed, precreated } from '../messaging';
import { QueueWorker } from '../support';
import { postsEnvironment, taggingEnvironment } from './environment';
import { codeBucket, platform, projects, sources } from './platform';

/**
 * As funções movidas por fila. Uma por FILA, e não uma com várias.
 *
 * `timeout: 120` e não 30 porque aqui não há API Gateway limitando, e o cold start de uma JVM mais os
 * 25s que o `SqsChannelIngress` espera pelo ack precisam caber juntos. O `visibilityTimeout` das filas
 * (180s) é maior que este número, que é a regra que impede uma segunda entrega enquanto a primeira
 * ainda processa.
 */
const timeout = 120;

/**
 * A VOLTA da saga: o post voltando completo de quem decidiu a tag. O MESMO código da API, outro
 * empacotamento — porque uma função do Lambda tem UM handler, e esta aplicação tem duas portas de
 * entrada de naturezas diferentes. Com um processo longo elas conviviam.
 */
export const postsInbox = new QueueWorker('PostsApiInbox', {
  platform,
  code: {
    artifact: 'posts-api-sqs',
    buildCommand: `npx -y nx run "${projects.postsApi}:lambda-sqs:native-container"`,
    output: 'infra/dist/posts-api-sqs.zip',
    bucket: codeBucket,
    sources: sources.postsApi,
  },
  environment: postsEnvironment,
  timeout,
  queue: completed,
  channel: 'post-completed-in',
});

/**
 * Onde o tagueamento AGE: cada mensagem vira uma decisão e um evento publicado.
 *
 * Esta e a de baixo saem do MESMO zip. O que as distingue é a fila que as aciona e o canal — não há
 * código diferente entre elas.
 */
export const taggingDecide = new QueueWorker('TaggingDecide', {
  platform,
  code: {
    artifact: 'tagging',
    buildCommand: `npx -y nx run "${projects.tagging}:lambda:native-container"`,
    output: 'infra/dist/tagging.zip',
    bucket: codeBucket,
    sources: sources.tagging,
  },
  environment: taggingEnvironment,
  timeout,
  queue: precreated,
  channel: 'post-precreated-in',
});

/**
 * Onde ele só REPLICA. Nenhum handler reage: estes eventos existem para o stream do Post ficar
 * completo naquele store, porque é dele que a posição do próximo append depende.
 */
export const taggingReplicate = new QueueWorker('TaggingReplicate', {
  platform,
  // O MESMO zip do `taggingDecide`: outra fila, outro `@Incoming`, o mesmo artefato.
  code: taggingDecide.code,
  environment: taggingEnvironment,
  timeout,
  queue: changes,
  channel: 'post-changes-in',
});

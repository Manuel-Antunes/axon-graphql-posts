/// <reference path="../../../.sst/platform/config.d.ts" />

import { Migrator } from "../support";
import { platform } from "./platform";
import { postsInbox, taggingDecide } from "./workers";
import { postsEnvironment, taggingEnvironment } from "./environment";

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
export const postsMigrate = new Migrator("PostsMigrate", {
    platform,
    // O MESMO zip da função de fila: o que muda é `QUARKUS_LAMBDA_HANDLER`.
    code: postsInbox.code,
    environment: postsEnvironment,
});

/**
 * `AXONPOSTS_LAMBDA_SQS_CHANNEL` aparece aqui mesmo sem haver fila nenhuma porque o Quarkus valida
 * TODOS os `@ConfigProperty` injetados na partida, incluindo o do `SqsChannelBinding` — que nesta
 * função nunca será usado. Sem o valor, ela não sobe.
 *
 * (No `posts-api` isso não é preciso: lá o canal é constante e já está no
 * `application-lambda.properties`, porque aquela aplicação tem uma fila de entrada só.)
 */
export const taggingMigrate = new Migrator("TaggingMigrate", {
    platform,
    // Idem: o artefato do `tagging`, com o handler de migração.
    code: taggingDecide.code,
    environment: { ...taggingEnvironment, AXONPOSTS_LAMBDA_SQS_CHANNEL: "post-precreated-in" },
});

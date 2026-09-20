/// <reference path="../../../.sst/platform/config.d.ts" />

/**
 * As SEIS funções, de TRÊS zips — e por que são seis.
 *
 * Uma função do Lambda tem UM handler, e ela é CHAMADA, não escuta:
 *
 * <ul>
 *   <li>o `posts-api` tem duas portas de entrada de naturezas diferentes — HTTP (o GraphQL) e fila (o
 *       `posts.PostCreated` que volta de quem decidiu a tag) —, e com um processo longo elas conviviam
 *       no mesmo JVM. Aqui quem chama é o API Gateway OU o event source mapping, nunca os dois;</li>
 *   <li>o `apps/tagging` tem duas filas, e elas existem justamente para ter falha, DLQ e concorrência
 *       separadas;</li>
 *   <li>e cada banco tem a sua migração, que roda no deploy e some em seguida.</li>
 * </ul>
 *
 * A ordem dos imports é a de dependência: `platform` cria o papel e publica os artefatos, e tudo o
 * mais os consome.
 */
export { platform, codeBucket, sources } from "./platform";
export { api, gateway, streaming } from "./api";
export { postsInbox, taggingDecide, taggingReplicate } from "./workers";
export { postsMigrate, taggingMigrate } from "./migrations";

/// <reference path="../../../.sst/platform/config.d.ts" />

import { vpc } from "../network";

/**
 * Os DOIS event stores.
 *
 * <h2>Por que dois bancos, e não dois schemas</h2>
 * Event store compartilhado é acoplamento pela porta dos fundos: dois serviços no mesmo store podem
 * ler o stream um do outro sem passar por contrato nenhum, e o dia em que um deles "der uma
 * olhadinha" no agregado do outro, a fronteira terá desaparecido sem nenhuma revisão de código ter
 * visto. É a mesma decisão que o `docker-compose.yml` já toma com o `02-tagging-database.sql`.
 *
 * <h2>O que pesa numa função com banco relacional</h2>
 * Não é a latência de rede: é UMA CONEXÃO POR AMBIENTE DE EXECUÇÃO, multiplicada pela concorrência.
 * Um `t4g.micro` aceita ~80 conexões. Para uso real o caminho é RDS Proxy, e o `sst.aws.Postgres` já
 * tem a opção `proxy`.
 *
 * <h2>E as migrations não rodam na partida</h2>
 * `migrate-at-start` é `false` pela razão medida no `application.properties` — o recorder do Axon toca
 * o EntityManager antes de o Flyway ter a vez. Em Lambda isso deixa de ser contorno e vira a única
 * escolha correta: migration na partida de uma função que escala para N ambientes seria N tentativas
 * concorrentes, cada cold start esperando o lock do Flyway de outro dentro do timeout de uma invocação
 * que alguém está esperando.
 * <p>
 * Quem cria o schema é o {@code Migrator} de `compute/migrations.ts`, invocado pelo PRÓPRIO deploy.
 */
export const postsDb = new sst.aws.Postgres("PostsDb", {
    vpc,
    instance: "t4g.micro",
    storage: "20 GB",
});

/**
 * O do `apps/tagging`. Ele não tem read model nenhum — `quarkus.hibernate-orm.packages` restringe a
 * persistence unit a `org.axonframework` —, então aqui só existem as tabelas do Axon.
 */
export const taggingDb = new sst.aws.Postgres("TaggingDb", {
    vpc,
    instance: "t4g.micro",
    storage: "20 GB",
});

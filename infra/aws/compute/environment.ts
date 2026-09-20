/// <reference path="../../../.sst/platform/config.d.ts" />

import { postEvents } from "../messaging";
import { postsDb, taggingDb } from "../data";
import { issuer, client } from "../identity";

/**
 * A configuração que as funções recebem por ambiente — e só ela. Tudo que NÃO muda entre ambientes
 * está no `application-lambda.properties`, dentro do artefato.
 *
 * <h2>`QUARKUS_PROFILE` tem duas entradas, e as duas importam</h2>
 * `lambda` traz o `application-lambda.properties` (o conector SNS, o canal in-memory, o OIDC do
 * Cognito). `prod` é o que mantém valendo as linhas `%prod.` do `application.properties` — datasource
 * e endpoint do OTLP.
 * <p>
 * Com `lambda` sozinho as `%prod.` sumiriam EM SILÊNCIO e a função subiria apontando para o datasource
 * de desenvolvimento. E isto precisa valer nos DOIS momentos: em build time vem do perfil Maven, em
 * runtime vem daqui — o Quarkus relê a configuração na partida da função, e o default é `prod` só.
 */
const shared = {
    QUARKUS_PROFILE: "lambda,prod",
    AXONPOSTS_EVENTS_TOPIC_ARN: postEvents.arn,

    /*
     * NÃO HÁ COLETOR OTLP NESTA STACK, e a linha `%prod.` do application.properties aponta o
     * exportador para `localhost:4317`. Deixá-la valer faria cada invocação pagar uma conexão que
     * falha, e o flush do BatchSpanProcessor segurar o encerramento da invocação.
     *
     * `sdk.disabled` desliga a instrumentação inteira e não só o exportador — é a mesma linha que o
     * perfil de teste usa, pela mesma razão. Isto é uma DÍVIDA declarada, não uma escolha: a regra
     * deste projeto é que aplicação nova nasce instrumentada, e aqui ela está desligada só porque não
     * há para onde exportar. O caminho nativo é a layer do ADOT (ou um coletor no VPC).
     */
    /*
     * O SDK LIGADO — ele estava desligado porque não havia para onde exportar.
     *
     * Agora há: toda função carrega a layer do coletor do OpenTelemetry (ver `support/functions.ts`),
     * que escuta em `localhost:4317` e reexporta para o Better Stack. O default de
     * `%prod.quarkus.otel.exporter.otlp.endpoint` já é esse endereço, então não há uma linha a mais
     * aqui: o que mudou foi deixar de desligar.
     *
     * O que isso devolve é o que o `CLAUDE.md` descreve como perdido na migração para Lambda — o
     * trace atravessando os dois serviços. O ponto cego de SPANS dentro do Axon continua, e quem
     * responde por ele continua sendo o `AxonMetrics`, que agora também sai daqui.
     */
};

/**
 * As três funções do `posts-api`.
 *
 * O OIDC vai para as TRÊS, e não só para a de API — o que parece desperdício e não é. As três
 * compartilham o mesmo `application-lambda.properties`, e a linha
 * `quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL}` NÃO TEM DEFAULT, ao contrário da `%prod.` que ela
 * substitui. Sem a variável, a expressão não expande e a aplicação nem sobe:
 *
 * <pre>
 * ConfigurationException: 'quarkus.oidc.auth-server-url' property must be configured
 * Quarkus manual initialization failed
 * </pre>
 *
 * Medido: a função de migração e a de inbox morriam na partida, sem servir HTTP nenhum — a extensão
 * OIDC inicializa junto com a APLICAÇÃO, não com a primeira requisição.
 */
export const postsEnvironment = {
    ...shared,
    QUARKUS_DATASOURCE_JDBC_URL: $interpolate`jdbc:postgresql://${postsDb.host}:${postsDb.port}/${postsDb.database}`,
    QUARKUS_DATASOURCE_USERNAME: postsDb.username,
    QUARKUS_DATASOURCE_PASSWORD: postsDb.password,
    OIDC_ISSUER_URL: issuer,
    OIDC_CLIENT_ID: client.id,

    /*
     * AS MESMAS DUAS COISAS, AGORA COMO CONFIGURAÇÃO DIRETA DO QUARKUS — e a duplicação é o conserto
     * de um defeito que só o BINÁRIO NATIVO revelou.
     *
     * O `application-lambda.properties` diz `quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL}` sem
     * prefixo de perfil, e o `application.properties` ao lado diz
     * `%prod.quarkus.oidc.auth-server-url=${KEYCLOAK_ISSUER_URI:http://localhost:8081/...}`. Na JVM a
     * primeira vence; no binário nativo, a segunda — e a função sobe apontando para um Keycloak em
     * `localhost` que não existe. O sintoma é `OIDC Server is not available: Connection refused` na
     * partida e HTTP 500 em toda operação autenticada, enquanto as públicas respondem normalmente.
     *
     * Medido dos dois lados: `axonposts.graphql.sse.keep-alive=2s`, do MESMO arquivo, vale em native
     * (keep-alives a cada 2 s na stack). A diferença entre as duas linhas é que só a do OIDC disputa
     * com uma `%prod.` — então não é o arquivo que não carrega, é a precedência entre uma propriedade
     * COM perfil e uma SEM.
     *
     * Variável de ambiente é ordinal 300: ganha de qualquer arquivo, em qualquer empacotamento. As
     * outras duas linhas de OIDC do arquivo (`token.audience`, `roles.role-claim-path`) ficam lá
     * porque não disputam com ninguém.
     */
    QUARKUS_OIDC_AUTH_SERVER_URL: issuer,
    QUARKUS_OIDC_CLIENT_ID: client.id,
};

/** As três do `apps/tagging`. Ele não tem OIDC: não há uma linha de HTTP nele. */
export const taggingEnvironment = {
    ...shared,
    QUARKUS_DATASOURCE_JDBC_URL: $interpolate`jdbc:postgresql://${taggingDb.host}:${taggingDb.port}/${taggingDb.database}`,
    QUARKUS_DATASOURCE_USERNAME: taggingDb.username,
    QUARKUS_DATASOURCE_PASSWORD: taggingDb.password,
};

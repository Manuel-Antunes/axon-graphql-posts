/// <reference path="../../../.sst/platform/config.d.ts" />

/**
 * As "filas vinculadas". Uma por FATIA DO FLUXO, e não uma por serviço.
 *
 * É a divisão que já existe no `application.properties` dos dois módulos, e a razão está escrita lá:
 *
 * <ul>
 *   <li><b>isolamento de falha</b> — mensagem-veneno de um tipo não é rejeitada junto com o fluxo dos
 *       outros;</li>
 *   <li><b>vazão por fatia</b> — dá para escalar ou pausar um fluxo sem tocar no resto;</li>
 *   <li><b>topologia legível</b> — a lista de filas DESCREVE o sistema.</li>
 * </ul>
 */

/**
 * A fábrica. Cada fila nasce com a DLQ dela, e a DLQ de uma fila FIFO também é FIFO.
 *
 * O `dlq` do SST exige o par `{ queue, retry }` — passar só `retry` derruba a criação com
 * `Redrive policy does not contain mandatory attribute: deadLetterTargetArn`, que foi o que aconteceu
 * na primeira tentativa de deploy.
 *
 * `retry: 5` e não 1 porque o que mais falha aqui não é a mensagem, é o ambiente: cold start de JVM
 * estourando, conexão com o RDS que ainda não subiu. Cinco tentativas atravessam isso; o que sobra
 * depois de cinco é veneno de verdade, e aí a DLQ é o lugar certo.
 *
 * `visibilityTimeout` é maior que o timeout das funções (120s): é o que impede a mesma mensagem de ser
 * entregue de novo enquanto a primeira invocação ainda a processa. Se isso acontecesse, quem seguraria
 * a duplicata seria o `axon_message_inbox` — mas pagando uma invocação inteira de JVM para descobrir
 * que não havia o que fazer.
 */
function queue(name: string) {
    const dlq = new sst.aws.Queue(`${name}Dlq`, { fifo: true });
    return new sst.aws.Queue(name, {
        fifo: true,
        visibilityTimeout: "180 seconds",
        dlq: { queue: dlq.arn, retry: 5 },
    });
}

/** Onde o tagueamento AGE: um post nasceu sem tag. Cada mensagem vira uma decisão e um evento. */
export const precreated = queue("TaggingPrecreated");

/**
 * Onde o tagueamento só REPLICA. Nenhum handler reage a estes eventos: eles existem para o stream do
 * Post ficar COMPLETO naquele store, porque é dele que a posição do próximo append depende. Ver o
 * Javadoc de `PostChangesListener`.
 */
export const changes = queue("TaggingChanges");

/** A VOLTA da saga: o post voltando completo de quem decidiu a tag. */
export const completed = queue("PostsApiCompleted");

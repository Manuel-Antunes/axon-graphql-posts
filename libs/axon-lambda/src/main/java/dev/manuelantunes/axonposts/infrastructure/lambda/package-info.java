/**
 * A entrada quando o transporte é o <b>event source mapping</b> do Lambda em vez de um conector.
 *
 * <p>Três peças, e só a primeira é conceitual:
 * <ul>
 *   <li>{@link dev.manuelantunes.axonposts.infrastructure.lambda.SqsChannelIngress} — o registro do SQS
 *       vira mensagem no canal, e esta thread espera o ack. É o que substitui o conector;</li>
 *   <li>{@link dev.manuelantunes.axonposts.infrastructure.lambda.SqsChannelBinding} — a que canal esta
 *       função entrega. Uma linha de configuração, porque o roteamento de verdade é das filter policies
 *       do topic: quando a fila já contém só o que aquele canal ingere, não sobra decisão para o
 *       código;</li>
 *   <li>{@link dev.manuelantunes.axonposts.infrastructure.lambda.AxonSqsLambda} — as seis linhas que o
 *       runtime da AWS chama.</li>
 * </ul>
 *
 * <h2>O que este pacote NÃO faz, e é a regressão conhecida desta migração</h2>
 * Não propaga contexto de trace. Com o RabbitMQ o elo entre os dois processos saía de graça, porque o
 * conector instrumenta os dois lados; aqui a entrega não passa por conector nenhum — o Lambda entrega o
 * {@code SQSEvent} direto —, então o {@code traceparent} teria de ser extraído à mão dos atributos da
 * mensagem e restaurado no contexto antes de empurrar para o canal.
 * <p>
 * Do lado da SAÍDA o quadro depende do conector escolhido, e a diferença é medível no JAR: o
 * {@code smallrye-reactive-messaging-aws-sqs} traz {@code SqsOpenTelemetryInstrumenter} e um
 * {@code TextMapSetter} — ele injeta; o {@code smallrye-reactive-messaging-aws-sns} 4.37.0 <b>não tem
 * pacote de tracing nenhum</b>. Ou seja: publicando por SQS direto o trace atravessa a saída;
 * publicando por SNS, não.
 * <p>
 * Isto importa mais do que parece, e o motivo está escrito no CLAUDE.md deste projeto: um trace pela
 * metade é pior que nenhum, porque <b>a lacuna parece latência</b>. Enquanto não houver propagação,
 * quem responde "onde o tempo foi gasto" são as métricas do {@code AxonMetrics} — que continuam
 * funcionando, porque não dependem de trace.
 */
package dev.manuelantunes.axonposts.infrastructure.lambda;

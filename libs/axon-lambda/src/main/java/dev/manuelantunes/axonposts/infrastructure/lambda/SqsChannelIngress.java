package dev.manuelantunes.axonposts.infrastructure.lambda;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;

/**
 * O transporte de entrada quando o transporte é o <b>Lambda</b>: um {@code SQSEvent} entra, e cada
 * registro sai como mensagem no canal que já existe.
 *
 * <h2>Por que os {@code @Incoming} que já existem continuam sendo a porta</h2>
 * Porque eles <b>são</b> a porta. A regra de camadas deste projeto diz que porta de entrada é
 * apresentação e mora na aplicação, e que o critério não é o transporte — é a direção. O
 * {@code PostPreCreatedListener} declara <i>qual fatia do fluxo esta máquina ingere</i>; isso não mudou
 * por a mensagem ter deixado de vir de um exchange e passado a vir de um event source mapping. O que
 * mudou foi só quem a entrega.
 * <p>
 * Então o que esta classe faz é ocupar o lugar do conector: ela empurra a mensagem para o canal, pelo
 * {@code smallrye-in-memory}, e o {@code @Incoming} roda <b>sem uma linha alterada</b> — com o
 * {@code @Blocking(ordered = false)} dele, com o {@code ChannelEventIngestion} atrás dele, com o
 * {@code axon_message_inbox} e o append na mesma unidade de trabalho. Toda a razão medida que está
 * escrita no Javadoc daqueles listeners continua valendo, porque continua sendo o mesmo código.
 * <p>
 * A alternativa era esta classe chamar {@code ChannelEventIngestion.ingest(body)} direto. Seria mais
 * curto e deixaria os listeners como código morto no perfil {@code lambda} — duas portas de entrada
 * para o mesmo fluxo, uma usada no RabbitMQ e outra na AWS, divergindo na primeira vez que alguém
 * mexesse numa só.
 *
 * <h2>Por que é síncrono, e por que devolve {@code SQSBatchResponse}</h2>
 * Um Lambda termina quando o método retorna: o ambiente de execução é <b>congelado</b> em seguida, e o
 * que estiver pendente numa thread de worker simplesmente para de acontecer — para recomeçar, talvez,
 * no meio de outra invocação. Por isso aqui se espera pelo ack de cada mensagem em vez de devolver um
 * {@code Uni}: um {@code Uni} devolvido ao runtime do Lambda não é assinado por ninguém.
 * <p>
 * E o retorno é {@code SQSBatchResponse} porque sem ele o lote é tudo-ou-nada: uma mensagem-veneno no
 * meio de dez faz as dez voltarem, e as nove boas são reprocessadas — o que o
 * {@code axon_message_inbox} descarta, mas pagando uma invocação e uma transação cada. Com
 * {@code ReportBatchItemFailures} ligado no event source mapping, só o que falhou volta.
 *
 * <h2>A regra de parada, e por que ela é mais dura do que parece necessário</h2>
 * Na primeira falha, esse registro <b>e todos os seguintes</b> são reportados como falha, e o lote
 * para. É o que a AWS documenta para filas FIFO, e aqui a razão é concreta: {@code apps/tagging}
 * escreve no stream do {@code Post}, e um append em <i>aggregate mode</i> depende de ter lido o stream
 * antes. Processar o registro N+1 depois de o N ter falhado entregaria os eventos do mesmo agregado
 * fora de ordem — e o preço disso não é uma mensagem perdida, é
 * {@code duplicate key value violates unique constraint "uk_aggregateevententry_aggregate"} numa
 * invocação futura, longe da causa.
 * <p>
 * Parar por lote inteiro em vez de por grupo é conservador: registros de outro {@code MessageGroupId}
 * poderiam seguir. Custa reprocessamento (que o inbox descarta) e compra não ter de raciocinar sobre
 * ordem parcial dentro de um lote.
 */
@ApplicationScoped
public class SqsChannelIngress {

    private static final Logger log = LoggerFactory.getLogger(SqsChannelIngress.class);

    private final InMemoryConnector channels;
    private final SqsChannelBinding binding;
    private final TelemetryFlush telemetry;
    private final Duration timeout;

    /*
     * O @Any não é enfeite: o InMemoryConnector é anotado @Connector("smallrye-in-memory"), e
     * @Connector É um qualifier. Um bean que declara qualifier próprio NÃO ganha @Default, então o
     * ponto de injeção sem qualificação nenhuma não resolve:
     *
     *   UnsatisfiedResolutionException: Unsatisfied dependency for type InMemoryConnector and
     *   qualifiers [@Default]
     *
     * @Any casa com qualquer bean do tipo, seja qual for o qualifier — e há um só.
     */
    SqsChannelIngress(@Any InMemoryConnector channels, SqsChannelBinding binding,
            TelemetryFlush telemetry,
            @ConfigProperty(name = "axonposts.lambda.sqs.timeout", defaultValue = "30s") Duration timeout) {
        this.channels = channels;
        this.binding = binding;
        this.telemetry = telemetry;
        this.timeout = timeout;
    }

    /**
     * Entrega o lote, em ordem, e devolve o que falhou.
     *
     * <p>Nunca lança: uma exceção escapando daqui faz o Lambda marcar a invocação inteira como falha e
     * o lote todo voltar — que é exatamente o comportamento que o {@code SQSBatchResponse} existe para
     * evitar. Falha vira item na lista, com a causa no log.
     */
    public SQSBatchResponse ingest(SQSEvent event) {
        List<SQSEvent.SQSMessage> records = event.getRecords() == null ? List.of() : event.getRecords();
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        String channel = binding.channel();
        log.debug("lambda ← lote de {} registro(s) para o canal '{}'", records.size(), channel);

        for (int position = 0; position < records.size(); position++) {
            SQSEvent.SQSMessage record = records.get(position);
            try {
                deliver(record, channel);
            } catch (Exception failure) {
                log.error("lambda ← registro {} ({}) FALHOU; ele e os {} seguintes voltam à fila",
                        record.getMessageId(), record.getEventSourceArn(),
                        records.size() - position - 1, failure);
                for (int remaining = position; remaining < records.size(); remaining++) {
                    failures.add(new SQSBatchResponse.BatchItemFailure(
                            records.get(remaining).getMessageId()));
                }
                break;
            }
        }

        if (failures.isEmpty()) {
            log.debug("lambda ← lote inteiro processado ({} registro(s))", records.size());
        }

        // A ÚLTIMA COISA ANTES DE RETORNAR, e nesta ordem por duas razões: depois deste
        // return o ambiente é congelado com o lote dentro, e aqui a unidade de trabalho do
        // Axon já commitou — não há transação ativa para uma segunda thread invadir. Ver
        // TelemetryFlush, inclusive o que acontece quando se tenta exportar antes disso.
        telemetry.beforeFreeze();
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    /**
     * Um registro: vira {@code Message} no canal e esta thread espera o ack.
     *
     * <p>O ack só sai quando o {@code @Incoming} retorna — que naqueles listeners é depois do commit da
     * unidade de trabalho do Axon, ou seja depois de a linha do inbox e o append no event store estarem
     * gravados, e depois de o que aquele append disparou (a decisão de tag, a publicação de saída) ter
     * acontecido. É por isso que esperar aqui não é pessimismo: é o que faz "a mensagem foi processada"
     * significar a mesma coisa que significava com o broker.
     */
    /**
     * O contexto do trace que veio COM a mensagem — e por que ele é lido à mão.
     *
     * <h3>Na entrada não há conector para instrumentar</h3>
     * O Lambda entrega o {@code SQSEvent} direto ao handler: não existe um `@Incoming` do SmallRye
     * neste ponto, e portanto não existe o instrumentador que, no RabbitMQ, extraía o `traceparent`
     * sozinho. Sem esta leitura, cada função começa um trace NOVO — e o que se vê no backend são
     * fragmentos soltos, com a travessia entre eles aparecendo como latência de origem desconhecida.
     *
     * <h3>Por que os atributos, e não o corpo</h3>
     * Porque o corpo é o evento serializado, e contrato de domínio não carrega transporte. Quem
     * escreve o `traceparent` do outro lado é o {@code AwsEventAttributes.inject}, nos ATRIBUTOS — e
     * eles atravessam SNS→SQS porque a subscription usa `RawMessageDelivery`.
     */
    private static final TextMapGetter<SQSEvent.SQSMessage> FROM_ATTRIBUTES =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(SQSEvent.SQSMessage record) {
                    return record.getMessageAttributes() == null
                            ? List.of()
                            : record.getMessageAttributes().keySet();
                }

                @Override
                public String get(SQSEvent.SQSMessage record, String key) {
                    if (record == null || record.getMessageAttributes() == null) {
                        return null;
                    }
                    SQSEvent.MessageAttribute attribute = record.getMessageAttributes().get(key);
                    return attribute == null ? null : attribute.getStringValue();
                }
            };

    /**
     * Abre um span de CONSUMO por registro — e sem ele este serviço não existe no trace.
     *
     * <h3>Extrair o contexto não basta</h3>
     * A primeira versão só chamava `extract` e `makeCurrent`. O contexto passava a existir, e mesmo
     * assim o `apps/tagging` <b>não aparecia em lugar nenhum</b>: medido no backend, 317 spans do
     * `axonposts-web`, 127 do `posts-api` e ZERO dele. A razão é simples e fácil de não enxergar —
     * um contexto não é um span. Este serviço não tem porta HTTP, não tem conector instrumentado e o
     * handler do Lambda não é instrumentado por ninguém: não havia quem CRIASSE um span para
     * pendurar no contexto extraído.
     *
     * <h3>O que este span cobre, e o que não cobre</h3>
     * Ele cobre a entrega síncrona: abre antes de mandar ao canal e fecha quando o ack (ou o nack)
     * volta, então a duração dele é o tempo real de processar aquele registro. Um erro o marca.
     * <p>
     * O que ele NÃO cobre são os spans INTERNOS do trabalho — o append no event store, o command, o
     * publish de volta. O canal in-memory roda o `@Incoming` noutra thread
     * ({@code runOnVertxContext(true)}), e o `Context` do OpenTelemetry é thread-local: o que vale
     * aqui não vale lá. A consequência visível é que a mensagem que ESTE serviço publica em seguida
     * sai sem `traceparent`, e o salto seguinte abre um trace novo.
     * <p>
     * Fechar isso é carregar o contexto na METADATA da mensagem e restaurá-lo em
     * {@code ChannelEventIngestion} — infraestrutura, sem tocar nos listeners, que por regra só
     * entregam e saem. Não está feito.
     */
    private void deliver(SQSEvent.SQSMessage record, String channel) throws Exception {
        Context parent = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), record, FROM_ATTRIBUTES);

        // O nome segue a convenção de mensageria do OpenTelemetry: `<destino> <operação>`. É o que
        // faz o backend agrupar isto com os spans de fila dos outros serviços.
        Span span = GlobalOpenTelemetry.getTracer("axonposts-lambda")
                .spanBuilder(channel + " receive")
                .setParent(parent)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute(AttributeKey.stringKey("messaging.system"), "aws_sqs")
                .setAttribute(AttributeKey.stringKey("messaging.operation"), "receive")
                .setAttribute(AttributeKey.stringKey("messaging.destination.name"), channel)
                .setAttribute(AttributeKey.stringKey("messaging.message.id"),
                        String.valueOf(record.getMessageId()))
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            deliverInContext(record, channel);
        } catch (Exception failure) {
            span.setStatus(StatusCode.ERROR, String.valueOf(failure.getMessage()));
            span.recordException(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private void deliverInContext(SQSEvent.SQSMessage record, String channel) throws Exception {
        byte[] body = record.getBody() == null
                ? new byte[0]
                : record.getBody().getBytes(StandardCharsets.UTF_8);

        CompletableFuture<Void> processed = new CompletableFuture<>();
        InMemorySource<Object> source = channels.source(channel);
        /*
         * runOnVertxContext(true) para a entrega acontecer no MESMO tipo de contexto em que o
         * conector do RabbitMQ a fazia. Não é preferência: é o que mantém válido tudo que já foi
         * medido nestes listeners. O `@Blocking(ordered = false)` deles existe porque a entrega
         * chegava num contexto do Vert.x e a ingestão faz JPA — com `false` a entrega aconteceria
         * NESTA thread, o `@Blocking` deixaria de estar offloadando o que ele foi escrito para
         * offloadar, e a propagação de contexto de requisição passaria a depender de uma thread que
         * o Vert.x não conhece.
         *
         * É o mesmo objeto a cada mensagem (o `source(...)` devolve o que o SmallRye registrou ao
         * ligar o canal), então esta chamada é uma atribuição idempotente.
         */
        source.runOnVertxContext(true);
        source.send(Message.of(body,
                () -> {
                    processed.complete(null);
                    return CompletableFuture.completedFuture(null);
                },
                failure -> {
                    processed.completeExceptionally(failure);
                    return CompletableFuture.completedFuture(null);
                }));

        log.debug("lambda ← {} ({} bytes) → canal '{}'", record.getMessageId(), body.length, channel);
        try {
            processed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            throw new IllegalStateException(
                    "o canal '" + channel + "' não deu ack nem nack em " + timeout + " para o registro "
                            + record.getMessageId() + ". O @Incoming está pendurado — em geral é o "
                            + "timeout da transação do Narayana, ou uma espera por conexão que não vem. "
                            + "Aumente axonposts.lambda.sqs.timeout se o timeout da FUNÇÃO for maior.",
                    expired);
        } catch (ExecutionException rejected) {
            Throwable cause = rejected.getCause();
            throw cause instanceof Exception checked ? checked : new IllegalStateException(cause);
        }
    }
}

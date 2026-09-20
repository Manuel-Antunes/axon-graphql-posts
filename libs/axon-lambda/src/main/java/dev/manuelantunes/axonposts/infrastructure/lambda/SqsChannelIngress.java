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

@ApplicationScoped
public class SqsChannelIngress {
    private static final Logger log = LoggerFactory.getLogger(SqsChannelIngress.class);

    private final InMemoryConnector channels;
    private final SqsChannelBinding binding;
    private final TelemetryFlush telemetry;
    private final Duration timeout;

    SqsChannelIngress(@Any InMemoryConnector channels, SqsChannelBinding binding,
            TelemetryFlush telemetry,
            @ConfigProperty(name = "axonposts.lambda.sqs.timeout", defaultValue = "30s") Duration timeout) {
        this.channels = channels;
        this.binding = binding;
        this.telemetry = telemetry;
        this.timeout = timeout;
    }

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

        telemetry.beforeFreeze();
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

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

    private void deliver(SQSEvent.SQSMessage record, String channel) throws Exception {
        Context parent = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), record, FROM_ATTRIBUTES);

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

package dev.manuelantunes.axonposts.interfaces.graphql.sse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.logging.Logger;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import graphql.ExecutionResult;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.smallrye.graphql.runtime.SmallRyeGraphQLAbstractHandler;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.graphql.execution.ExecutionResponse;
import io.smallrye.graphql.execution.ExecutionResponseWriter;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

public class GraphQlSseHandler extends SmallRyeGraphQLAbstractHandler {
    private static final Logger log = Logger.getLogger(GraphQlSseHandler.class);

    static final String EVENT_STREAM = "text/event-stream";

    private static final String INTERNAL_ERROR = errors("Internal server error");

    private final Duration keepAlive;

    GraphQlSseHandler(CurrentIdentityAssociation identity, CurrentVertxRequest request, Duration keepAlive) {
        super(identity, request, false);
        this.keepAlive = keepAlive;
    }

    @Override
    protected void doHandle(RoutingContext ctx) {
        if (!wantsEventStream(ctx)) {
            ctx.next();
            return;
        }

        Map<String, Object> metaData = getMetaData(ctx);

        if (ctx.request().method() == HttpMethod.GET) {
            handleGet(ctx, metaData);
        } else {
            handlePost(ctx, metaData);
        }
    }

    private void handleGet(RoutingContext ctx, Map<String, Object> metaData) {
        String query = ctx.request().getParam("query");
        if (query == null || query.isBlank()) {
            badRequest(ctx, "o parâmetro 'query' é obrigatório");
            return;
        }
        JsonObjectBuilder request = Json.createObjectBuilder().add("query", query);
        addIfPresent(request, "operationName", ctx.request().getParam("operationName"));
        addJsonIfPresent(request, "variables", ctx.request().getParam("variables"));
        addJsonIfPresent(request, "extensions", ctx.request().getParam("extensions"));

        stream(ctx, request.build(), metaData);
    }

    private void handlePost(RoutingContext ctx, Map<String, Object> metaData) {
        HttpServerRequest incoming = ctx.request();
        incoming.body(body -> {
            if (body.failed()) {
                badRequest(ctx, "corpo da requisição ilegível");
                return;
            }
            JsonObject request;
            try {
                request = inputToJsonObject(body.result().toString(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                badRequest(ctx, "o corpo da requisição não é um objeto JSON");
                return;
            }
            stream(ctx, request, metaData);
        });
        incoming.resume();
    }

    private void stream(RoutingContext ctx, JsonObject request, Map<String, Object> metaData) {
        SseStream stream = SseStream.open(ctx, keepAlive);
        ResultSubscriber subscriber = new ResultSubscriber(stream);

        ctx.addEndHandler(ignored -> {
            stream.abandon();
            subscriber.cancel();
        });

        getExecutionService().executeAsync(request, metaData, new ExecutionResponseWriter() {
            @Override
            public void write(ExecutionResponse response) {
                ExecutionResult result = response.getExecutionResult();
                Object data = result.isDataPresent() ? result.getData() : null;
                if (data instanceof Publisher<?> updates) {
                    @SuppressWarnings("unchecked")
                    Publisher<ExecutionResult> results = (Publisher<ExecutionResult>) updates;
                    results.subscribe(subscriber);
                } else {
                    stream.next(response.getExecutionResultAsString());
                    stream.complete();
                }
            }

            @Override
            public void fail(Throwable thrown) {
                log.warn("falha ao executar a operação GraphQL sobre SSE", thrown);
                stream.next(INTERNAL_ERROR);
                stream.complete();
            }
        });
    }

    private static final class ResultSubscriber implements Subscriber<ExecutionResult> {
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();
        private final SseStream stream;

        private ResultSubscriber(SseStream stream) {
            this.stream = stream;
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription.set(s);
            s.request(1);
        }

        @Override
        public void onNext(ExecutionResult result) {
            if (!stream.isOpen()) {
                cancel();
                return;
            }
            stream.next(new ExecutionResponse(result).getExecutionResultAsString());
            Subscription s = subscription.get();
            if (s != null) {
                s.request(1);
            }
        }

        @Override
        public void onError(Throwable thrown) {
            log.warn("o stream da subscription falhou", thrown);
            stream.complete();
        }

        @Override
        public void onComplete() {
            stream.complete();
        }

        void cancel() {
            Subscription s = subscription.getAndSet(null);
            if (s != null) {
                s.cancel();
            }
        }
    }

    private static boolean wantsEventStream(RoutingContext ctx) {
        String accept = ctx.request().getHeader(HttpHeaders.ACCEPT);
        if (accept == null) {
            return false;
        }
        for (String candidate : accept.split(",")) {
            if (EVENT_STREAM.equalsIgnoreCase(candidate.split(";")[0].trim())) {
                return true;
            }
        }
        return false;
    }

    private static void badRequest(RoutingContext ctx, String reason) {
        ctx.response()
                .setStatusCode(400)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                .end(errors(reason));
    }

    private static String errors(String message) {
        return Json.createObjectBuilder()
                .add("errors", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("message", message)))
                .build()
                .toString();
    }

    private static void addIfPresent(JsonObjectBuilder request, String name, String value) {
        if (value != null && !value.isBlank()) {
            request.add(name, value);
        }
    }

    private void addJsonIfPresent(JsonObjectBuilder request, String name, String value) {
        if (value != null && !value.isBlank()) {
            request.add(name, inputToJsonObject(value));
        }
    }
}

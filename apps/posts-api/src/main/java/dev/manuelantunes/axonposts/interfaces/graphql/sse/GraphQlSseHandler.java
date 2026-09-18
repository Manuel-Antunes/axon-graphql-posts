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

/**
 * GraphQL over <b>Server-Sent Events</b>, no modo <i>distinct connections</i> do protocolo
 * {@code graphql-sse}: uma requisição HTTP por operação, a resposta fica aberta, cada resultado vira um
 * evento {@code next} e o fim vira um {@code complete}.
 *
 * <h2>Por que herdar do handler do Quarkus</h2>
 * Porque um endpoint GraphQL não é só "executar a query". Antes disso é preciso ativar o contexto de
 * requisição do ArC, publicar a {@code SecurityIdentity} para que {@code @RolesAllowed} e
 * {@code AuthenticatedUser} enxerguem quem chamou, pendurar o {@code RoutingContext} no
 * {@code CurrentVertxRequest} e — o mais fácil de esquecer — <b>carregar o estado do contexto no
 * {@code metaData}</b>, que é como os data fetchers assíncronos o reativam na thread de worker.
 * <p>
 * O {@code SmallRyeGraphQLAbstractHandler} faz exatamente isso, e é dele que descendem o handler HTTP e o
 * de WebSocket do próprio Quarkus. Herdar é o que garante que as três portas se comportem <b>igual</b>:
 * a mesma tradução de erro, a mesma autorização, o mesmo contexto. Reescrever essas trinta linhas daria
 * um SSE que funciona no caminho feliz e diverge no resto.
 * <p>
 * <b>O preço</b>: a classe é do pacote {@code runtime} de uma extensão, não uma API pública do Quarkus.
 * Uma atualização do Quarkus pode mexer nela. É uma dependência consciente, e a alternativa era pior.
 *
 * <h2>Negociação explícita, e o curinga não conta</h2>
 * A rota cobre o mesmo {@code /graphql} das outras duas portas e só assume a requisição quando o
 * {@code Accept} traz {@code text/event-stream} <b>literalmente</b>; qualquer outra coisa é
 * {@code ctx.next()}, e o handler de execução do Quarkus responde como sempre. Um {@code Accept}
 * curinga <b>não</b> casa de propósito: cliente que não pediu stream não deve receber uma conexão que nunca
 * fecha. É o mesmo desenho do handler de WebSocket do Quarkus, que também olha um cabeçalho e devolve a
 * requisição quando não é para ele.
 *
 * <h2>Erro antes e erro depois do primeiro byte</h2>
 * Abrir o stream escreve o status. Depois disso nada mais pode virar um código HTTP, e é por isso que o
 * pedido é validado <b>antes</b>: pedido malformado sai como {@code 400} com corpo JSON, e erro de
 * execução — inclusive erro de validação do documento GraphQL — sai como evento {@code next} com
 * {@code errors} dentro, que é o que o protocolo manda.
 */
public class GraphQlSseHandler extends SmallRyeGraphQLAbstractHandler {

    private static final Logger log = Logger.getLogger(GraphQlSseHandler.class);

    static final String EVENT_STREAM = "text/event-stream";

    /** A mesma mensagem que o handler de WebSocket do SmallRye usa quando a execução falha por fora. */
    private static final String INTERNAL_ERROR = errors("Internal server error");

    private final Duration keepAlive;

    /**
     * {@code runBlocking = false} para acompanhar o padrão da extensão
     * ({@code quarkus.smallrye-graphql.non-blocking.enabled}): os resolvers deste projeto devolvem
     * {@code Uni}/{@code Multi} e fazem o próprio offload com {@code runSubscriptionOn}.
     */
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

        // enquanto o contexto de requisição está ativo: é dele que sai o "state" que os data fetchers
        // assíncronos reativam mais tarde, possivelmente noutra thread
        Map<String, Object> metaData = getMetaData(ctx);

        if (ctx.request().method() == HttpMethod.GET) {
            handleGet(ctx, metaData);
        } else {
            handlePost(ctx, metaData);
        }
    }

    /** GET com os parâmetros na URL — a forma que o {@code EventSource} do navegador consegue falar. */
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

    /**
     * POST com o corpo JSON — a forma canônica, e a que o cliente {@code graphql-sse} usa.
     * <p>
     * O corpo é lido aqui, e não por um {@code BodyHandler} na rota: o {@code BodyHandler} do Quarkus
     * está montado na rota <i>do handler de execução</i>, e ler o corpo antes dele significaria lê-lo
     * duas vezes nas requisições que esta rota devolve com {@code ctx.next()}.
     */
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
        // o Vert.x Web PAUSA a requisição ao começar a rotear, e quem a solta é o BodyHandler. Como esta
        // rota não tem um (ver acima), o resume é aqui — sem ele o `body(...)` acima nunca é chamado e a
        // requisição fica pendurada até o cliente desistir. É o mesmo passo que o BodyHandler daria.
        incoming.resume();
    }

    private void stream(RoutingContext ctx, JsonObject request, Map<String, Object> metaData) {
        SseStream stream = SseStream.open(ctx, keepAlive);
        ResultSubscriber subscriber = new ResultSubscriber(stream);

        // o cliente fechar a aba tem de CANCELAR a subscription query lá no Axon; sem isto o emitter
        // continuaria com um assinante que ninguém lê
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
                    // query, mutation, erro de validação ou subscription que estourou ao ser montada:
                    // tudo isso é um resultado só, e o protocolo pede next + complete do mesmo jeito
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

    /**
     * Um assinante por conexão, pedindo <b>um item de cada vez</b> — igual ao do handler de WebSocket do
     * SmallRye, e pelo mesmo motivo: é a demanda que aplica contrapressão até o socket.
     * <p>
     * É também o que torna o {@code onOverflow().buffer(...)} das subscriptions obrigatório também aqui.
     * O {@code Publisher} do {@code subscriptionQuery} do Axon não honra demanda incremental, então sem o
     * buffer <b>esta porta falharia exatamente como a de WebSocket falhava</b>: um evento e silêncio. A
     * correção mora na camada de aplicação, e as duas portas herdam.
     */
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
            // a falha de um resolver chega como um ExecutionResult com `errors` no onNext; aqui é o
            // stream em si que quebrou, e não há mais para onde mandar detalhe
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

    /** {@code Accept} tem de trazer o tipo <b>literal</b>; curinga não conta. Ver o Javadoc da classe. */
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

package dev.manuelantunes.axonposts.interfaces.graphql.sse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

/**
 * O <b>transporte</b>: uma resposta HTTP que fica aberta e vai recebendo eventos, no formato
 * {@code text/event-stream}. Não sabe nada de GraphQL — recebe strings já prontas e as emoldura.
 *
 * <h2>O formato, e as três linhas que costumam faltar</h2>
 * Um evento SSE é {@code event: <nome>}, uma ou mais {@code data: <linha>} e uma linha em branco. Três
 * detalhes decidem se ele chega:
 * <ol>
 *   <li><b>{@code setChunked(true)}</b>. Sem {@code Content-Length} e sem chunked o Vert.x segura a
 *       resposta até o {@code end()} — que numa subscription nunca vem. O stream "funciona", a conexão
 *       fica aberta, e o cliente não recebe nada;</li>
 *   <li><b>uma {@code data:} por linha</b>. O {@code \n} é o separador de campos do próprio protocolo:
 *       um JSON com quebra de linha viraria dois campos e o cliente leria metade. O JSON que o SmallRye
 *       gera é compacto, mas depender disso é depender de um detalhe de outra biblioteca;</li>
 *   <li><b>{@code X-Accel-Buffering: no}</b>. Proxy reverso acumula resposta chunked por padrão. O
 *       cabeçalho é o pedido explícito para não acumular esta.</li>
 * </ol>
 *
 * <h2>O keep-alive é um comentário</h2>
 * Uma linha que começa com {@code :} é comentário de SSE: o cliente descarta, o proxy vê tráfego. É o que
 * impede um balanceador de fechar a conexão de uma newsletter que passou dez minutos sem post novo. O
 * {@code graphql-sse} não o exige — quem exige é a rede. Intervalo em
 * {@code axonposts.graphql.sse.keep-alive}; zero desliga.
 */
final class SseStream {

    private static final Logger log = Logger.getLogger(SseStream.class);

    /** Comentário vazio: o cliente ignora, o proxy conta como tráfego. */
    private static final String KEEP_ALIVE = ":\n\n";

    /** O {@code data:} vazio existe porque o {@code EventSource} do navegador descarta evento sem ele. */
    private static final String COMPLETE = "event: complete\ndata: \n\n";

    private static final long NO_TIMER = -1;

    private final HttpServerResponse response;
    private final Vertx vertx;
    private final long keepAliveTimer;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private SseStream(RoutingContext ctx, Duration keepAlive) {
        this.response = ctx.response();
        this.vertx = ctx.vertx();
        this.keepAliveTimer = keepAlive.isZero() || keepAlive.isNegative()
                ? NO_TIMER
                : vertx.setPeriodic(keepAlive.toMillis(), id -> write(KEEP_ALIVE));
    }

    /**
     * Abre o stream: escreve os cabeçalhos e passa a aceitar eventos.
     * <p>
     * A partir daqui o status já foi para o cliente, e <b>nada mais pode virar um código HTTP</b> — erro
     * de execução tem de sair como evento. É por isso que o pedido é validado antes desta chamada.
     */
    static SseStream open(RoutingContext ctx, Duration keepAlive) {
        ctx.response()
                .setStatusCode(200)
                .setChunked(true)
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=UTF-8")
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .putHeader("X-Accel-Buffering", "no");
        return new SseStream(ctx, keepAlive);
    }

    /** Um evento {@code next}: um resultado de execução GraphQL, já serializado. */
    void next(String json) {
        StringBuilder event = new StringBuilder("event: next\n");
        for (String line : json.split("\n", -1)) {
            event.append("data: ").append(line).append('\n');
        }
        write(event.append('\n').toString());
    }

    /** Encerra pelo lado do servidor: evento {@code complete} e fim da resposta. */
    void complete() {
        if (open.compareAndSet(true, false)) {
            cancelKeepAlive();
            try {
                response.end(COMPLETE);
            } catch (RuntimeException e) {
                log.debugf(e, "o cliente já havia fechado o stream SSE");
            }
        }
    }

    /**
     * O cliente sumiu — fechou a aba, caiu a rede. Só solta o timer: escrever num socket morto não tem
     * para quem, e o {@code end()} do {@link #complete()} levantaria exceção.
     */
    void abandon() {
        if (open.compareAndSet(true, false)) {
            cancelKeepAlive();
        }
    }

    boolean isOpen() {
        return open.get() && !response.closed();
    }

    private void write(String frame) {
        if (!isOpen()) {
            return;
        }
        try {
            response.write(frame);
        } catch (RuntimeException e) {
            log.debugf(e, "stream SSE fechado durante a escrita");
            abandon();
        }
    }

    private void cancelKeepAlive() {
        if (keepAliveTimer != NO_TIMER) {
            vertx.cancelTimer(keepAliveTimer);
        }
    }
}

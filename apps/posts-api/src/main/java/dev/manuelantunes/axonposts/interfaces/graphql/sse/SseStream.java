package dev.manuelantunes.axonposts.interfaces.graphql.sse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

final class SseStream {
    private static final Logger log = Logger.getLogger(SseStream.class);

    private static final String KEEP_ALIVE = ":\n\n";

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

    static SseStream open(RoutingContext ctx, Duration keepAlive) {
        ctx.response()
                .setStatusCode(200)
                .setChunked(true)
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=UTF-8")
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .putHeader("X-Accel-Buffering", "no");
        return new SseStream(ctx, keepAlive);
    }

    void next(String json) {
        StringBuilder event = new StringBuilder("event: next\n");
        for (String line : json.split("\n", -1)) {
            event.append("data: ").append(line).append('\n');
        }
        write(event.append('\n').toString());
    }

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

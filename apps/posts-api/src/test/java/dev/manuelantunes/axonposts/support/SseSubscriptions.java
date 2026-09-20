package dev.manuelantunes.axonposts.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class SseSubscriptions implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration OPENS = Duration.ofSeconds(20);

    private final HttpClient client = HttpClient.newHttpClient();
    private final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
    private final CountDownLatch flowing = new CountDownLatch(1);
    private final AtomicReference<Flow.Subscription> lines = new AtomicReference<>();
    private final CompletableFuture<HttpResponse<Void>> exchange;

    private SseSubscriptions(int port, String document, Map<String, Object> variables) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload(document, variables)))
                .build();

        this.exchange = client.sendAsync(request,
                HttpResponse.BodyHandlers.fromLineSubscriber(new Frames()));

        awaitFirstLine();
    }

    public static SseSubscriptions subscribe(int port, String document, Map<String, Object> variables) {
        return new SseSubscriptions(port, document, variables);
    }

    public <T> T next(String path, Class<T> type, Duration timeout) {
        try {
            JsonNode event = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (event == null) {
                return null;
            }
            JsonNode node = event.at("/data/" + path.replace('.', '/'));
            if (node.isMissingNode() || node.isNull()) {
                throw new IllegalStateException("caminho '" + path + "' ausente no evento: " + event);
            }
            return JSON.treeToValue(node, type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido esperando o evento", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("evento ilegível", e);
        }
    }

    public boolean silentFor(Duration window) {
        try {
            return events.poll(window.toMillis(), TimeUnit.MILLISECONDS) == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido esperando o silêncio", e);
        }
    }

    @Override
    public void close() {
        Flow.Subscription subscription = lines.getAndSet(null);
        if (subscription != null) {
            subscription.cancel();
        }
        exchange.cancel(true);
        client.close();
    }

    private void awaitFirstLine() {
        try {
            if (!flowing.await(OPENS.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("o servidor não abriu o stream SSE a tempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido esperando o stream abrir", e);
        }
    }

    private static String payload(String document, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", document);
        body.put("variables", variables == null ? Map.of() : variables);
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("não consegui serializar o pedido", e);
        }
    }

    private final class Frames implements Flow.Subscriber<String> {
        private String event;
        private final StringBuilder data = new StringBuilder();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            lines.set(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            flowing.countDown();
            if (line.isEmpty()) {
                dispatch();
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }

        @Override
        public void onError(Throwable throwable) {
            flowing.countDown();
        }

        @Override
        public void onComplete() {
            flowing.countDown();
        }

        private void dispatch() {
            if ("next".equals(event) && !data.isEmpty()) {
                try {
                    events.add(JSON.readTree(data.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException("evento SSE ilegível: " + data, e);
                }
            }
            event = null;
            data.setLength(0);
        }
    }
}

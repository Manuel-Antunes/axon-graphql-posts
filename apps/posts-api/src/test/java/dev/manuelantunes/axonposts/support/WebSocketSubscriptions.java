package dev.manuelantunes.axonposts.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class WebSocketSubscriptions implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HANDSHAKE = Duration.ofSeconds(20);

    private final WebSocket socket;
    private final BlockingQueue<JsonNode> payloads = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();

    private WebSocketSubscriptions(int port, String document, Map<String, Object> variables) {
        this.socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .subprotocols("graphql-transport-ws")
                .connectTimeout(HANDSHAKE)
                .buildAsync(URI.create("ws://localhost:" + port + "/graphql"), new Listener())
                .join();

        send(Map.of("type", "connection_init", "payload", Map.of()));
        send(subscribe(document, variables));
    }

    public static WebSocketSubscriptions subscribe(int port, String document, Map<String, Object> variables) {
        return new WebSocketSubscriptions(port, document, variables);
    }

    public <T> T next(String path, Class<T> type, Duration timeout) {
        try {
            JsonNode payload = payloads.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (payload == null) {
                return null;
            }
            JsonNode node = payload.at("/data/" + path.replace('.', '/'));
            if (node.isMissingNode() || node.isNull()) {
                throw new IllegalStateException("caminho '" + path + "' ausente no evento: " + payload);
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
            return payloads.poll(window.toMillis(), TimeUnit.MILLISECONDS) == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido esperando o silêncio", e);
        }
    }

    @Override
    public void close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "fim do teste");
    }

    private static Map<String, Object> subscribe(String document, Map<String, Object> variables) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", document);
        payload.put("variables", variables == null ? Map.of() : variables);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "1");
        message.put("type", "subscribe");
        message.put("payload", payload);
        return message;
    }

    private void send(Map<String, Object> message) {
        try {
            socket.sendText(JSON.writeValueAsString(message), true).join();
        } catch (Exception e) {
            throw new IllegalStateException("falha ao enviar " + message, e);
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                accept(message);
            }
            webSocket.request(1);
            return null;
        }

        private void accept(String message) {
            try {
                JsonNode node = JSON.readTree(message);
                if ("next".equals(node.path("type").asText())) {
                    payloads.add(node.path("payload"));
                }
            } catch (Exception e) {
                throw new IllegalStateException("mensagem ilegível do WebSocket: " + message, e);
            }
        }
    }
}

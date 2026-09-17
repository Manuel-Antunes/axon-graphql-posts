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

/**
 * Assina uma subscription GraphQL por <b>WebSocket</b>, no protocolo {@code graphql-transport-ws} — o
 * transporte que o SmallRye GraphQL serve.
 *
 * <h2>Por que WebSocket, e não SSE como no projeto Spring</h2>
 * Porque é o que existe deste lado. O Spring for GraphQL serve GraphQL-over-SSE no mesmo
 * {@code POST /graphql}, e o teste de lá fala SSE porque o {@code HttpGraphQlTester} recusa subscriptions
 * sobre HTTP. O SmallRye serve o protocolo WebSocket, então é por ele que se entra.
 * <p>
 * O cliente é o {@link WebSocket} do próprio JDK: nenhuma dependência de teste a mais, e o handshake do
 * subprotocolo é uma linha.
 *
 * <h2>O protocolo, na prática</h2>
 * <ol>
 *   <li>o cliente conecta pedindo o subprotocolo {@code graphql-transport-ws};</li>
 *   <li>manda {@code {"type":"connection_init"}} e espera {@code connection_ack};</li>
 *   <li>manda {@code {"id":"1","type":"subscribe","payload":{query, variables}}};</li>
 *   <li>recebe um {@code {"id":"1","type":"next","payload":{"data":…}}} por evento.</li>
 * </ol>
 * Só os {@code next} interessam; os outros tipos ({@code ping}, {@code complete}) são ignorados, o que é
 * o que impede um {@code complete} de virar um item da fila.
 */
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

    /**
     * Abre a subscription e devolve o cliente já assinado.
     * <p>
     * A conexão é aberta <b>antes</b> de o teste provocar o evento, de propósito: o {@code emit} do Axon
     * só alcança quem já está registrado no query bus, e assinar depois testaria outra coisa.
     */
    public static WebSocketSubscriptions subscribe(int port, String document, Map<String, Object> variables) {
        return new WebSocketSubscriptions(port, document, variables);
    }

    /**
     * O próximo evento, como caminho dentro de {@code data} — por exemplo {@code onPostCreated.title}.
     *
     * @return o valor, ou {@code null} se nada chegar dentro do tempo
     */
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

    /** {@code true} se <b>nada</b> chegou no tempo dado — o caso negativo da newsletter. */
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

        /**
         * Uma mensagem pode chegar fatiada em vários frames; só o último traz {@code last = true}. Sem
         * acumular, um JSON cortado ao meio viraria erro de parse intermitente.
         */
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

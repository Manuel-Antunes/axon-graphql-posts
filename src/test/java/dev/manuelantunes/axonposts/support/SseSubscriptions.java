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

/**
 * Assina uma subscription GraphQL por <b>Server-Sent Events</b>, no modo <i>distinct connections</i> do
 * protocolo {@code graphql-sse} — o transporte que {@code interfaces.graphql.sse} acrescenta.
 *
 * <h2>O cliente é um POST comum</h2>
 * É exatamente esse o ponto do SSE: não há handshake de subprotocolo, não há quadro de controle, não há
 * biblioteca. Um {@code HttpRequest} com {@code Accept: text/event-stream}, e a resposta vai chegando.
 * O {@link WebSocketSubscriptions} ao lado precisa negociar {@code graphql-transport-ws},
 * mandar {@code connection_init}, esperar o {@code connection_ack} e só então assinar.
 *
 * <h2>Ler o fio</h2>
 * {@code BodyHandlers.fromLineSubscriber} entrega o corpo linha a linha enquanto ele chega — é o que
 * permite ler uma resposta que nunca termina. Um evento acaba na primeira linha em branco; até lá
 * acumulam-se {@code event:} e {@code data:}. Linhas que começam com {@code :} são comentário (o
 * keep-alive) e não viram evento, o que é justamente o que se quer afirmar sobre elas.
 *
 * <h2>A corrida da assinatura, e o que a fecha</h2>
 * O {@code emit} do Axon só alcança quem já está registrado no query bus, então o teste precisa ter
 * certeza de que o servidor abriu o stream <b>antes</b> de provocar o evento. Esperar o {@code 200} não
 * basta: os cabeçalhos saem antes de a execução começar. O que se espera aqui é a <b>primeira linha</b>
 * vinda do servidor — em teste o keep-alive é de 250&nbsp;ms ({@code %test} em
 * {@code application.properties}), então essa linha chega logo e com folga de sobra sobre a execução.
 */
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

    /** Abre a subscription e devolve o cliente já assinado. */
    public static SseSubscriptions subscribe(int port, String document, Map<String, Object> variables) {
        return new SseSubscriptions(port, document, variables);
    }

    /**
     * O próximo evento {@code next}, como caminho dentro de {@code data} — por exemplo
     * {@code onPostCreated.title}.
     *
     * @return o valor, ou {@code null} se nada chegar dentro do tempo
     */
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

    /** {@code true} se <b>nenhum evento</b> chegou no tempo dado. Comentário de keep-alive não conta. */
    public boolean silentFor(Duration window) {
        try {
            return events.poll(window.toMillis(), TimeUnit.MILLISECONDS) == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido esperando o silêncio", e);
        }
    }

    /** Fechar a conexão é como se cancela uma subscription em SSE: não há mensagem de "pare". */
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

    /** Monta um evento SSE a partir das linhas: {@code event:}, {@code data:} e a linha em branco. */
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
            // linha começando com ':' é comentário (keep-alive): conta como sinal de vida, nada mais
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

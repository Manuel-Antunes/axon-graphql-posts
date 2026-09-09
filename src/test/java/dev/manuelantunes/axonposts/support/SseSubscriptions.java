package dev.manuelantunes.axonposts.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assina uma subscription GraphQL <b>por SSE</b>, o transporte que esta aplicação serve.
 *
 * <h2>Por que não o {@code HttpGraphQlTester}</h2>
 * Ele recusa: {@code UnsupportedOperationException: Subscriptions not supported over HTTP}. O
 * {@code WebTestClient} por trás dele é feito para requisição-resposta, e um stream infinito não cabe
 * nesse modelo.
 * <p>
 * As alternativas seriam o {@code WebSocketGraphQlTester} — que funcionaria, mas testaria o WebSocket,
 * habilitado aqui só para o GraphiQL — ou falar SSE direto. É o que esta classe faz: o mesmo
 * {@code POST /graphql} com {@code Accept: text/event-stream} que um cliente real usaria, decodificado
 * pelo {@code ServerSentEvent} do próprio Spring.
 *
 * <h2>O protocolo GraphQL over SSE, na prática</h2>
 * O servidor emite {@code event: next} com o payload GraphQL em {@code data}, e {@code event: complete}
 * ao terminar. Só os {@code next} interessam; o filtro por nome de evento é o que impede um
 * {@code complete} de virar um item do {@code Flux}.
 */
public final class SseSubscriptions {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseSubscriptions() {
    }

    /**
     * @param path caminho dentro de {@code data}, com pontos — por exemplo {@code onPostCreated.title}
     */
    public static <T> Flux<T> subscribe(int port, String query, Map<String, Object> variables,
                                        String path, Class<T> type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("variables", variables == null ? Map.of() : variables);

        return WebClient.create("http://localhost:" + port)
                .post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .filter(event -> "next".equals(event.event()) && event.data() != null)
                .map(event -> extract(event.data(), path, type));
    }

    /** {@code onPostCreated.title} → ponteiro {@code /data/onPostCreated/title}. */
    private static <T> T extract(String payload, String path, Class<T> type) {
        try {
            JsonNode node = JSON.readTree(payload).at("/data/" + path.replace('.', '/'));
            if (node.isMissingNode() || node.isNull()) {
                throw new IllegalStateException("caminho '" + path + "' ausente no evento: " + payload);
            }
            return JSON.treeToValue(node, type);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("evento SSE ilegível: " + payload, e);
        }
    }
}

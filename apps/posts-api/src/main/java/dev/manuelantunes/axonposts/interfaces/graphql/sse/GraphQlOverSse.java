package dev.manuelantunes.axonposts.interfaces.graphql.sse;

import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.vertx.http.runtime.security.SecurityHandlerPriorities;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Monta a terceira porta do endpoint GraphQL — a de SSE — no mesmo caminho das outras duas.
 *
 * <h2>{@code @Observes Router}: o jeito do Quarkus de acrescentar uma rota</h2>
 * Não é preciso escrever uma extensão nem trazer o Quarkus REST para expor um caminho HTTP. O Quarkus
 * dispara o {@link Router} do Vert.x como evento CDI na partida, e observar esse evento é a API pública
 * para registrar uma rota ao lado das que as extensões já registraram. Três linhas, nenhuma dependência
 * nova além do {@code quarkus-vertx-http} que o próprio GraphQL já traz.
 *
 * <h2>A ordem não é decoração, e o número não é chutado</h2>
 * As rotas do Vert.x Web são avaliadas por ordem crescente, e no {@code /graphql} já existem duas: a de
 * WebSocket, em {@code -AUTHORIZATION + 1}, e a de execução HTTP, que o Quarkus numera <b>em sequência</b>
 * com as outras rotas da aplicação (na prática um dígito: 4, aqui). Registrar num número "seguro e alto"
 * é o erro óbvio — {@code 1 000} cai <i>depois</i> do handler de execução, que responde
 * {@code 406 Not Acceptable} a quem pediu {@code text/event-stream} e a rota de SSE nunca roda.
 * <p>
 * Daí {@link #ROUTE_ORDER} ser ancorado na mesma constante que o Quarkus usa, uma casa depois do
 * WebSocket: <b>depois</b> dos handlers de segurança — é o que faz a {@code SecurityIdentity} já estar
 * resolvível — e <b>antes</b> de qualquer rota de aplicação. As duas portas de subscription ficam lado a
 * lado, cada uma olhando o seu cabeçalho e devolvendo a requisição com {@code ctx.next()} quando não é
 * para ela. O POST JSON de sempre não muda de comportamento em nada.
 *
 * <h2>Um handler, duas rotas</h2>
 * {@code POST} é a forma canônica do {@code graphql-sse}. {@code GET} existe porque o {@code EventSource}
 * do navegador só sabe fazer GET — e manda {@code Accept: text/event-stream} sozinho, então
 * {@code new EventSource('/graphql?query=subscription{...}')} basta, sem biblioteca nenhuma. É a
 * diferença prática em relação ao WebSocket, onde o handshake do subprotocolo exige um cliente.
 */
@ApplicationScoped
public class GraphQlOverSse {

    /** Logo depois da rota de WebSocket, que é {@code -AUTHORIZATION + 1}. Ver o Javadoc da classe. */
    private static final int ROUTE_ORDER = (-1 * SecurityHandlerPriorities.AUTHORIZATION) + 2;

    private final CurrentIdentityAssociation identity;
    private final CurrentVertxRequest request;
    private final String rootPath;
    private final Duration keepAlive;

    public GraphQlOverSse(
            CurrentIdentityAssociation identity,
            CurrentVertxRequest request,
            @ConfigProperty(name = "quarkus.smallrye-graphql.root-path", defaultValue = "/graphql") String rootPath,
            @ConfigProperty(name = "axonposts.graphql.sse.keep-alive", defaultValue = "15s") Duration keepAlive) {
        this.identity = identity;
        this.request = request;
        this.rootPath = rootPath;
        this.keepAlive = keepAlive;
    }

    void mount(@Observes Router router) {
        GraphQlSseHandler handler = new GraphQlSseHandler(identity, request, keepAlive);
        router.route(HttpMethod.POST, rootPath).order(ROUTE_ORDER).handler(handler);
        router.route(HttpMethod.GET, rootPath).order(ROUTE_ORDER).handler(handler);
    }
}

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

@ApplicationScoped
public class GraphQlOverSse {
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

package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.eclipse.microprofile.graphql.GraphQLApi;

import io.smallrye.graphql.api.federation.link.Import;
import io.smallrye.graphql.api.federation.link.Link;
import jakarta.enterprise.context.ApplicationScoped;

@GraphQLApi
@ApplicationScoped
@SuppressWarnings("WRONG_DIRECTIVE_PLACEMENT")
@Link(
        url = "https://specs.apollo.dev/federation/v2.7",
        _import = {
                @Import(name = "@key"),
                @Import(name = "@shareable")
        })
public class FederatedSchemaApi {
}

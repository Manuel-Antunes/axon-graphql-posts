package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import io.smallrye.graphql.api.federation.Shareable;

@Shareable
public record PageInfo(
        boolean hasNextPage,
        boolean hasPreviousPage,
        String startCursor,
        String endCursor) {
    static PageInfo empty(boolean hasPreviousPage) {
        return new PageInfo(false, hasPreviousPage, null, null);
    }
}

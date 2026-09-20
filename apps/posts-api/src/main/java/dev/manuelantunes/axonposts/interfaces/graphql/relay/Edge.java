package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

@RegisterForReflection
public abstract class Edge<N> {
    private N node;
    private String cursor;

    protected Edge() {
    }

    void init(N node, String cursor) {
        this.node = node;
        this.cursor = cursor;
    }

    @NonNull
    @Description("The item at this position in the connection")
    public N getNode() {
        return node;
    }

    @NonNull
    @Description("This item's opaque position; pass it back in `after` to continue from here")
    public String getCursor() {
        return cursor;
    }
}

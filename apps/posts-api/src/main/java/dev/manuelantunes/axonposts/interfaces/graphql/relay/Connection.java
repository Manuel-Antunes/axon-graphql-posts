package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

@RegisterForReflection
public abstract class Connection<N, E extends Edge<N>> {
    private List<E> edges = List.of();
    private PageInfo pageInfo = PageInfo.empty(false);

    protected Connection() {
    }

    void init(List<E> edges, PageInfo pageInfo) {
        this.edges = List.copyOf(edges);
        this.pageInfo = pageInfo;
    }

    @NonNull
    @Description("The items on this page, each with its position")
    public List<E> getEdges() {
        return edges;
    }

    @NonNull
    @Description("Whether there is a next/previous page, and the cursors at this one's ends")
    public PageInfo getPageInfo() {
        return pageInfo;
    }
}

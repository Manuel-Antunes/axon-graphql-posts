package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class Connections {
    private Connections() {
    }

    public static <N, E extends Edge<N>, C extends Connection<N, E>> C page(
            List<N> nodes,
            boolean hasNext,
            ConnectionArgs args,
            Supplier<E> newEdge,
            Supplier<C> newConnection) {
        List<E> edges = new ArrayList<>(nodes.size());
        String startCursor = null;
        String endCursor = null;

        for (int i = 0; i < nodes.size(); i++) {
            String cursor = Cursors.encode(args.type(), args.offset() + i);
            E edge = newEdge.get();
            edge.init(nodes.get(i), cursor);
            edges.add(edge);

            if (startCursor == null) {
                startCursor = cursor;
            }
            endCursor = cursor;
        }

        C connection = newConnection.get();
        connection.init(edges, new PageInfo(hasNext, args.hasPreviousPage(), startCursor, endCursor));
        return connection;
    }

    public static <N, E extends Edge<N>, C extends Connection<N, E>> C slice(
            List<N> all,
            ConnectionArgs args,
            Supplier<E> newEdge,
            Supplier<C> newConnection) {
        int from = (int) Math.min(args.offset(), all.size());
        int to = (int) Math.min((long) from + args.limit(), all.size());

        return page(all.subList(from, to), all.size() > to, args, newEdge, newConnection);
    }
}

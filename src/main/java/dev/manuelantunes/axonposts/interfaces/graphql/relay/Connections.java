package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Monta uma connection a partir das linhas que vieram do banco. É a <b>única</b> peça que sabe paginar:
 * cursor por item, {@code pageInfo} e recorte estão aqui, e cada conexão do sistema contribui apenas com
 * dois construtores de uma linha.
 * <p>
 * Genérico de verdade, ao contrário da versão anterior desta POC, onde cada domínio passava também uma
 * função de cursor e uma fábrica de edge com assinatura própria. O que ficou: passe o tipo concreto do
 * edge e o da connection, e funciona.
 */
public final class Connections {

    private Connections() {
    }

    /**
     * Uma página que <b>já veio recortada do banco</b>, com uma linha a mais para saber se existe
     * próxima.
     * <p>
     * Pedir {@code limit + 1} e olhar se a linha extra veio é o jeito barato de responder
     * {@code hasNextPage} sem um {@code COUNT}. Quem pede a linha extra é o query handler — ver
     * {@code FindAllPostsQuery}; aqui só chega o resultado já decidido.
     *
     * @param nodes   as linhas desta página, no limite pedido
     * @param hasNext se o banco tinha pelo menos mais uma linha depois destas
     */
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

    /**
     * Recorta uma coleção <b>já carregada inteira</b> e monta a connection.
     *
     * <h3>Quando isto se justifica, e quando não</h3>
     * Vale para coleção filha pequena que um DataLoader já trouxe no lote — as tags de um post, que vêm
     * todas no mesmo {@code join fetch}. Paginar no banco por post desfaria o lote, que é o problema que
     * o lote existe para resolver.
     * <p>
     * <b>Não</b> vale quando a coleção é aberta. {@code Author.posts} usa este caminho e é uma troca
     * consciente de POC: um autor produtivo acumula milhares de posts, e trazer todos para devolver os 20
     * primeiros é desperdício que cresce com o tempo. O caminho de saída não muda a fronteira de quem
     * chama: seria uma consulta com {@code row_number() over (partition by author_id order by created_at
     * desc)} dentro da própria função de lote, devolvendo já recortado.
     */
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

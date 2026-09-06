package dev.manuelantunes.axonposts.interfaces.graphql;

import org.springframework.data.domain.OffsetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.query.ScrollSubrange;

import java.util.List;

/**
 * A tradução entre o cursor opaco do GraphQL e o par {@code offset}/{@code limit} que o resto da
 * aplicação entende. Vive na camada de interface porque é onde a Relay connection existe — nem o domínio
 * nem a aplicação conhecem cursor.
 * <p>
 * Os cursores são {@link OffsetScrollPosition} codificados em base64 pelo {@code CursorStrategy} do
 * Spring: {@code T18w} é {@code O_0}, {@code T18x} é {@code O_1}. A convenção do Spring Data é que a
 * posição aponta para a <b>última linha já vista</b> — por isso a próxima página começa em
 * {@code offset + 1}.
 */
final class Connections {

    private Connections() {
    }

    /** Cursor → índice da primeira linha da página. Sem cursor, começa em 0. */
    static long startOffset(ScrollSubrange subrange) {
        return subrange.position()
                .filter(OffsetScrollPosition.class::isInstance)
                .map(OffsetScrollPosition.class::cast)
                .filter(position -> !position.isInitial())
                .map(position -> position.getOffset() + 1)
                .orElse(0L);
    }

    /**
     * Monta a {@link Window} de uma página já recortada.
     * <p>
     * O {@code positionFunction} é o que dá cursor a cada item: o de índice {@code i} recebe a posição
     * {@code offset + i}, que é o que o cliente devolve como {@code after}.
     */
    static <T> Window<T> window(List<T> page, long offset, boolean hasNext) {
        return Window.from(page, OffsetScrollPosition.positionFunction(offset), hasNext);
    }

    /**
     * Recorta uma coleção <b>já carregada</b> e monta a {@link Window}.
     * <p>
     * Cortar em memória só se justifica para coleções filhas pequenas — as tags de um post, que o
     * DataLoader já trouxe inteiras no lote. Para uma coleção grande, o recorte teria de descer até a
     * consulta (como acontece em {@code posts}, onde o offset e o limite vão até o SQL).
     */
    static <T> Window<T> slice(List<T> all, long offset, int limit) {
        int from = (int) Math.min(offset, all.size());
        int to = (int) Math.min((long) from + limit, all.size());
        return window(all.subList(from, to), offset, all.size() > to);
    }
}

package dev.manuelantunes.axonposts.interfaces.graphql;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.query.ScrollSubrange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tradução cursor ↔ offset e o recorte em memória — a parte da cursor connection que é lógica nossa, e
 * não do Spring. É o mesmo código por trás de {@code posts} e de {@code Post.tags}.
 */
class ConnectionsTest {

    private static final List<String> ITEMS = List.of("a", "b", "c", "d", "e");

    @Test
    void withoutACursorThePageStartsAtZero() {
        assertThat(Connections.startOffset(subrange(null, 2))).isZero();
    }

    @Test
    void aCursorPointsAtTheLastSeenRowSoTheNextPageStartsAfterIt() {
        assertThat(Connections.startOffset(subrange(ScrollPosition.offset(3), 2))).isEqualTo(4);
    }

    @Test
    void theInitialPositionIsTreatedAsNoCursor() {
        assertThat(Connections.startOffset(subrange(ScrollPosition.offset(), 2))).isZero();
    }

    @Test
    void sliceCutsThePageAndFlagsThatThereIsMore() {
        Window<String> window = Connections.slice(ITEMS, 0, 2);

        assertThat(window.getContent()).containsExactly("a", "b");
        assertThat(window.hasNext()).isTrue();
    }

    @Test
    void theLastPageHasNoNext() {
        Window<String> window = Connections.slice(ITEMS, 3, 2);

        assertThat(window.getContent()).containsExactly("d", "e");
        assertThat(window.hasNext()).isFalse();
    }

    @Test
    void anOffsetPastTheEndIsAnEmptyPage() {
        Window<String> window = Connections.slice(ITEMS, 99, 2);

        assertThat(window.getContent()).isEmpty();
        assertThat(window.hasNext()).isFalse();
    }

    @Test
    void aLimitLargerThanTheCollectionIsNotAnError() {
        Window<String> window = Connections.slice(ITEMS, 0, 100);

        assertThat(window.getContent()).isEqualTo(ITEMS);
        assertThat(window.hasNext()).isFalse();
    }

    @Test
    void eachItemGetsTheCursorOfItsAbsolutePosition() {
        Window<String> window = Connections.slice(ITEMS, 2, 2);

        // o item de índice 0 desta página é a linha 2 da coleção inteira
        assertThat(window.positionAt(0)).isEqualTo(ScrollPosition.offset(2));
        assertThat(window.positionAt(1)).isEqualTo(ScrollPosition.offset(3));
    }

    private static ScrollSubrange subrange(ScrollPosition position, int count) {
        return ScrollSubrange.create(position, count, true);
    }
}

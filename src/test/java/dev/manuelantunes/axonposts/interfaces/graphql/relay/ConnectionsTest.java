package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.application.tag.view.TagView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A camada {@code relay} sozinha: cursor ↔ offset, validação dos argumentos e montagem da connection.
 * <p>
 * É a parte da cursor connection que no projeto Spring era do framework ({@code ScrollSubrange},
 * {@code Window}, {@code ConnectionTypeDefinitionConfigurer}) e aqui é código nosso — então aqui ela
 * precisa de teste. O mesmo código serve {@code posts}, {@code Post.tags} e {@code Author.posts}.
 */
class ConnectionsTest {

    private static final String TYPE = "tag";

    private static final List<TagView> ITEMS = List.of(
            new TagView("1", "a"), new TagView("2", "b"), new TagView("3", "c"),
            new TagView("4", "d"), new TagView("5", "e"));

    // ---- cursores -------------------------------------------------------------------------------

    @Test
    void aCursorRoundTrips() {
        assertThat(Cursors.decode(TYPE, Cursors.encode(TYPE, 7))).isEqualTo(7);
    }

    @Test
    void theCursorIsOpaqueAndCarriesNoPunctuation() {
        // Base64-URL sem padding: atravessa querystring e JSON sem escape
        assertThat(Cursors.encode(TYPE, 0)).doesNotContain("=", "+", "/", ":");
    }

    @Test
    void aCursorFromAnotherConnectionIsRefused() {
        String fromPosts = Cursors.encode("post", 3);

        // é o que o cursor do Spring não conseguia dizer: um O_3 servia em qualquer conexão
        assertThatThrownBy(() -> Cursors.decode(TYPE, fromPosts))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cursor inválido");
    }

    @Test
    void garbageIsRefusedAsBadRequestAndNotAsAnInternalError() {
        assertThatThrownBy(() -> Cursors.decode(TYPE, "isto-nao-e-base64-valido-%%%"))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- argumentos -----------------------------------------------------------------------------

    @Test
    void withoutACursorThePageStartsAtZero() {
        ConnectionArgs args = ConnectionArgs.of(TYPE, 2, null);

        assertThat(args.offset()).isZero();
        assertThat(args.limit()).isEqualTo(2);
        assertThat(args.hasPreviousPage()).isFalse();
    }

    @Test
    void aCursorPointsAtTheLastSeenRowSoTheNextPageStartsAfterIt() {
        ConnectionArgs args = ConnectionArgs.of(TYPE, 2, Cursors.encode(TYPE, 3));

        assertThat(args.offset()).isEqualTo(4);
        assertThat(args.hasPreviousPage()).isTrue();
    }

    @Test
    void withoutFirstTheDefaultApplies() {
        assertThat(ConnectionArgs.of(TYPE, null, null).limit()).isEqualTo(ConnectionArgs.DEFAULT_LIMIT);
    }

    @Test
    void aPageLargerThanTheCeilingIsRefused() {
        // o teto que o ScrollSubrange do Spring não tinha
        assertThatThrownBy(() -> ConnectionArgs.of(TYPE, ConnectionArgs.MAX_LIMIT + 1, null))
                .isInstanceOf(BadRequestException.class);
        assertThat(ConnectionArgs.of(TYPE, ConnectionArgs.MAX_LIMIT, null).limit())
                .isEqualTo(ConnectionArgs.MAX_LIMIT);
    }

    @Test
    void aNegativeFirstIsAnErrorAndNotASilentlyEmptyPage() {
        assertThatThrownBy(() -> ConnectionArgs.of(TYPE, -1, null))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- montagem -------------------------------------------------------------------------------

    @Test
    void sliceCutsThePageAndFlagsThatThereIsMore() {
        TagConnection connection = slice(0, 2);

        assertThat(nodes(connection)).containsExactly("a", "b");
        assertThat(connection.getPageInfo().hasNextPage()).isTrue();
        assertThat(connection.getPageInfo().hasPreviousPage()).isFalse();
    }

    @Test
    void theLastPageHasNoNextButHasPrevious() {
        TagConnection connection = slice(3, 2);

        assertThat(nodes(connection)).containsExactly("d", "e");
        assertThat(connection.getPageInfo().hasNextPage()).isFalse();
        assertThat(connection.getPageInfo().hasPreviousPage()).isTrue();
    }

    @Test
    void anOffsetPastTheEndIsAnEmptyPageWithoutCursors() {
        TagConnection connection = slice(99, 2);

        assertThat(connection.getEdges()).isEmpty();
        assertThat(connection.getPageInfo().startCursor()).isNull();
        assertThat(connection.getPageInfo().endCursor()).isNull();
        assertThat(connection.getPageInfo().hasNextPage()).isFalse();
    }

    @Test
    void aLimitLargerThanTheCollectionIsNotAnError() {
        TagConnection connection = slice(0, 100);

        assertThat(nodes(connection)).containsExactly("a", "b", "c", "d", "e");
        assertThat(connection.getPageInfo().hasNextPage()).isFalse();
    }

    @Test
    void eachItemGetsTheCursorOfItsAbsolutePosition() {
        TagConnection connection = slice(2, 2);

        // o item de índice 0 desta página é a linha 2 da coleção inteira
        assertThat(connection.getEdges().get(0).getCursor()).isEqualTo(Cursors.encode(TYPE, 2));
        assertThat(connection.getEdges().get(1).getCursor()).isEqualTo(Cursors.encode(TYPE, 3));
        assertThat(connection.getPageInfo().startCursor()).isEqualTo(Cursors.encode(TYPE, 2));
        assertThat(connection.getPageInfo().endCursor()).isEqualTo(Cursors.encode(TYPE, 3));
    }

    /**
     * O outro caminho de montagem: a página já veio recortada do banco e {@code hasNext} é a resposta da
     * linha extra que o query handler pediu.
     */
    @Test
    void pageTrustsTheHasNextItReceives() {
        ConnectionArgs args = ConnectionArgs.of(TYPE, 2, null);
        TagConnection connection = Connections.page(
                ITEMS.subList(0, 2), true, args, TagEdge::new, TagConnection::new);

        assertThat(nodes(connection)).containsExactly("a", "b");
        assertThat(connection.getPageInfo().hasNextPage()).isTrue();
    }

    private static TagConnection slice(long offset, int limit) {
        ConnectionArgs args = new ConnectionArgs(offset, limit, TYPE);
        return Connections.slice(ITEMS, args, TagEdge::new, TagConnection::new);
    }

    private static List<String> nodes(TagConnection connection) {
        return connection.getEdges().stream().map(edge -> edge.getNode().name()).toList();
    }
}

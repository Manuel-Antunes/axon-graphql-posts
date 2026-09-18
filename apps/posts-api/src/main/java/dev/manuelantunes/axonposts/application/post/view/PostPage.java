package dev.manuelantunes.axonposts.application.post.view;


import java.util.List;

/**
 * Uma fatia de posts: as linhas pedidas, de onde elas começam, e se existe mais coisa depois.
 * <p>
 * Existe para atravessar o query bus do Axon como um <b>tipo concreto</b>. O {@code Window} do Spring
 * Data seria o candidato natural, mas ele é genérico ({@code Window<PostView>}) e o gateway do Axon pede
 * um {@code Class<R>} — o parâmetro de tipo se perderia no caminho. O controller converte este record em
 * {@code Window} na borda, onde a cursor connection é montada.
 *
 * @param items   as linhas desta página, já no limite pedido
 * @param offset  índice da primeira linha em {@code items} dentro da coleção inteira
 * @param hasNext se existe pelo menos mais uma linha depois desta página
 */
public record PostPage(List<PostView> items, long offset, boolean hasNext) {

    public PostPage {
        items = List.copyOf(items);
    }
}

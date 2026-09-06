package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.TagRef;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Porta do repositório de Posts. Guarda e devolve o próprio {@link Post} — não existe mais um DTO de
 * leitura intermediário: a entidade de domínio é o que está gravado.
 * <p>
 * A paginação é o par {@code offset}/{@code limit}, e não {@code ScrollPosition}/{@code Window} do
 * Spring Data: assim o domínio não herda o vocabulário de paginação de um framework. Quem traduz cursor
 * em offset é o controller; quem traduz offset em scroll é o adapter.
 */
public interface PostRepository {

    void save(Post post);

    Optional<Post> findById(PostId postId);

    /**
     * @param offset índice da primeira linha desejada, contando de 0, na ordem de criação
     * @param limit  quantidade máxima de linhas a devolver
     */
    List<Post> findAll(long offset, int limit);

    /**
     * As tags de vários posts de uma vez. É o método que existe para ser chamado <b>em lote</b>: o
     * DataLoader do campo {@code Post.tags} junta os ids de todos os posts de uma mesma resposta GraphQL
     * e pergunta uma vez só, em vez de uma vez por post.
     * <p>
     * Posts sem tag simplesmente não aparecem no mapa — cabe a quem chama tratar a ausência como lista
     * vazia.
     */
    Map<PostId, List<TagRef>> findTagsByPostIds(Collection<PostId> postIds);
}

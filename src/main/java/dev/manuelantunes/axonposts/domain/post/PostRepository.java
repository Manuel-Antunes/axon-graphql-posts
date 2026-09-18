package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;

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

    List<Post> findAllById(Collection<PostId> postIds);

    /**
     * As tags de vários posts de uma vez. É o método que existe para ser chamado <b>em lote</b>: o
     * DataLoader do campo {@code Post.tags} junta os ids de todos os posts de uma mesma resposta GraphQL
     * e pergunta uma vez só, em vez de uma vez por post.
     * <p>
     * Posts sem tag simplesmente não aparecem no mapa — cabe a quem chama tratar a ausência como lista
     * vazia.
     */
    Map<PostId, List<Tag>> findTagsByPostIds(Collection<PostId> postIds);

    /**
     * Os posts de vários autores de uma vez, mais recentes primeiro. Existe pelo mesmo motivo do
     * {@link #findTagsByPostIds}: é o campo {@code Author.posts} do GraphQL, e sem lote uma resposta com
     * N autores viraria N consultas.
     * <p>
     * Autores sem post não aparecem no mapa.
     */
    Map<UserId, List<Post>> findByAuthorIds(Collection<UserId> authorIds);

    /**
     * Torna a linha de um post apagado <b>visível</b> de novo.
     *
     * <h3>Por que isto não é só {@code post.restore()} + {@code save(post)}</h3>
     * O {@code @SQLRestriction} do {@link Post} filtra <i>toda</i> consulta, inclusive o SELECT que o
     * {@code merge} faz por dentro do {@code save}. Para o JPA, a linha apagada não existe: o merge não a
     * acha, conclui que a entidade é nova e tenta um INSERT — que estoura na chave primária.
     * <p>
     * É a limitação inerente a qualquer exclusão lógica sempre-ligada, e a razão de restaurar precisar de
     * uma escrita que passe por fora do filtro. Quem chama faz o par: primeiro este método, para a linha
     * reaparecer, depois {@link #save} para gravar o resto do estado.
     */
    void restore(PostId postId);
}

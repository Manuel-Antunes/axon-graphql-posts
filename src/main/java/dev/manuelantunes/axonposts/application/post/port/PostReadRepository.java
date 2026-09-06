package dev.manuelantunes.axonposts.application.post.port;

import dev.manuelantunes.axonposts.application.post.PostView;

import java.util.List;
import java.util.Optional;

/**
 * Porta (driven port) do read model. A camada de aplicação só conhece esta interface; a implementação
 * concreta (SQLite via Spring Data JPA) vive em {@code infrastructure} — trocar de banco é trocar o
 * adapter, sem tocar em command handler, event handler, query ou subscription.
 * <p>
 * A porta é deliberadamente burra: {@code save} sobrescreve tudo e a paginação é o par
 * {@code offset}/{@code limit}, não {@code ScrollPosition}/{@code Window} do Spring Data. Assim a
 * aplicação não herda o vocabulário de paginação de um framework — quem traduz cursor em offset é o
 * controller, e quem traduz offset em scroll é o adapter.
 */
public interface PostReadRepository {

    void save(PostView view);

    Optional<PostView> findById(String postId);

    /**
     * @param offset índice da primeira linha desejada, contando de 0, na ordem de criação
     * @param limit  quantidade máxima de linhas a devolver
     */
    List<PostView> findAll(long offset, int limit);
}

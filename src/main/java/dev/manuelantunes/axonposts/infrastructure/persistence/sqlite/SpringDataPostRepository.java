package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA gerado em runtime — o "driver" do SQLite.
 * <p>
 * A leitura paginada usa o suporte nativo a <i>scrolling</i> do Spring Data: {@link ScrollPosition} de
 * entrada e {@link Window} de saída, com {@link Limit} dinâmico. É a mesma abstração que o Spring GraphQL
 * usa para montar a cursor connection, então nada precisa ser traduzido no caminho.
 * <p>
 * A ordenação inclui o {@code id} como desempate: {@code createdAt} sozinho não é único (dois posts
 * criados no mesmo instante embaralhariam entre páginas, e a paginação por offset perderia ou repetiria
 * linhas).
 */
interface SpringDataPostRepository extends JpaRepository<PostEntity, String> {

    Window<PostEntity> findAllByOrderByCreatedAtAscIdAsc(ScrollPosition position, Limit limit);
}

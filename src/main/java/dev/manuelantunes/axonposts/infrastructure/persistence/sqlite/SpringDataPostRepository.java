package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA gerado em runtime — o "driver" do SQLite. Trabalha direto sobre a entidade
 * de domínio {@link Post}, que é ela própria a entidade JPA.
 * <p>
 * A leitura paginada usa o suporte nativo a <i>scrolling</i> do Spring Data: {@link ScrollPosition} de
 * entrada e {@link Window} de saída, com {@link Limit} dinâmico.
 * <p>
 * A ordenação inclui o id como desempate: {@code createdAt} sozinho não é único, e dois posts criados no
 * mesmo instante fariam a paginação por offset pular ou repetir linhas.
 */
interface SpringDataPostRepository extends JpaRepository<Post, PostId> {

    Window<Post> findAllByOrderByCreatedAtAscIdAsc(ScrollPosition position, Limit limit);
}

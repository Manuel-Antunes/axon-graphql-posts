package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

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

    /**
     * O {@code @EntityGraph} é o que transforma o {@code author} EAGER num <b>join</b> nesta consulta.
     * Sem ele, o Hibernate honraria o EAGER com um SELECT por autor distinto depois de trazer a página —
     * correto, mas desnecessário quando um join resolve.
     */
    @EntityGraph(attributePaths = "author")
    Window<Post> findAllByOrderByCreatedAtAscIdAsc(ScrollPosition position, Limit limit);

    /**
     * Os posts de vários autores, mais recentes primeiro, numa consulta só — o lote do campo
     * {@code Author.posts}. O {@code join fetch} do autor evita o SELECT extra por post.
     */
    @Query("select p from Post p join fetch p.author a where a.id in :authorIds order by p.createdAt desc, p.id desc")
    List<Post> findAllByAuthorIdIn(@Param("authorIds") Collection<UserId> authorIds);

    /**
     * Carrega vários posts já com as tags, numa consulta só — a coleção é {@code LAZY}, então sem o
     * {@code join fetch} o Hibernate faria um SELECT por post e o lote do DataLoader não evitaria nada.
     * <p>
     * O {@code join fetch} devolve uma linha por tag; o Hibernate 6 deduplica os roots sozinho, sem
     * precisar de {@code distinct} (que, se escrito, iria parar no SQL e mudaria o plano à toa).
     */
    /**
     * A escrita que ignora o {@code @SQLRestriction}. Precisa ser <b>nativa</b>: JPQL passa pelo
     * mapeamento da entidade, e o filtro entraria junto — o UPDATE não encontraria a linha que veio
     * apagar de volta.
     */
    @Modifying
    @Query(value = "update posts set deleted_at = null where id = :id", nativeQuery = true)
    int restoreById(@Param("id") String id);

    @Query("select p from Post p join fetch p.author left join fetch p.tags where p.id in :ids")
    List<Post> findAllWithTagsByIdIn(@Param("ids") Collection<PostId> ids);
}

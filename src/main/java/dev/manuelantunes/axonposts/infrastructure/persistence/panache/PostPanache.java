package dev.manuelantunes.axonposts.infrastructure.persistence.panache;

import java.util.Collection;
import java.util.List;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O "driver" do PostgreSQL para {@link Post}: um repositório Panache com as consultas que existem de
 * fato. Trabalha direto sobre a entidade de domínio, que é ela própria a entidade JPA.
 * <p>
 * É o papel que o {@code SpringDataPostRepository} tem no projeto Spring, e como lá ele é
 * <b>pacote-visível</b>: quem o usa é o adapter ao lado, e ninguém mais. O domínio conhece a porta
 * {@code PostRepository}; esta classe é detalhe de infraestrutura.
 *
 * <h2>Por que duas classes, e não uma</h2>
 * Porque {@code PanacheRepositoryBase.findById(Id)} devolve a entidade e a porta do domínio devolve
 * {@code Optional} — mesma assinatura, retornos incompatíveis, e o compilador recusa uma classe que
 * tente ser as duas coisas. A separação que o choque força é a mesma que o projeto Spring já fazia por
 * escolha: adapter de um lado, repositório gerado do outro.
 */
@ApplicationScoped
class PostPanache implements PanacheRepositoryBase<Post, PostId> {

    /**
     * A página, na ordem de criação e com o {@code id} como desempate — {@code createdAt} sozinho não é
     * único, e dois posts criados no mesmo instante fariam a paginação por offset pular ou repetir
     * linhas.
     * <p>
     * O {@code join fetch} do autor é o que o {@code @EntityGraph} fazia na versão Spring: sem ele o
     * Hibernate honraria o {@code EAGER} do {@code Post.author} com um SELECT por autor distinto depois
     * de trazer a página — correto, mas desnecessário quando um join resolve.
     */
    List<Post> page(long offset, int limit) {
        return getEntityManager()
                .createQuery("select p from Post p join fetch p.author "
                        + "order by p.createdAt asc, p.id asc", Post.class)
                .setFirstResult((int) offset)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * Os posts de vários autores, mais recentes primeiro, numa consulta só — o lote do campo
     * {@code Author.posts}. O {@code join fetch} do autor evita o SELECT extra por post.
     */
    List<Post> byAuthorIds(Collection<UserId> authorIds) {
        return getEntityManager()
                .createQuery("select p from Post p join fetch p.author a where a.id in :authorIds "
                        + "order by p.createdAt desc, p.id desc", Post.class)
                .setParameter("authorIds", authorIds)
                .getResultList();
    }

    /**
     * Vários posts já com as tags, numa consulta só — a coleção é {@code LAZY}, então sem o
     * {@code join fetch} o Hibernate faria um SELECT por post e o lote não evitaria nada.
     * <p>
     * O {@code join fetch} devolve uma linha por tag; o Hibernate 6 deduplica os roots sozinho, sem
     * precisar de {@code distinct} (que, se escrito, iria parar no SQL e mudaria o plano à toa).
     */
    List<Post> withTagsByIds(Collection<PostId> ids) {
        return getEntityManager()
                .createQuery("select p from Post p join fetch p.author left join fetch p.tags "
                        + "where p.id in :ids", Post.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    /**
     * A escrita que ignora o {@code @SQLRestriction}. Precisa ser <b>nativa</b>: JPQL passa pelo
     * mapeamento da entidade, e o filtro entraria junto — o UPDATE não encontraria a linha que veio
     * trazer de volta.
     */
    int undelete(PostId postId) {
        return getEntityManager()
                .createNativeQuery("update posts set deleted_at = null where id = :id")
                .setParameter("id", postId.value())
                .executeUpdate();
    }
}

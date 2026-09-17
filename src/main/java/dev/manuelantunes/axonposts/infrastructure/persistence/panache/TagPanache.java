package dev.manuelantunes.axonposts.infrastructure.persistence.panache;

import java.util.Optional;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** O "driver" do PostgreSQL para {@link Tag}. Ver {@link PostPanache} sobre por que são duas classes. */
@ApplicationScoped
class TagPanache implements PanacheRepositoryBase<Tag, TagId> {

    /**
     * O {@code name} é um {@code @Embedded TagName}, então a consulta navega até o campo do embeddable.
     * Sem diferenciar caixa, pelo mesmo motivo que {@code TagName.sameAs} não diferencia: "Untagged" e
     * "untagged" são a mesma tag.
     */
    Optional<Tag> byName(String name) {
        return find("lower(name.value) = lower(?1)", name).firstResultOptional();
    }
}

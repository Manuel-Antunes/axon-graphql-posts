package dev.manuelantunes.axonposts.infrastructure.persistence.sqlite;

import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositório Spring Data JPA das Tags. O {@code name} é um {@code @Embedded TagName}, então a query
 * derivada navega até o campo do embeddable — {@code NameValueIgnoreCase} é "name.value, sem caixa".
 */
interface SpringDataTagRepository extends JpaRepository<Tag, TagId> {

    Optional<Tag> findByNameValueIgnoreCase(String name);
}

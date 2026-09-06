package dev.manuelantunes.axonposts.mapper;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.infrastructure.persistence.sqlite.PostEntity;
import org.mapstruct.Mapper;

/**
 * Aplicação ↔ infraestrutura: read model {@link PostView} e entidade JPA {@link PostEntity}, nos dois
 * sentidos.
 * <p>
 * Este é o mapeamento em que o MapStruct trabalha sozinho: {@code PostEntity} é um JavaBean (getters e
 * setters) e {@code PostView} é um record de campos homônimos, então os dois métodos abaixo são só a
 * assinatura — o corpo é gerado. É também o que mantém a entidade JPA invisível para a aplicação: quem
 * sai daqui para cima é sempre {@code PostView}.
 */
@Mapper(componentModel = "spring")
public interface PostEntityMapper {

    PostView toView(PostEntity entity);

    PostEntity toEntity(PostView view);
}

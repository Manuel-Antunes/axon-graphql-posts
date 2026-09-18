package dev.manuelantunes.axonposts.infrastructure.axon;

import java.util.Map;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;

import at.meks.quarkiverse.axon.runtime.customizations.EventSourcedEntityConfigurer;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * <b>Qual é o tipo do id de cada entidade event-sourced.</b> É a única coisa que a extensão de Quarkus
 * não consegue descobrir sozinha, e por isso é o único arquivo de configuração do Axon que sobrou.
 *
 * <h2>Por que o tipo do id não está numa anotação</h2>
 * Porque ele nunca esteve. No Axon 5 o par (tipo do id, classe da entidade) é argumento de
 * {@link EventSourcedEntityModule#autodetected}, e não um atributo de {@code @EventSourcedEntity} — o
 * {@code idType} do {@code @EventSourced} do módulo Spring existia só para o scan ter onde lê-lo.
 * <p>
 * A extensão resolve isso com uma anotação própria, {@code @IdType(PostId.class)} na entidade, caindo em
 * {@code String} quando ela não está lá. Este projeto <b>não</b> a usa, e a escolha é deliberada: pôr uma
 * anotação de uma extensão de Quarkus dentro de {@code domain} seria a primeira dependência do domínio
 * para uma biblioteca de plataforma — exatamente o que a conversão inteira provou não ser necessário
 * (o domínio atravessou de Spring para Quarkus sem uma linha alterada). Implementar o
 * {@code EventSourcedEntityConfigurer} custa este mapa e mantém a seta apontando para dentro.
 *
 * <h2>Entidade nova</h2>
 * Uma linha no mapa. A descoberta da entidade em si é da extensão: {@code @EventSourcedEntity} na classe
 * basta. Sem a linha, o id dela seria {@code String} e o primeiro command falharia ao reidratar.
 */
@ApplicationScoped
public class EventSourcedEntities implements EventSourcedEntityConfigurer {

    private static final Map<Class<?>, Class<?>> ID_TYPES = Map.of(
            Post.class, PostId.class,
            Tag.class, TagId.class,
            User.class, UserId.class);

    /**
     * @param idClass o que a extensão inferiu — {@code String} por default. É ignorado quando a entidade
     *                está no mapa, e serve de fallback para uma entidade que alguém acrescente sem
     *                lembrar desta classe.
     */
    @Override
    public <T> EventSourcedEntityModule<?, T> createConfigurer(Class<T> entity, Class<?> idClass) {
        return EventSourcedEntityModule.autodetected(ID_TYPES.getOrDefault(entity, idClass), entity);
    }
}

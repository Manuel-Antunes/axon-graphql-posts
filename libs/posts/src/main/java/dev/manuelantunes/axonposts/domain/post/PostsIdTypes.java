package dev.manuelantunes.axonposts.domain.post;

import java.util.Map;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.AggregateIdTypes;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Os tipos de id que <b>este módulo</b> declara ao Axon: os dois agregados que ele contém.
 * <p>
 * Acrescentar um agregado aqui não toca em nada fora desta lib — é o que a porta
 * {@link AggregateIdTypes} compra.
 */
@ApplicationScoped
public class PostsIdTypes implements AggregateIdTypes {

    @Override
    public Map<Class<?>, Class<?>> idTypes() {
        return Map.of(
                Post.class, PostId.class,
                Tag.class, TagId.class);
    }
}

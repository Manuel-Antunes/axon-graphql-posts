package dev.manuelantunes.axonposts.application.post;

import java.time.Instant;

/**
 * Read model do Post — modelo da <b>aplicação</b>, não do domínio: existe para ser lido, não para
 * decidir nada. Por isso é feito de primitivos e não tem invariante nenhuma.
 * <p>
 * É o que as queries devolvem <b>e</b> o que as subscriptions emitem, então o cliente GraphQL recebe
 * sempre o mesmo shape, venha de {@code post}, {@code posts}, {@code onPostCreated} ou
 * {@code onPostUpdated}.
 *
 * @param version quantidade de eventos aplicados a esse Post (1 = só criado)
 */
public record PostView(
        String id,
        String title,
        String content,
        String author,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}

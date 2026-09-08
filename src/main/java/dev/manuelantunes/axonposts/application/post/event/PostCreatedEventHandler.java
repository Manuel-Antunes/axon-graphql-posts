package dev.manuelantunes.axonposts.application.post.event;

import dev.manuelantunes.axonposts.application.post.subscription.OnPostCreatedSubscription.OnPostCreated;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handler de <b>um</b> evento de domínio: {@link PostCreatedEvent}. Faz uma coisa só — notificar quem
 * estiver ouvindo {@code onPostCreated}.
 * <p>
 * A view é montada a partir do <b>payload do evento</b>, não do banco, e isso é deliberado: o
 * {@code AssignDefaultTagOnPostCreated} reage ao mesmo evento e vai atribuir a tag padrão, e a ordem
 * entre dois handlers do mesmo processor não é garantida. Lendo o banco, o que este emit publicasse
 * dependeria de quem rodou primeiro. Lendo o evento, o {@code onPostCreated} publica sempre a mesma
 * coisa: o post <b>como ele nasceu</b> — versão 1, sem tags. A tag chega logo em seguida pelo
 * {@code onPostUpdated}, que é a ordem em que os fatos de fato aconteceram.
 * <p>
 * O {@link QueryUpdateEmitter} é injetado por parâmetro (Axon 5) e já vem ligado ao
 * {@code ProcessingContext} do evento: o emit sai só depois do commit.
 */
@Component
public class PostCreatedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PostCreatedEventHandler.class);

    @EventHandler
    public void on(PostCreatedEvent event, QueryUpdateEmitter emitter) {
        PostView view = new PostView(
                event.postId().value(),
                event.title(),
                event.content(),
                event.author(),
                event.occurredAt(),
                event.occurredAt(),
                PostVersion.initial().value()
        );

        log.debug("PostCreated {} → emitindo para onPostCreated", view.id());

        // tópico global: todo assinante de OnPostCreated recebe
        emitter.emit(OnPostCreated.class, subscription -> true, view);
    }
}

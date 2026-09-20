package dev.manuelantunes.axonposts.application.post.projection;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A projeção de {@link PostUpdatedEvent} <b>não grava nada</b> — e ainda assim existe.
 *
 * <h2>O que ela guarda</h2>
 * A invariante de que o command salvou a linha ANTES do commit que apendou o evento. Ao contrário do
 * {@code PostCreated}, não há caminho em que um {@code PostUpdated} chegue aqui sem command local: esta
 * aplicação publica mudanças de post, não as recebe. Uma linha ausente, portanto, não é atraso — é
 * estado incoerente, e a única resposta correta é abortar o append junto.
 * <p>
 * É por isso que ela é do pacote das <b>projeções</b>, e não do pacote dos handlers que notificam: só
 * um processor subscribing roda dentro da transação que grava, e só de lá uma exceção desfaz a escrita.
 * A mesma leitura em {@code PostUpdatedEventHandler} apenas avisa e segue, porque lá uma exceção
 * travaria o cursor do processor streaming para sempre — ver o Javadoc de lá.
 *
 * <h2>Por que não checar dentro do command</h2>
 * Porque o que se quer afirmar é sobre o <b>resultado</b> da transação, não sobre a decisão. O command
 * chama {@code posts.save}; se um dia o {@code merge} deixar de acontecer por qualquer razão — um
 * refactor, um {@code flush} que não veio, um caminho novo que esquece de salvar —, é aqui que isso
 * aparece, e aparece como falha da escrita inteira, não como um post que some da leitura seguinte.
 */
@ApplicationScoped
public class PostUpdatedProjection {

    private final PostRepository posts;

    public PostUpdatedProjection(PostRepository posts) {
        this.posts = posts;
    }

    @EventHandler
    public void on(PostUpdatedEvent event) {
        posts.findById(event.postId())
                .orElseThrow(() -> new IllegalStateException(
                        "PostUpdated de um Post que não está no banco: " + event.postId()
                                + " — o command deveria tê-lo salvo antes do commit"));
    }
}

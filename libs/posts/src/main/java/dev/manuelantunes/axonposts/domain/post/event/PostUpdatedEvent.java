package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;
import java.util.List;

/**
 * Evento de domínio: o conteúdo editável de um Post mudou — título, corpo e/ou tags. Disparado por
 * {@code post.update(...)} e por {@code post.assignTag(...)}.
 * <p>
 * <b>Todo campo é o estado resultante, inclusive a versão.</b> Nada aqui é delta, e é isso que torna
 * {@code Post.on(evento)} <i>idempotente</i>: aplicar o mesmo evento duas vezes deixa a entidade no
 * mesmo lugar que aplicá-lo uma vez.
 * <p>
 * Essa idempotência não é preciosismo. A entidade é mutável (o JPA exige), e o evento é aplicado por
 * dois caminhos: o domínio chama {@code on(...)} ao decidir, para devolver o Post pronto para salvar, e
 * o Axon aplica o mesmo evento ao apendá-lo. Se a versão fosse {@code version + 1} calculado no
 * {@code on}, ela contaria dois. Vindo pronta no evento, os dois caminhos convergem para o mesmo estado.
 * <p>
 * O autor não muda com um update, mas o {@code authorId} vem no payload assim mesmo, como
 * {@code @EventTag}: é o que faz "todos os eventos do autor X" ser um critério de event store e o que a
 * subscription {@code onPostUpdated(authorId:)} usa como tópico. Um evento que não carrega a tag
 * simplesmente não aparece nessa leitura, e a newsletter perderia justamente as atualizações.
 *
 * @param version versão resultante do post depois deste evento
 */
@Event(namespace = "posts", name = "PostUpdated", version = "1.0.0")
public record PostUpdatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        /*
         * SEM @EventTag, e a razão está em PostCreatedEvent: o event store JPA do Axon 5.3.1 é
         * aggregate mode e aceita UMA tag por evento.
         */
        UserId authorId,
        List<Tag> tags,
        long version,
        Instant occurredAt
) implements DomainEvent {

    public PostUpdatedEvent {
        tags = List.copyOf(tags);
    }

    /**
     * Uma tag como ela atravessa o evento: id e nome, sem tipo do agregado Tag.
     * <p>
     * Evento é contrato — fica gravado para sempre e pode ser lido por quem não conhece as classes deste
     * projeto. Por isso primitivos, e por isso um record próprio em vez de reusar o {@code TagRef} do
     * domínio: mudar o value object não pode mudar o que já está no event store.
     */
    public record Tag(String tagId, String name) {
    }
}

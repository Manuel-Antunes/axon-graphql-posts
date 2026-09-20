package dev.manuelantunes.axonposts.nativesupport.deployment;

import java.time.Instant;
import java.util.List;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * As FORMAS que o processor tem de reconhecer, em miniatura.
 *
 * <h2>Por que classes de verdade e não um índice montado à mão</h2>
 * Porque um índice inventado pelo próprio teste afirmaria sobre o que o teste acha que o Quarkus
 * entrega, e não sobre o que ele entrega. Aqui as anotações são as do Axon e o índice sai do Jandex,
 * que é o mesmo que roda no augmentation — o que muda é só o tamanho.
 *
 * <h2>Cada fixture existe por uma falha que já aconteceu</h2>
 * As formas abaixo são as do domínio real deste projeto, reduzidas: um evento que carrega uma lista de
 * records ANINHADOS (a falha nº 6 do Javadoc da extensão, que parou a saga na versão 1), um agregado
 * POLIMÓRFICO com {@code concreteTypes} (o {@code User}), e um handler cuja classe só é alcançável
 * pela anotação num MÉTODO.
 */
final class Fixtures {

    private Fixtures() {
    }

    /** O tipo ANINHADO. Ele não tem anotação nenhuma — é só um componente do evento. */
    record AssignedTag(String tagId, String name) {
    }

    /** E este é aninhado DENTRO do aninhado: o fecho tem de ser transitivo, não de um nível. */
    record TagColour(String hex) {
    }

    record DeepTag(String tagId, TagColour colour) {
    }

    /**
     * O evento que carrega os aninhados dentro de um {@code List<>}.
     * <p>
     * É a forma exata que quebrou em produção: {@code PostCreatedEvent} era registrado, o
     * {@code AssignedTag} dentro dele não, e a serialização morria com
     * {@code ConversionException ... to 'byte[]'} — com o contador de erros do Lambda em ZERO.
     */
    @Event(namespace = "test", name = "PostCreated", version = "1.0.0")
    record PostCreatedEvent(@EventTag String postId, List<AssignedTag> tags, Instant occurredAt) {
    }

    @Event(namespace = "test", name = "PostDeepened", version = "1.0.0")
    record PostDeepenedEvent(@EventTag String postId, DeepTag tag) {
    }

    @Command(namespace = "test", name = "CreatePost", version = "1.0.0")
    record CreatePost(@TargetEntityId String postId) {
    }

    /** Agregado POLIMÓRFICO: o tipo concreto só aparece no atributo da anotação. */
    @EventSourcedEntity(concreteTypes = {Author.class, Reader.class})
    static class User {
    }

    static class Author extends User {
    }

    static class Reader extends User {
    }

    /** A classe só é alcançável pela anotação no MÉTODO — não há anotação de classe aqui. */
    static class PostProjection {

        @EventHandler
        void on(PostCreatedEvent event) {
        }
    }

    static class PostCommandHandler {

        @CommandHandler
        void handle(CreatePost command) {
        }
    }

    /** Ninguém a referencia e ela não tem anotação: não pode entrar no registro. */
    record Unrelated(String value) {
    }
}

package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostDeletedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostRestoredEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.exception.NotThePostAuthorException;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.domain.shared.AlreadyDeletedException;
import dev.manuelantunes.axonposts.domain.shared.NotDeletedException;
import dev.manuelantunes.axonposts.domain.post.vo.PostContent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.support.RecordingDomainEvents;
import dev.manuelantunes.axonposts.support.UserFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes puros de domínio: decidir e evoluir, sem nenhum framework no caminho — nem Axon, nem Spring,
 * nem JPA, mesmo a entidade sendo mapeada. O único colaborador é o {@link RecordingDomainEvents}, que é
 * a porta de saída de eventos do próprio domínio.
 */
class PostTest {

    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    /**
     * A Tag como o domínio a vê aqui: uma referência, com id e nome e nada mais. É a mesma forma que o
     * replay produz, e basta — {@code Post} só compara tags por id.
     */
    private static final Tag UNTAGGED = Tag.reference(TagId.of("tag-1"), TagName.of("Untagged"));

    /**
     * O autor. O {@code Post} só precisa do id dele — quem garante que esse id existe e é de um autor é a
     * chave estrangeira, não uma consulta.
     */
    private static final Author AUTHOR = UserFixtures.author();

    private final RecordingDomainEvents events = new RecordingDomainEvents();

    @Test
    void createWithoutTagsRaisesOnlyPreCreatedAndLeavesThePostIncomplete() {
        PostId id = PostId.newId();

        Post post = Post.create(id, "  Título  ", " conteúdo ", AUTHOR, List.of(), T0, events);

        assertThat(events.single())
                .isEqualTo(new PostPreCreatedEvent(id, "Título", "conteúdo", UserFixtures.AUTHOR_ID, T0));
        assertThat(post.id()).isEqualTo(id);
        assertThat(post.title()).isEqualTo(PostTitle.of("Título"));
        assertThat(post.content()).isEqualTo(PostContent.of("conteúdo"));
        assertThat(post.author()).isEqualTo(AUTHOR);
        assertThat(post.createdAt()).isEqualTo(T0);
        assertThat(post.version()).isEqualTo(PostVersion.initial());
        assertThat(post.tags()).isEmpty();
        assertThat(post.hasNoTags()).isTrue();
        assertThat(post.isComplete()).as("sem tag, o post fica esperando a saga de tagueamento").isFalse();
        assertThat(post.publishedAt()).isNull();
    }

    /**
     * O outro caminho de {@code create}: com tags, os DOIS eventos saem na mesma unidade de trabalho e o
     * post já nasce completo, na versão 2. É o que o {@code createPost} faria se a borda GraphQL
     * oferecesse tags — e é a prova de que a saga é um caminho, não o único.
     */
    @Test
    void createWithTagsRaisesBothEventsAndThePostIsBornComplete() {
        PostId id = PostId.newId();

        Post post = Post.create(id, "Título", "conteúdo", AUTHOR, List.of(UNTAGGED), T0, events);

        assertThat(events.raised()).containsExactly(
                new PostPreCreatedEvent(id, "Título", "conteúdo", UserFixtures.AUTHOR_ID, T0),
                new PostCreatedEvent(id, "Título", "conteúdo", UserFixtures.AUTHOR_ID,
                        List.of(new PostCreatedEvent.AssignedTag("tag-1", "Untagged")), 2, T0));
        assertThat(post.isComplete()).isTrue();
        assertThat(post.publishedAt()).isEqualTo(T0);
        assertThat(post.version()).isEqualTo(new PostVersion(2));
        assertThat(post.tags()).containsExactly(UNTAGGED);
    }

    @Test
    void completeRaisesPostCreatedAndTakesThePostToVersionTwo() {
        Post post = preCreatedPost();

        post.complete(List.of(UNTAGGED), T1, events);

        assertThat(events.single()).isEqualTo(new PostCreatedEvent(
                PostId.of("post-1"), "Título", "conteúdo", UserFixtures.AUTHOR_ID,
                List.of(new PostCreatedEvent.AssignedTag("tag-1", "Untagged")), 2, T1));
        assertThat(post.isComplete()).isTrue();
        assertThat(post.version()).isEqualTo(new PostVersion(2));
    }

    /**
     * A invariante que protege a saga de uma decisão de tagueamento entregue duas vezes. Ela é do
     * AGREGADO de propósito: quem transforma esta exceção em "entrega duplicada, tudo bem" é o command
     * handler, que é quem conhece o contexto da mensagem.
     */
    @Test
    void completingAnAlreadyCompletePostIsRejected() {
        Post post = preCreatedPost();
        post.complete(List.of(UNTAGGED), T0, events);
        events.clear();

        assertThatThrownBy(() -> post.complete(
                List.of(Tag.reference(TagId.of("tag-2"), TagName.of("Outra"))), T1, events))
                .isInstanceOf(InvalidPostException.class);
        assertThat(events.raised()).isEmpty();
    }

    @Test
    void completingWithoutAnyTagIsRejected() {
        Post post = preCreatedPost();

        assertThatThrownBy(() -> post.complete(List.of(), T1, events))
                .isInstanceOf(InvalidPostException.class);
        assertThat(events.raised()).isEmpty();
    }

    @Test
    void createWithInvalidValueRaisesNothing() {
        assertThatThrownBy(() -> Post.create(PostId.newId(), "   ", "conteúdo", AUTHOR, List.of(), T0, events))
                .isInstanceOf(InvalidPostException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void createRejectsATitleLongerThanTheMaximum() {
        String tooLong = "x".repeat(PostTitle.MAX_LENGTH + 1);

        assertThatThrownBy(() -> Post.create(PostId.newId(), tooLong, "conteúdo", AUTHOR, List.of(), T0, events))
                .isInstanceOf(InvalidPostException.class);
    }

    @Test
    void updateRaisesTheEventWithTheResultingStateAndKeepsTheTags() {
        Post post = preCreatedPost();
        post.assignTag(UNTAGGED, T0, events);
        events.clear();

        post.update("Novo título", null, AUTHOR, T1, events);

        assertThat(events.single()).isEqualTo(new PostUpdatedEvent(
                post.id(), "Novo título", "conteúdo", UserFixtures.AUTHOR_ID,
                List.of(new PostUpdatedEvent.Tag("tag-1", "Untagged")), 3, T1));
        assertThat(post.title()).isEqualTo(PostTitle.of("Novo título"));
        assertThat(post.tags()).containsExactly(UNTAGGED);
        assertThat(post.version()).isEqualTo(new PostVersion(3)); // criado + tag + update
    }

    @Test
    void assignTagRaisesPostUpdatedWithTheTagInTheList() {
        Post post = preCreatedPost();

        Post tagged = post.assignTag(UNTAGGED, T1, events);

        assertThat(events.single()).isEqualTo(new PostUpdatedEvent(
                post.id(), "Título", "conteúdo", UserFixtures.AUTHOR_ID,
                List.of(new PostUpdatedEvent.Tag("tag-1", "Untagged")), 2, T1));
        assertThat(tagged.tags()).containsExactly(UNTAGGED);
        assertThat(tagged.version()).isEqualTo(new PostVersion(2));
        assertThat(tagged.hasTag(TagId.of("tag-1"))).isTrue();
    }

    @Test
    void assigningTheSameTagTwiceIsRejected() {
        Post post = preCreatedPost();
        post.assignTag(UNTAGGED, T0, events);
        events.clear();

        assertThatThrownBy(() -> post.assignTag(UNTAGGED, T1, events))
                .isInstanceOf(InvalidPostException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void theStateReturnedByUpdateIsTheSameAsSourcingTheRaisedEvent() {
        Post decided = preCreatedPost();
        Post sourced = preCreatedPost();

        decided.update(null, "outro conteúdo", AUTHOR, T1, events);
        sourced.on((PostUpdatedEvent) events.single());

        // decidir e reconstituir passam pelo mesmo @EventSourcingHandler: não podem divergir
        assertThat(decided.title()).isEqualTo(sourced.title());
        assertThat(decided.content()).isEqualTo(sourced.content());
        assertThat(decided.updatedAt()).isEqualTo(sourced.updatedAt());
        assertThat(decided.version()).isEqualTo(sourced.version());
        assertThat(decided.tags()).isEqualTo(sourced.tags());
    }

    @Test
    void updateWithoutChangesRaisesNothing() {
        Post post = preCreatedPost();

        assertThatThrownBy(() -> post.update("Título", "conteúdo", AUTHOR, T1, events))
                .isInstanceOf(InvalidPostException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void applyingTheSameEventTwiceLeavesTheSameState() {
        Post post = preCreatedPost();
        post.assignTag(UNTAGGED, T1, events);
        PostUpdatedEvent event = (PostUpdatedEvent) events.single();

        // é o que acontece de verdade: o domínio aplica ao decidir, e o Axon aplica ao apendar
        post.on(event);
        post.on(event);

        assertThat(post.version()).isEqualTo(new PostVersion(2));
        assertThat(post.tags()).containsExactly(UNTAGGED);
        assertThat(post.updatedAt()).isEqualTo(T1);
    }

    @Test
    void deleteRaisesPostDeletedAndMarksTheState() {
        Post post = preCreatedPost();

        Post deleted = post.delete(AUTHOR, T1, events);

        assertThat(events.single()).isEqualTo(
                new PostDeletedEvent(post.id(), UserFixtures.AUTHOR_ID, 2, T1));
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.deletedAt()).isEqualTo(T1);
        assertThat(deleted.version()).isEqualTo(new PostVersion(2));
    }

    @Test
    void deletingTwiceRaisesNothing() {
        Post post = preCreatedPost();
        post.delete(AUTHOR, T0, events);
        events.clear();

        assertThatThrownBy(() -> post.delete(AUTHOR, T1, events))
                .isInstanceOf(AlreadyDeletedException.class);

        // a guarda do mixin roda ANTES de existir evento: nada foi disparado
        assertThat(events.raised()).isEmpty();
    }

    @Test
    void restoreRaisesPostRestoredAndClearsTheState() {
        Post post = preCreatedPost();
        post.delete(AUTHOR, T0, events);
        events.clear();

        Post restored = post.restore(AUTHOR, T1, events);

        assertThat(events.single()).isEqualTo(
                new PostRestoredEvent(post.id(), UserFixtures.AUTHOR_ID, 3, T1));
        assertThat(restored.isDeleted()).isFalse();
        assertThat(restored.deletedAt()).isNull();
    }

    @Test
    void restoringSomethingAliveRaisesNothing() {
        Post post = preCreatedPost();

        assertThatThrownBy(() -> post.restore(AUTHOR, T1, events))
                .isInstanceOf(NotDeletedException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void applyingPostDeletedTwiceLeavesTheSameState() {
        Post post = preCreatedPost();
        post.delete(AUTHOR, T1, events);
        PostDeletedEvent event = (PostDeletedEvent) events.single();

        // o domínio já aplicou ao decidir; o Axon aplica de novo ao apendar
        post.on(event);
        post.on(event);

        assertThat(post.isDeleted()).isTrue();
        assertThat(post.version()).isEqualTo(new PostVersion(2));
    }

    @Test
    void anotherAuthorCannotTouchThePost() {
        Post post = preCreatedPost();
        Author intruso = Author.reference(UserId.of("outro-autor"));

        assertThatThrownBy(() -> post.update("Editado por fora", null, intruso, T1, events))
                .isInstanceOf(NotThePostAuthorException.class);
        assertThatThrownBy(() -> post.delete(intruso, T1, events))
                .isInstanceOf(NotThePostAuthorException.class);

        // a guarda roda antes de qualquer evento: nada foi disparado
        assertThat(events.raised()).isEmpty();
    }

    @Test
    void ownershipIsCheckedEvenOnADeletedPost() {
        Post post = preCreatedPost();
        post.delete(AUTHOR, T0, events);
        events.clear();

        // o agregado veio do stream, então o author está reconstituído mesmo com a linha escondida
        assertThatThrownBy(() -> post.restore(Author.reference(UserId.of("outro-autor")), T1, events))
                .isInstanceOf(NotThePostAuthorException.class);
        assertThat(events.raised()).isEmpty();
    }

    /**
     * O post como o stream o devolve: pré-criado, versão 1, sem tag. É o estado em que a saga de
     * tagueamento o encontra, e o ponto de partida da maioria dos testes daqui.
     */
    private Post preCreatedPost() {
        return new Post(new PostPreCreatedEvent(
                PostId.of("post-1"), "Título", "conteúdo", UserFixtures.AUTHOR_ID, T0));
    }
}

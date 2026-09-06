package dev.manuelantunes.axonposts.domain.post;

import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostUpdatedEvent;
import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import dev.manuelantunes.axonposts.domain.post.vo.Author;
import dev.manuelantunes.axonposts.domain.post.vo.PostContent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import dev.manuelantunes.axonposts.domain.post.vo.PostVersion;
import dev.manuelantunes.axonposts.support.RecordingDomainEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes puros de domínio: decidir e evoluir, sem nenhum framework no caminho. O único colaborador é o
 * {@link RecordingDomainEvents}, que é a porta de saída de eventos do próprio domínio.
 */
class PostTest {

    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    private final RecordingDomainEvents events = new RecordingDomainEvents();

    @Test
    void createNormalizesRaisesTheEventAndReturnsThePostReadyToSave() {
        PostId id = PostId.newId();

        Post post = Post.create(id, "  Título  ", " conteúdo ", " manuel ", T0, events);

        assertThat(events.single()).isEqualTo(new PostCreatedEvent(id, "Título", "conteúdo", "manuel", T0));
        assertThat(post.id()).isEqualTo(id);
        assertThat(post.title()).isEqualTo(PostTitle.of("Título"));
        assertThat(post.content()).isEqualTo(PostContent.of("conteúdo"));
        assertThat(post.author()).isEqualTo(Author.of("manuel"));
        assertThat(post.createdAt()).isEqualTo(T0);
        assertThat(post.updatedAt()).isEqualTo(T0);
        assertThat(post.version()).isEqualTo(PostVersion.initial());
    }

    @Test
    void createWithInvalidValueRaisesNothing() {
        assertThatThrownBy(() -> Post.create(PostId.newId(), "   ", "conteúdo", "manuel", T0, events))
                .isInstanceOf(InvalidPostException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void createRejectsATitleLongerThanTheMaximum() {
        String tooLong = "x".repeat(PostTitle.MAX_LENGTH + 1);

        assertThatThrownBy(() -> Post.create(PostId.newId(), tooLong, "conteúdo", "manuel", T0, events))
                .isInstanceOf(InvalidPostException.class);
    }

    @Test
    void updateRaisesTheEventAndReturnsTheNextStateWithoutTouchingThePrevious() {
        PostId id = PostId.newId();
        Post created = Post.createdFrom(new PostCreatedEvent(id, "Título", "conteúdo", "manuel", T0));

        Post updated = created.update("Novo título", null, T1, events);

        assertThat(events.single()).isEqualTo(new PostUpdatedEvent(id, "Novo título", "conteúdo", T1));
        assertThat(updated.title()).isEqualTo(PostTitle.of("Novo título"));
        assertThat(updated.content()).isEqualTo(PostContent.of("conteúdo"));
        assertThat(updated.author()).isEqualTo(Author.of("manuel"));
        assertThat(updated.createdAt()).isEqualTo(T0);
        assertThat(updated.updatedAt()).isEqualTo(T1);
        assertThat(updated.version()).isEqualTo(new PostVersion(2));
        assertThat(created.title()).isEqualTo(PostTitle.of("Título")); // o estado anterior não muda
        assertThat(created.version()).isEqualTo(PostVersion.initial());
    }

    @Test
    void theStateReturnedByUpdateIsTheSameAsSourcingTheRaisedEvent() {
        Post created = Post.createdFrom(new PostCreatedEvent(PostId.newId(), "Título", "conteúdo", "manuel", T0));

        Post returned = created.update(null, "outro conteúdo", T1, events);
        Post sourced = created.on((PostUpdatedEvent) events.single());

        // decidir e reconstituir passam pelo mesmo @EventSourcingHandler: não podem divergir
        assertThat(returned.title()).isEqualTo(sourced.title());
        assertThat(returned.content()).isEqualTo(sourced.content());
        assertThat(returned.author()).isEqualTo(sourced.author());
        assertThat(returned.createdAt()).isEqualTo(sourced.createdAt());
        assertThat(returned.updatedAt()).isEqualTo(sourced.updatedAt());
        assertThat(returned.version()).isEqualTo(sourced.version());
    }

    @Test
    void updateWithoutChangesRaisesNothing() {
        Post post = Post.createdFrom(new PostCreatedEvent(PostId.newId(), "Título", "conteúdo", "manuel", T0));

        assertThatThrownBy(() -> post.update("Título", "conteúdo", T1, events))
                .isInstanceOf(InvalidPostException.class);

        assertThat(events.raised()).isEmpty();
    }
}

package dev.manuelantunes.axonposts.domain.tag;

import dev.manuelantunes.axonposts.domain.tag.event.TagCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.support.RecordingDomainEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Testes puros do agregado Tag. */
class TagTest {

    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    private final RecordingDomainEvents events = new RecordingDomainEvents();

    @Test
    void createNormalizesRaisesTheEventAndReturnsTheTag() {
        TagId id = TagId.newId();

        Tag tag = Tag.create(id, "  Untagged  ", T0, events);

        assertThat(events.single()).isEqualTo(new TagCreatedEvent(id, "Untagged", T0));
        assertThat(tag.id()).isEqualTo(id);
        assertThat(tag.name()).isEqualTo(TagName.of("Untagged"));
        assertThat(tag.createdAt()).isEqualTo(T0);
    }

    @Test
    void createWithBlankNameRaisesNothing() {
        assertThatThrownBy(() -> Tag.create(TagId.newId(), "   ", T0, events))
                .isInstanceOf(InvalidTagException.class);

        assertThat(events.raised()).isEmpty();
    }

    @Test
    void namesDifferingOnlyInCaseOrPaddingAreTheSameTag() {
        assertThat(TagName.of("Untagged").sameAs(TagName.of("  untagged  "))).isTrue();
        assertThat(TagName.of("Untagged").sameAs(TagName.of("outra"))).isFalse();
    }

    /**
     * Trava o literal que a migration {@code V5__default_tag.sql} semeia contra a derivação do domínio.
     *
     * <h3>Por que existe</h3>
     * Porque SQL não deriva UUID versão 3: a linha da tag padrão é semeada com o id escrito à mão. São
     * duas fontes para o mesmo valor, e sem este teste elas divergiriam em silêncio no dia em que
     * {@link Tag#DEFAULT_NAME} mudasse — o sintoma seria {@code Tag não encontrada} no primeiro post
     * criado depois da mudança, sem nada apontando para a migration.
     */
    @Test
    void theDefaultTagIdIsDerivedFromTheName() {
        assertThat(Tag.DEFAULT_ID.value())
                .as("se este valor mudar, a V5__default_tag.sql tem de mudar com ele")
                .isEqualTo("fa65e148-3f7c-3860-a765-a70f54983048");
        assertThat(Tag.DEFAULT_NAME).isEqualTo("Untagged");
    }
}

package dev.manuelantunes.axonposts.domain.tag;

import dev.manuelantunes.axonposts.domain.tag.event.TagCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.testing.RecordingDomainEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void theDefaultTagIdIsDerivedFromTheName() {
        assertThat(Tag.DEFAULT_ID.value())
                .as("se este valor mudar, a V5__default_tag.sql tem de mudar com ele")
                .isEqualTo("fa65e148-3f7c-3860-a765-a70f54983048");
        assertThat(Tag.DEFAULT_NAME).isEqualTo("Untagged");
    }
}

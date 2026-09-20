package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;
import java.util.Set;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelMetadataTest {
    private static List<EventTag> tags(String... keyValues) {
        return java.util.stream.IntStream.range(0, keyValues.length / 2)
                .mapToObj(i -> new EventTag(keyValues[i * 2], keyValues[i * 2 + 1]))
                .toList();
    }

    @Test
    void encodesAndDecodesASingleTag() {
        String encoded = ChannelMetadata.encodeTags(tags("postId", "abc-123"));

        assertThat(encoded).isEqualTo("postId=abc-123");
        assertThat(ChannelMetadata.decodeTags(encoded)).containsExactly(new Tag("postId", "abc-123"));
    }

    @Test
    void keepsTheOrderOfSeveralTags() {
        String encoded = ChannelMetadata.encodeTags(tags("postId", "p-1", "tagId", "t-2"));

        assertThat(encoded).isEqualTo("postId=p-1;tagId=t-2");
        assertThat(ChannelMetadata.decodeTags(encoded))
                .containsExactly(new Tag("postId", "p-1"), new Tag("tagId", "t-2"));
    }

    @Test
    void survivesSeparatorsInsideTheValue() {
        List<EventTag> awkward = tags("chave=estranha", "valor;com;ponto-e-vírgula");

        Set<Tag> roundTripped = ChannelMetadata.decodeTags(ChannelMetadata.encodeTags(awkward));

        assertThat(roundTripped)
                .containsExactly(new Tag("chave=estranha", "valor;com;ponto-e-vírgula"));
    }

    @Test
    void survivesAccentsAndSpaces() {
        List<EventTag> accented = tags("título", "Saga coreografada — versão 2");

        assertThat(ChannelMetadata.decodeTags(ChannelMetadata.encodeTags(accented)))
                .containsExactly(new Tag("título", "Saga coreografada — versão 2"));
    }

    @Test
    void decodesNothingFromNullOrBlank() {
        assertThat(ChannelMetadata.decodeTags(null)).isEmpty();
        assertThat(ChannelMetadata.decodeTags("")).isEmpty();
        assertThat(ChannelMetadata.decodeTags("   ")).isEmpty();
    }

    @Test
    void ignoresAMalformedPairInsteadOfFailing() {
        assertThat(ChannelMetadata.decodeTags("semIgual;postId=p-1"))
                .containsExactly(new Tag("postId", "p-1"));
    }

    @Test
    void encodesAnEmptyListAsAnEmptyString() {
        assertThat(ChannelMetadata.encodeTags(List.of())).isEmpty();
    }

    @Test
    void convertsEnvelopeTagsToAxonTagsPreservingOrder() {
        assertThat(ChannelMetadata.tagsOf(tags("postId", "p-1", "tagId", "t-2")))
                .containsExactly(new Tag("postId", "p-1"), new Tag("tagId", "t-2"));
    }
}

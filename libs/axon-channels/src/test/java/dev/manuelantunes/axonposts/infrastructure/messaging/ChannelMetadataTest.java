package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;
import java.util.Set;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CODIFICAÇÃO DAS TAGS NO FIO — ida e volta.
 *
 * <h2>Por que isto merece teste próprio</h2>
 * As tags atravessam o broker como UMA string num cabeçalho, e é o que chega do outro lado que decide
 * em qual stream o evento é apendado. Um escape errado aqui não quebra nada na hora: ele faz o evento
 * ir para o agregado errado — ou para nenhum — no serviço vizinho, longe da causa.
 * <p>
 * O separador é {@code ;} e o par é {@code chave=valor}: as duas coisas aparecem em valor de tag mais
 * vezes do que se espera, e é por isso que cada metade é percent-encoded antes de entrar na string.
 */
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

    /**
     * O CASO QUE JUSTIFICA O ESCAPE, e ele não é hipotético: um valor com {@code ;} ou {@code =}
     * partiria a string em pares que não existem, e o outro lado apendaria no agregado errado.
     */
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

    /**
     * Um par SEM {@code =} é descartado em silêncio, e o teste existe para que isso seja uma decisão
     * e não um acidente: uma mensagem de uma versão anterior do formato não deve derrubar a ingestão
     * inteira — ela chega sem aquela tag, e o append falha com uma mensagem sobre o agregado, que é
     * onde se quer olhar.
     */
    @Test
    void ignoresAMalformedPairInsteadOfFailing() {
        assertThat(ChannelMetadata.decodeTags("semIgual;postId=p-1"))
                .containsExactly(new Tag("postId", "p-1"));
    }

    @Test
    void encodesAnEmptyListAsAnEmptyString() {
        assertThat(ChannelMetadata.encodeTags(List.of())).isEmpty();
    }

    /** {@code tagsOf} é a conversão sem fio no meio — usada quando o evento é local. */
    @Test
    void convertsEnvelopeTagsToAxonTagsPreservingOrder() {
        assertThat(ChannelMetadata.tagsOf(tags("postId", "p-1", "tagId", "t-2")))
                .containsExactly(new Tag("postId", "p-1"), new Tag("tagId", "t-2"));
    }
}

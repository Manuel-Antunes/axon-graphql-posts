package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O ENDEREÇO de um evento — lido do evento UMA vez, e a base da routing key.
 *
 * <h2>Por que aqui tem Mockito e no domínio não</h2>
 * {@code EventMessage} é um tipo de BIBLIOTECA, com um punhado de métodos que este código não usa.
 * Implementá-lo à mão só para devolver um {@code type()} e um {@code identifier()} seria escrever
 * trinta linhas que ninguém lê para afirmar duas. O domínio é o oposto: lá o colaborador é uma porta
 * de três métodos que o próprio projeto declara, e um duplo escrito à mão diz mais que um mock.
 *
 * <h2>O que se afirma</h2>
 * A chave de ordenação. Ela é o terceiro segmento da routing key, e é ela que garante que dois eventos
 * do MESMO agregado cheguem em ordem — em SQS FIFO ela vira o {@code MessageGroupId}, e uma chave
 * errada não deixa a saga mais lenta: ela quebra o append seguinte com violação de chave única.
 */
@ExtendWith(MockitoExtension.class)
class EventAddressTest {

    private static EventMessage eventOf(String messageType, String identifier) {
        EventMessage event = mock(EventMessage.class);
        when(event.type()).thenReturn(MessageType.fromString(messageType));
        when(event.identifier()).thenReturn(identifier);
        return event;
    }

    @Test
    void readsNamespaceQualifiedNameAndIdentifierFromTheEvent() {
        EventAddress address = EventAddress.of(
                eventOf("posts.PostCreated#2.0.0", "msg-1"),
                List.of(new EventTag("postId", "p-1")));

        assertThat(address.namespace()).isEqualTo("posts");
        assertThat(address.qualifiedName()).isEqualTo("posts.PostCreated");
        assertThat(address.identifier()).isEqualTo("msg-1");
        assertThat(address.messageType()).isEqualTo("posts.PostCreated#2.0.0");
    }

    /** A chave de ordenação é o VALOR da tag do agregado — é o que agrupa o stream de um post. */
    @Test
    void theOrderingKeyIsTheAggregateTagValue() {
        EventAddress address = EventAddress.of(
                eventOf("posts.PostCreated#2.0.0", "msg-1"),
                List.of(new EventTag("postId", "p-42")));

        assertThat(address.orderingKey()).isEqualTo("p-42");
    }

    /**
     * Evento SEM tag ainda tem de ter endereço.
     * <p>
     * Ele existe: um evento que não pertence a agregado nenhum continua podendo sair. O que não pode é
     * a routing key ficar com um segmento vazio, porque aí ela deixa de casar com o binding e a
     * mensagem é descartada pelo exchange sem uma linha no log.
     */
    @Test
    void anEventWithoutTagsStillGetsAnOrderingKey() {
        EventAddress address = EventAddress.of(eventOf("audit.Something#1.0.0", "msg-2"), List.of());

        assertThat(address.orderingKey()).isEqualTo(EventAddress.NO_AGGREGATE);
        assertThat(address.orderingKey()).isNotBlank();
    }

    /**
     * DUAS TAGS: escolhe a primeira e AVISA — em vez de escolher em silêncio.
     * <p>
     * O {@code AggregateBasedJpaEventStorageEngine} aceita UMA tag por evento, então duas aqui
     * significam que o {@code TagResolver} está desalinhado com o store. A versão anterior deste código
     * ordenava alfabeticamente e escolhia, o que dava uma chave estável e ERRADA. Avisar é o que
     * transforma um defeito de configuração numa linha de log em vez de numa saga que ordena mal.
     */
    @Test
    void withTwoTagsItTakesTheFirstOne() {
        EventAddress address = EventAddress.of(
                eventOf("posts.PostCreated#2.0.0", "msg-3"),
                List.of(new EventTag("postId", "p-1"), new EventTag("tagId", "t-2")));

        assertThat(address.orderingKey()).isEqualTo("p-1");
        assertThat(address.tags()).hasSize(2);
    }

    /** As tags atravessam o endereço intactas: quem as grava no store é quem as recebe. */
    @Test
    void carriesTheTagsThrough() {
        List<EventTag> tags = List.of(new EventTag("postId", "p-1"));

        assertThat(EventAddress.of(eventOf("posts.PostCreated#2.0.0", "msg-4"), tags).tags())
                .isEqualTo(tags);
    }
}

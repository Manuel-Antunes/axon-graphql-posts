package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A ROUTING KEY, que é o contrato de fio entre os dois serviços.
 *
 * <h2>Por que ela merece um teste e não só a suíte ponta a ponta</h2>
 * Porque quando ela sai errada, NADA falha: o exchange não encontra binding que case e descarta a
 * mensagem em silêncio. A saga simplesmente não fecha, e o log dos dois lados fica limpo. É o modo de
 * falhar mais caro que este sistema tem, e é o único ponto onde o formato é decidido.
 * <p>
 * O formato é {@code namespace.Nome.chaveDoAgregado} — os dois primeiros segmentos vêm do
 * {@code @Event} da classe, o terceiro da tag. Os bindings do outro lado são
 * {@code posts.PostPreCreated.*} e companhia: mexer em qualquer um dos três segmentos aqui exige
 * mexer lá.
 */
class RabbitMqAddressingTest {

    private final RabbitMqAddressing addressing = new RabbitMqAddressing();

    private static EventAddress address(String qualifiedName, String orderingKey) {
        return new EventAddress(qualifiedName + "#1.0.0", qualifiedName, "posts", "msg-1",
                orderingKey, List.of(new EventTag("postId", orderingKey)));
    }

    private static OutgoingRabbitMQMetadata metadataOf(Metadata metadata) {
        return metadata.get(OutgoingRabbitMQMetadata.class).orElseThrow();
    }

    /** Este é o conector que o `mp.messaging.outgoing.<canal>.connector` nomeia. */
    @Test
    void itAnswersForTheRabbitMqConnector() {
        assertThat(addressing.connector()).isEqualTo("smallrye-rabbitmq");
    }

    @Test
    void theRoutingKeyIsQualifiedNameThenAggregateKey() {
        Metadata metadata = addressing.addressing(address("posts.PostPreCreated", "p-42"));

        assertThat(metadataOf(metadata).getRoutingKey()).isEqualTo("posts.PostPreCreated.p-42");
    }

    /**
     * O binding do outro lado é {@code posts.PostPreCreated.*}, e o {@code *} do AMQP casa UM
     * segmento. Esta asserção é o que amarra as duas pontas: três segmentos, nem mais nem menos.
     */
    @Test
    void theRoutingKeyHasExactlyThreeSegmentsSoTheBindingMatches() {
        String routingKey = metadataOf(addressing.addressing(address("posts.PostCreated", "p-1")))
                .getRoutingKey();

        assertThat(routingKey.split("\\.")).hasSize(3);
    }

    /**
     * Um evento sem agregado também sai com três segmentos — o terceiro é {@code none}.
     * <p>
     * Sem isso a chave teria dois segmentos, o binding não casaria e a mensagem seria descartada pelo
     * exchange sem log nenhum.
     */
    @Test
    void anEventWithoutAnAggregateStillGetsThreeSegments() {
        String routingKey = metadataOf(addressing.addressing(
                new EventAddress("audit.Something#1.0.0", "audit.Something", "audit", "msg-2",
                        EventAddress.NO_AGGREGATE, List.of()))).getRoutingKey();

        assertThat(routingKey).isEqualTo("audit.Something.none");
        assertThat(routingKey.split("\\.")).hasSize(3);
    }

    /**
     * Os cabeçalhos são para QUEM DEPURA, e é por isso que eles existem: com a mensagem parada numa
     * fila, é por eles que se descobre qual evento é e de qual despacho ele veio, sem desserializar
     * o payload.
     */
    @Test
    void carriesTheMessageTypeAndIdAsHeaders() {
        Metadata metadata = addressing.addressing(address("posts.PostCreated", "p-1"));

        assertThat(metadataOf(metadata).getHeaders())
                .containsEntry("axon-message-type", "posts.PostCreated#1.0.0")
                .containsEntry("axon-message-id", "msg-1");
    }

    /**
     * O conector IN-MEMORY não endereça nada, e a ausência é a decisão.
     * <p>
     * Ele existe para o caminho do Lambda e para os testes, onde não há exchange nem routing key — o
     * que o substitui é o nome do canal. Mas ele TEM de existir: conector sem `ChannelAddressing`
     * derruba a resolução da tabela de saída, que é o que impede uma mensagem de sair sem endereço.
     */
    @Test
    void theInMemoryAddressingAnswersForItsConnectorAndAddressesNothing() {
        InMemoryAddressing inMemory = new InMemoryAddressing();

        assertThat(inMemory.connector()).isEqualTo("smallrye-in-memory");
        assertThat(inMemory.addressing(address("posts.PostCreated", "p-1")))
                .isEqualTo(Metadata.empty());
    }
}

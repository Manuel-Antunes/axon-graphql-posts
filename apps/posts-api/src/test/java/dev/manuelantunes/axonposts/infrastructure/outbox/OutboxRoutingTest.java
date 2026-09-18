package dev.manuelantunes.axonposts.infrastructure.outbox;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope;
import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;
import dev.manuelantunes.axonposts.infrastructure.messaging.OutboxRouting;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O guarda da SAÍDA, e o irmão de {@code AxonWiringTest}: três coisas que a fiação decide e que, erradas,
 * produzem exatamente o mesmo sintoma do lado de fora — a saga não fecha, sem nada no log.
 *
 * <h2>Por que um teste, se a resolução já falha alto</h2>
 * Porque {@code OutboxRouting} só levanta a mão para fiação <b>quebrada</b> (produtor sem
 * {@code @Channel}, canal que o SmallRye não ligou, conector sem endereçamento, dois outboxes no mesmo
 * canal). Ela não tem opinião sobre fiação <b>mudada</b>: acrescentar {@code "users"} ao
 * {@code @AxonOutbox} de um outbox, ou escrever {@code "post"} no lugar de {@code "posts"}, são um
 * produtor que sobe, compila e passa em todo o resto da suíte.
 * <p>
 * O terceiro caso é o que este teste existe sobretudo para travar: a chave de ordenação deixou de vir de
 * uma propriedade ({@code axonposts.messaging.ordering-tag-keys}) e passa a vir da tag do evento. É uma
 * regra em código, e código sem teste é regra que alguém "simplifica".
 */
@QuarkusTest
class OutboxRoutingTest {

    @Inject
    OutboxRouting routing;

    private static EventAddress address(String qualifiedName, String tagKey, String tagValue) {
        String namespace = qualifiedName.substring(0, qualifiedName.indexOf('.'));
        return new EventAddress(qualifiedName + "#1.0.0", qualifiedName, namespace, "evt-1", tagValue,
                List.of(new AxonEventEnvelope.EventTag(tagKey, tagValue)));
    }

    @Test
    void everyPostEventLeavesByThePostOutbox() {
        assertThat(routing.routesFor(address("posts.PostPreCreated", "postId", "p-1")))
                .as("o namespace posts, declarado no @AxonOutbox de PostEventsOutbox")
                .singleElement()
                .extracting(OutboxRouting.Route::channel)
                .isEqualTo("post-events-out");
    }

    /**
     * <b>O que NÃO sai também é o desenho.</b> Esta aplicação publica {@code users.*} e {@code tags.*} no
     * event store, e nenhuma fila jamais os vinculou: antes eles iam ao exchange para serem descartados
     * em silêncio. Acrescentar {@code "users"} ao {@code @AxonOutbox} é uma decisão legítima — mas é uma
     * decisão, e este teste é o que a obriga a ser tomada em vez de acontecer.
     */
    @Test
    void whatNobodyBoundDoesNotReachTheBroker() {
        assertThat(routing.routesFor(address("users.UserRegistered", "userId", "u-1")))
                .as("nenhum @AxonOutbox deste serviço declara o namespace users")
                .isEmpty();
    }

    /**
     * A routing key tem três segmentos, e o terceiro é a TAG DO EVENTO — não uma preferência configurada.
     * Sem o terceiro a binding {@code posts.PostCreated.*} deixa de casar, o exchange descarta a
     * mensagem, e nada no log diz que isso aconteceu. Medido, na topologia anterior: 274 eventos
     * publicados, 0 recebidos.
     */
    @Test
    void theRoutingKeyEndsInTheAggregateTagOfTheEvent() {
        OutboxRouting.Route route =
                routing.routesFor(address("posts.PostCreated", "postId", "p-42")).get(0);

        assertThat(route.addressing().addressing(address("posts.PostCreated", "postId", "p-42"))
                .get(OutgoingRabbitMQMetadata.class))
                .get()
                .extracting(OutgoingRabbitMQMetadata::getRoutingKey)
                .isEqualTo("posts.PostCreated.p-42");
    }
}

package dev.manuelantunes.axonposts.infrastructure.messaging.aws;

import java.util.List;
import java.util.Map;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OS ATRIBUTOS DE MENSAGEM NA AWS — o que substitui a routing key quando o transporte é SNS/SQS.
 *
 * <h2>Por que este arquivo existe</h2>
 * Porque no SNS não há routing key: há atributos, e a <b>filter policy</b> de cada subscription decide
 * quem recebe o quê a partir deles. Um nome de atributo trocado não derruba nada — a mensagem é
 * publicada, o {@code NumberOfMessagesPublished} sobe, e simplesmente nenhuma fila recebe. É o mesmo
 * modo de falhar da routing key errada no RabbitMQ, com o agravante de que as métricas do SNS mostram
 * sucesso.
 *
 * <h2>E o {@code traceparent}</h2>
 * Ele entra por aqui porque este é o ÚNICO lugar por onde passam os atributos das duas saídas (SNS e
 * SQS), e porque o conector de SNS do SmallRye não tem pacote de tracing — o de SQS tem. Sem esta
 * injeção o serviço do outro lado abre um trace NOVO, e a saga aparece como dois traces sem relação.
 */
class AwsEventAttributesTest {

    private static EventAddress address(String namespace, String qualifiedName, String orderingKey) {
        return new EventAddress(qualifiedName + "#1.0.0", qualifiedName, namespace, "msg-1",
                orderingKey, List.of(new EventTag("postId", orderingKey)));
    }

    @Test
    void carriesTheFiveAttributesAFilterPolicyCanMatchOn() {
        Map<String, String> attributes = AwsEventAttributes.of(
                address("posts", "posts.PostPreCreated", "p-42"));

        assertThat(attributes)
                .containsEntry(AwsEventAttributes.MESSAGE_NAME, "PostPreCreated")
                .containsEntry(AwsEventAttributes.ROUTING_KEY, "posts.PostPreCreated.p-42")
                .containsEntry(AwsEventAttributes.MESSAGE_TYPE, "posts.PostPreCreated#1.0.0")
                .containsEntry(AwsEventAttributes.MESSAGE_ID, "msg-1")
                .containsEntry(AwsEventAttributes.NAMESPACE, "posts");
    }

    /**
     * O NOME LOCAL é o que a filter policy do SNS casa, e ele é o nome SEM o namespace.
     * <p>
     * É o que permite uma subscription dizer "só PostPreCreated" sem repetir o namespace — que já é
     * atributo próprio, e que uma segunda subscription pode querer casar sozinho.
     */
    @Test
    void theLocalNameDropsTheNamespacePrefix() {
        assertThat(AwsEventAttributes.localName(address("posts", "posts.PostCreated", "p-1")))
                .isEqualTo("PostCreated");
    }

    /**
     * Um nome qualificado que NÃO começa pelo namespace sai inteiro, em vez de ser recortado errado.
     * <p>
     * É a guarda contra o recorte cego: {@code substring(namespace.length() + 1)} numa string que não
     * tem aquele prefixo devolveria lixo — ou estouraria —, e o atributo iria para o fio assim.
     */
    @Test
    void aQualifiedNameWithoutThePrefixIsKeptWhole() {
        assertThat(AwsEventAttributes.localName(address("posts", "outro.PostCreated", "p-1")))
                .isEqualTo("outro.PostCreated");
    }

    /** A routing key continua existindo como ATRIBUTO: é o que dá uma filter policy equivalente. */
    @Test
    void theRoutingKeyKeepsTheSameThreeSegmentShapeAsOnRabbitMq() {
        String routingKey = AwsEventAttributes.routingKey(address("posts", "posts.PostCreated", "p-1"));

        assertThat(routingKey).isEqualTo("posts.PostCreated.p-1");
        assertThat(routingKey.split("\\.")).hasSize(3);
    }

    @Test
    void anEventWithoutAnAggregateStillGetsAThirdSegment() {
        assertThat(AwsEventAttributes.routingKey(
                new EventAddress("audit.Something#1.0.0", "audit.Something", "audit", "msg-2",
                        EventAddress.NO_AGGREGATE, List.of())))
                .isEqualTo("audit.Something.none");
    }

    /**
     * SEM CONTEXTO ATIVO NÃO HÁ {@code traceparent} — e a ausência é correta, não um defeito.
     * <p>
     * O propagador do W3C só escreve quando há um span válido para propagar. Injetar um
     * {@code traceparent} inválido seria pior que não injetar: o outro lado o tomaria como pai e o
     * trace inteiro ficaria pendurado num id que não existe.
     * <p>
     * O caminho COM contexto é o que a stack mede — o `CLAUDE.md` traz o publish real com o
     * {@code traceparent} no mapa. Aqui o que se trava é que a ausência não quebra os outros cinco.
     */
    @Test
    void withoutAnActiveSpanTheAttributesAreStillComplete() {
        Map<String, String> attributes = AwsEventAttributes.of(
                address("posts", "posts.PostCreated", "p-1"));

        assertThat(attributes).hasSizeGreaterThanOrEqualTo(5);
        assertThat(attributes.get(AwsEventAttributes.MESSAGE_NAME)).isEqualTo("PostCreated");
    }
}

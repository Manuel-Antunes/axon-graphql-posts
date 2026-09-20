package dev.manuelantunes.axonposts.infrastructure.messaging.aws;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelAddressing;
import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;
import io.smallrye.reactive.messaging.aws.sns.SnsConnector;
import io.smallrye.reactive.messaging.aws.sns.SnsOutboundMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * O endereçamento do <b>SNS FIFO</b>: o exchange topic do RabbitMQ, traduzido.
 *
 * <h2>A tradução, peça por peça</h2>
 * <table>
 *   <tr><th>RabbitMQ</th><th>AWS</th></tr>
 *   <tr><td>exchange {@code axonposts.events}, tipo topic</td><td>um topic SNS FIFO</td></tr>
 *   <tr><td>a routing key do evento</td><td>o atributo {@link AwsEventAttributes#MESSAGE_NAME}</td></tr>
 *   <tr><td>a binding de cada fila</td><td>a <i>filter policy</i> de cada subscription</td></tr>
 *   <tr><td>a fila</td><td>a fila SQS FIFO assinante</td></tr>
 * </table>
 * Nenhuma das quatro linhas é código: as três últimas são provisionamento. O que é código é só o que
 * vai <b>na</b> mensagem, e é o que esta classe monta.
 *
 * <h2>Por que o {@code groupId} é a chave de ordenação — e por que isso não é detalhe</h2>
 * Numa fila FIFO o {@code MessageGroupId} é a unidade de ordem: mensagens do mesmo grupo chegam na
 * ordem em que foram publicadas, grupos diferentes correm em paralelo. E o que este sistema precisa
 * ordenado é exatamente <b>um agregado</b>: {@code apps/tagging} escreve no stream do {@code Post}, e
 * num event store em <i>aggregate mode</i> a posição de um append vem de ter lido o stream antes. Um
 * {@code PostUpdated} que ultrapasse o {@code PostPreCreated} do mesmo post faz o append seguinte cair
 * em
 * {@code duplicate key value violates unique constraint "uk_aggregateevententry_aggregate"}.
 * <p>
 * O valor certo já existia e já estava calculado: {@link EventAddress#orderingKey()} é a tag do evento,
 * ou seja o id do agregado. Era o terceiro segmento da routing key, onde não ordenava nada — no
 * RabbitMQ a ordem vinha do canal ser um só. Aqui ele passa a ser o que faz a ordem existir.
 * <p>
 * Evento sem tag vale {@code EventAddress.NO_AGGREGATE} ({@code "none"}): todos caem num grupo só e
 * são serializados entre si. É conservador de propósito — sem fronteira de agregado declarada, não há
 * como afirmar que dois eventos podem correr em paralelo.
 *
 * <h2>E o {@code deduplicationId}</h2>
 * O id do evento. Numa fila FIFO ele dá uma janela de cinco minutos de deduplicação <b>de graça</b>,
 * do lado do broker. Não substitui o {@code axon_message_inbox} — aquele é durável e cobre reentrega
 * depois de qualquer intervalo —, mas corta o caso comum antes de ele custar uma invocação de Lambda e
 * uma transação.
 */
@ApplicationScoped
public class SnsAddressing implements ChannelAddressing {

    private static final Logger log = LoggerFactory.getLogger(SnsAddressing.class);

    private static final String STRING = "String";

    /**
     * {@code "smallrye-sns"}, lido da constante do próprio conector em vez de escrito à mão: é o valor
     * com que {@code OutboxRouting} casa {@code mp.messaging.outgoing.<canal>.connector}, e um literal
     * errado aqui falharia na partida com "nenhum ChannelAddressing atende o conector".
     */
    @Override
    public String connector() {
        return SnsConnector.CONNECTOR_NAME;
    }

    @Override
    public Metadata addressing(EventAddress address) {
        Map<String, String> attributes = AwsEventAttributes.of(address);
        log.debug("sns → grupo={} dedup={} atributos={}",
                address.orderingKey(), address.identifier(), attributes);
        return Metadata.of(SnsOutboundMetadata.builder()
                .groupId(address.orderingKey())
                .deduplicationId(address.identifier())
                .messageAttributes(asAttributeValues(attributes))
                .build());
    }

    private static Map<String, MessageAttributeValue> asAttributeValues(Map<String, String> attributes) {
        Map<String, MessageAttributeValue> values = new LinkedHashMap<>();
        attributes.forEach((key, value) -> values.put(key,
                MessageAttributeValue.builder().dataType(STRING).stringValue(value).build()));
        return values;
    }
}

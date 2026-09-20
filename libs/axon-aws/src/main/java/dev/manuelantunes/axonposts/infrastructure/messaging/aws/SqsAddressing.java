package dev.manuelantunes.axonposts.infrastructure.messaging.aws;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelAddressing;
import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;
import io.smallrye.reactive.messaging.aws.sqs.SqsConnector;
import io.smallrye.reactive.messaging.aws.sqs.SqsOutboundMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * O endereçamento do <b>SQS FIFO direto</b>: uma fila é um destino, sem topic no meio.
 *
 * <h2>Quando usar este, e quando usar o {@link SnsAddressing}</h2>
 * A diferença não é de protocolo, é de <b>quem decide o destino</b>:
 * <ul>
 *   <li><b>SNS</b> — o produtor publica UM fato e o broker decide quem recebe, por filter policy.
 *       Destino novo é uma subscription nova, e o produtor não muda. É a tradução do exchange topic, e
 *       é o que este sistema usa hoje, porque é o que mantém a saga coreografada: nenhum dos dois
 *       serviços nomeia o outro.</li>
 *   <li><b>SQS</b> — o produtor escreve na fila do destinatário. Um salto a menos, latência e custo
 *       menores, e nenhuma infraestrutura de tópico para provisionar. O preço é que o destino passa a
 *       ser conhecido do lado de quem publica: acrescentar um consumidor vira mudança no produtor.</li>
 * </ul>
 * As duas convivem no mesmo processo, e escolher é uma linha de {@code .properties} — porque o conector
 * é atributo do CANAL, não da aplicação. É exatamente a propriedade que o
 * {@code ChannelAddressing.connector()} passou a existir para dar, e a razão de ele não ser mais um
 * bean único por processo.
 *
 * <h2>Os dois campos, pelas mesmas razões do SNS</h2>
 * {@code MessageGroupId} = a tag do agregado, porque é o que ordena o stream de um {@code Post} e é do
 * que o append em <i>aggregate mode</i> depende. {@code MessageDeduplicationId} = o id do evento, que
 * dá cinco minutos de deduplicação no broker antes de o {@code axon_message_inbox} precisar opinar.
 * Ver {@link SnsAddressing} para o detalhe medido.
 *
 * <h2>Os atributos numa fila direta</h2>
 * Não há filter policy para servi-los, mas eles continuam valendo: {@code axon-message-name} e
 * {@code axon-message-id} aparecem no console, no log de uma DLQ e num filtro de busca sem que ninguém
 * precise desserializar o corpo — que é onde se perde tempo às três da manhã.
 */
@ApplicationScoped
public class SqsAddressing implements ChannelAddressing {

    private static final Logger log = LoggerFactory.getLogger(SqsAddressing.class);

    private static final String STRING = "String";

    @Override
    public String connector() {
        return SqsConnector.CONNECTOR_NAME;
    }

    @Override
    public Metadata addressing(EventAddress address) {
        Map<String, String> attributes = AwsEventAttributes.of(address);
        log.debug("sqs → grupo={} dedup={} atributos={}",
                address.orderingKey(), address.identifier(), attributes);
        return Metadata.of(SqsOutboundMetadata.builder()
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

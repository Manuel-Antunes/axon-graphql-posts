package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Para onde este evento vai, lido do próprio evento.</b> Nenhum campo daqui vem de configuração.
 *
 * <h2>Por que existe um valor no meio</h2>
 * Porque "que evento é este" e "como este broker endereça" são perguntas diferentes, e antes elas
 * estavam na mesma linha: o {@link RabbitMqAddressing} lia o {@code MessageType}, escolhia a tag de
 * ordenação <i>e</i> montava a routing key. Com as três juntas, um outbox de Kafka teria de repetir as
 * duas primeiras decisões para mudar só a terceira — e decisão repetida é como duas implementações
 * passam a discordar.
 * <p>
 * Aqui o evento é lido <b>uma vez</b>. Quem roteia ({@link OutboxRouting}) compara o
 * {@link #qualifiedName()} com o seletor do canal; quem endereça ({@link ChannelAddressing}) recebe
 * isto pronto e só traduz para o metadado do broker dele.
 *
 * @param messageType   o {@code MessageType} na forma de fio ({@code namespace.Name#versão}), como o
 *                      envelope o carrega. Vai em header de saída para quem depura no broker.
 * @param qualifiedName {@code namespace.Name} — o que o {@code @Event} declara. Sem o namespace no
 *                      valor, uma binding {@code posts.*} não casa e o exchange descarta a mensagem sem
 *                      uma linha no log.
 * @param namespace     o {@code namespace} do {@code @Event}, sozinho: é por ele que
 *                      {@link OutboxRouting} decide qual outbox leva este evento.
 * @param identifier    o id do evento, para o mesmo header de depuração.
 * @param orderingKey   a identidade da instância — ver {@link #orderingKeyOf}.
 * @param tags          as {@code Tag} do evento, como o {@code TagResolver} do Axon as resolveu.
 */
public record EventAddress(
        String messageType,
        String qualifiedName,
        String namespace,
        String identifier,
        String orderingKey,
        List<AxonEventEnvelope.EventTag> tags) {

    private static final Logger log = LoggerFactory.getLogger(EventAddress.class);

    /**
     * O que vira chave de ordenação num evento sem tag nenhuma. Não deveria acontecer — evento sem tag
     * num store em aggregate mode nunca é lido de volta —, e o sentinela existe para a chave continuar
     * tendo o mesmo número de segmentos, porque é dele que as bindings dependem.
     */
    public static final String NO_AGGREGATE = "none";

    public static EventAddress of(EventMessage event, List<AxonEventEnvelope.EventTag> tags) {
        MessageType type = event.type();
        return new EventAddress(
                type.toString(),
                type.qualifiedName().fullName(),
                type.qualifiedName().namespace(),
                event.identifier(),
                orderingKeyOf(type, tags),
                tags);
    }

    /**
     * <b>A tag do evento — e não uma lista de preferências escrita à mão.</b>
     *
     * <h3>O que isto substituiu, e por que a substituição é honesta</h3>
     * Havia uma propriedade, {@code axonposts.messaging.ordering-tag-keys}, com a ordem em que as tags
     * seriam preferidas ({@code postId,tagAssignment,tagId,userId}). Ela era configuração repetindo um
     * fato do domínio — e configuração que repete um fato do domínio é configuração que um dia diverge
     * dele, em silêncio.
     * <p>
     * Ela também não decidia nada: o {@code AggregateBasedJpaEventStorageEngine}, único event storage
     * engine relacional do Axon 5.3.1, aceita <b>uma tag por evento</b>. Todo evento deste projeto tem
     * exatamente uma, e é ela o {@code aggregateIdentifier} do stream. Não havia empate a desempatar;
     * havia uma lista pronta para um empate que o store não deixa existir — e os dois
     * {@code application.properties} já admitiam isso por escrito.
     * <p>
     * Se um dia houver (num store com DCB), o {@code WARN} abaixo torna a escolha visível em vez de
     * alfabética e calada — as tags chegam ordenadas de {@link ChannelEventForwarder}, então ela é
     * estável, só não é <i>informada</i>. É aí que uma regra nova entra, e ela terá de vir do modelo de
     * entidades do Axon (o {@code tagKey} de {@code @EventSourcedEntity}), não de uma propriedade.
     */
    private static String orderingKeyOf(MessageType type, List<AxonEventEnvelope.EventTag> tags) {
        if (tags.isEmpty()) {
            return NO_AGGREGATE;
        }
        if (tags.size() > 1) {
            log.warn("{} tem {} tags {} — a chave de ordenação será a de '{}', a primeira em ordem. "
                    + "Um store em aggregate mode não grava duas tags: confira o TagResolver.",
                    type, tags.size(), tags, tags.get(0).key());
        }
        return tags.get(0).value();
    }
}

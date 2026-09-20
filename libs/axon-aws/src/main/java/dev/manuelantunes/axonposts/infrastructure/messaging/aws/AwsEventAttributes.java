package dev.manuelantunes.axonposts.infrastructure.messaging.aws;

import java.util.LinkedHashMap;
import java.util.Map;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;

import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;

/**
 * Os atributos de mensagem que acompanham todo evento publicado na AWS — <b>um cálculo só</b>, usado
 * pelo endereçamento do SNS e pelo do SQS.
 *
 * <h2>Por que isto existe, se o RabbitMQ resolvia com uma routing key</h2>
 * Porque no RabbitMQ o endereço <b>é</b> o seletor: a routing key {@code posts.PostCreated.<postId>}
 * chega ao exchange e as bindings decidem quem recebe. SNS e SQS não têm routing key — o corpo é opaco
 * e quem seleciona é a <i>filter policy</i>, que só enxerga <b>atributos de mensagem</b>. Então o que
 * era um campo do protocolo passa a ser carga explícita, e é aqui que ela é montada.
 *
 * <h2>Por que cinco atributos, e não um</h2>
 * Porque cada um responde a uma pergunta diferente, e escrever os cinco custa o mesmo que escrever um:
 * <ul>
 *   <li>{@link #MESSAGE_NAME} — {@code PostCreated}. É por ele que as filter policies filtram, porque
 *       é o que a binding do RabbitMQ filtrava: o <b>nome do evento</b>, sem namespace e sem agregado.
 *       Igualdade exata, que é o que uma filter policy faz melhor;</li>
 *   <li>{@link #ROUTING_KEY} — {@code posts.PostCreated.<postId>}, o MESMO string que a
 *       {@code RabbitMqAddressing} produz. Existe para a topologia da AWS poder ser lida como a
 *       tradução da que já existe, e para uma policy por {@code prefix} continuar exprimível;</li>
 *   <li>{@link #MESSAGE_TYPE} — o {@code MessageType} completo, <b>com a versão</b> do {@code @Event}.
 *       É o que o consumidor usa para resolver o tipo, e o que um dia separará v1 de v2;</li>
 *   <li>{@link #MESSAGE_ID} — o id do evento, visível sem abrir o corpo (log, DLQ, console);</li>
 *   <li>{@link #NAMESPACE} — {@code posts}. A granularidade em que o {@code @AxonOutbox} decide, agora
 *       legível também do lado de quem consome.</li>
 * </ul>
 *
 * <h2>O que NÃO está aqui, e é o detalhe que quebra em silêncio</h2>
 * A assinatura de origem ({@code axon-channel-origin}) continua na <b>metadata do envelope</b>, dentro
 * do corpo, e não sobe para atributo. É de propósito: é o próprio evento reconstruído que precisa
 * dela — {@code ChannelEventIngestion} a lê depois de desserializar —, e duplicá-la aqui criaria dois
 * lugares onde a mesma verdade pode divergir.
 */
public final class AwsEventAttributes {

    /** {@code posts.PostCreated} → {@code PostCreated}. O seletor das filter policies. */
    public static final String MESSAGE_NAME = "axon-message-name";

    /** {@code posts.PostCreated.<postId>} — o mesmo string da routing key do RabbitMQ. */
    public static final String ROUTING_KEY = "axon-routing-key";

    /** O {@code MessageType} serializado, com namespace, nome e versão. */
    public static final String MESSAGE_TYPE = "axon-message-type";

    /** O identificador do evento. */
    public static final String MESSAGE_ID = "axon-message-id";

    /** O namespace do {@code @Event}. */
    public static final String NAMESPACE = "axon-namespace";

    private AwsEventAttributes() {
    }

    /** Ordenado ({@code LinkedHashMap}) para o console e o log mostrarem sempre a mesma sequência. */
    public static Map<String, String> of(EventAddress address) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(MESSAGE_NAME, localName(address));
        attributes.put(ROUTING_KEY, routingKey(address));
        attributes.put(MESSAGE_TYPE, address.messageType());
        attributes.put(MESSAGE_ID, address.identifier());
        attributes.put(NAMESPACE, address.namespace());
        inject(attributes);
        return attributes;
    }

    /**
     * Põe o contexto do trace nos atributos da mensagem — e é isto que costura os dois serviços num
     * trace só.
     *
     * <h3>Por que à mão, e não pelo conector</h3>
     * Porque o conector não faz. Com o RabbitMQ a saga inteira era UM trace de graça: o
     * `tracing.enabled` do conector já vinha ligado dos dois lados. Na AWS isso regrediu, e por um
     * motivo assimétrico que este projeto já tinha medido: `smallrye-reactive-messaging-aws-sqs` traz
     * um instrumentador e injeta; `smallrye-reactive-messaging-aws-sns` 4.37.0 <b>não tem pacote de
     * tracing nenhum</b>. Como a SAÍDA deste sistema é SNS, nada era injetado.
     * <p>
     * O que se perdia não é enfeite: um trace pela metade é pior que nenhum, porque a lacuna parece
     * latência. Era exatamente o sintoma do publish aparecendo e o silêncio seguinte — que é o outro
     * serviço decidindo a tag sem nada registrar.
     *
     * <h3>Por que AQUI</h3>
     * Porque este é o único lugar por onde passam os atributos das DUAS saídas (SNS e SQS), e porque
     * `traceparent` é, para o fio, um atributo como os outros. Quem lê do outro lado é o
     * `SqsChannelIngress`.
     * <p>
     * O formato é o W3C — `traceparent`/`tracestate` —, que é o propagador default do Quarkus. Sem
     * span ativo o propagador não escreve nada, e a mensagem sai como saía.
     */
    private static void inject(Map<String, String> attributes) {
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), attributes, (carrier, key, value) -> {
                    if (carrier != null) {
                        carrier.put(key, value);
                    }
                });
    }

    /**
     * A routing key do RabbitMQ, letra por letra — {@code namespace.Name.tagDoAgregado}. Não é nostalgia:
     * é o que permite conferir as duas topologias lado a lado enquanto as duas existirem.
     */
    public static String routingKey(EventAddress address) {
        return address.qualifiedName() + "." + address.orderingKey();
    }

    /**
     * O nome do evento sem o namespace.
     *
     * <p>Derivado aqui, e não lido de {@code EventAddress}, porque aquele record não o expõe — e
     * acrescentar um componente a ele seria mexer num arquivo que esta migração não precisa tocar. O
     * corte é por prefixo e não por último ponto: um nome de evento com ponto dentro (que o
     * {@code QualifiedName} do Axon permite) sobreviveria ao primeiro e não ao segundo.
     */
    public static String localName(EventAddress address) {
        String qualified = address.qualifiedName();
        String prefix = address.namespace() + ".";
        return qualified.startsWith(prefix) ? qualified.substring(prefix.length()) : qualified;
    }
}

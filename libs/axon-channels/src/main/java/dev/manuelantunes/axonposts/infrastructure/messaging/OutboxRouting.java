package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.ChannelRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * <b>Qual outbox recebe qual evento.</b> É a tabela de roteamento da saída — e o que substituiu o canal
 * único {@code axon-events}.
 *
 * <h2>O que estava errado no desenho anterior</h2>
 * A ENTRADA sempre foi "um canal por propósito": cada fatia que o serviço ingere tem canal, fila e
 * seletor próprios, e o seletor ({@code routing-keys=posts.PostCreated.*}) está declarado no próprio
 * canal, ao lado do {@code queue.name}. A SAÍDA era o oposto: um canal só, um exchange só, e destino
 * nenhum declarado — todo evento saía por ali, e quem separava era a binding do consumidor.
 * <p>
 * Isso obriga um ponto central por onde tudo passa. Não é o que coreografia quer dizer, e cobra caro em
 * duas situações concretas: um destino em outro protocolo (Kafka ao lado de RabbitMQ) não é exprimível,
 * porque o conector é atributo do canal e só havia um canal; e não há como dar vazão, isolamento de
 * falha ou pausa a um fluxo de saída sem dar aos outros, porque fluxo de saída não era uma coisa
 * separada.
 *
 * <h2>Como um outbox se declara, e como este código o encontra</h2>
 * A aplicação escreve um produtor de {@code Emitter} anotado {@link AxonOutbox} — o que sai ao lado de
 * para onde vai, numa declaração só. Daqui, tudo vem do CDI e <b>nada de reflexão</b>: canal e
 * namespaces saem de {@link Bean#getQualifiers()}, que o ArC materializa em bytecode, e o emitter sai do
 * próprio handle — resolvido na primeira publicação, não na partida.
 *
 * <h2>Como o evento acha o canal</h2>
 * Pelo {@code namespace} que ele já declara no {@code @Event}, contra os {@link AxonOutbox#value()} de
 * cada outbox. <b>Não há propriedade nenhuma nesta decisão</b> — o porquê está no Javadoc do qualifier.
 * <p>
 * Um evento que casa com <b>vários</b> outboxes sai em todos. Publicar o mesmo fato num broker e num
 * barramento de auditoria é um requisito comum, e um roteamento que escolhesse "o melhor" canal
 * silenciaria metade dele.
 *
 * <h2>As quatro coisas que isto se recusa a fazer em silêncio</h2>
 * <ol>
 *   <li><b>{@code channel()} e {@code @Channel} discordando</b> — o arquivo copiado com um dos dois
 *       nomes trocado. O evento sairia pelo canal do {@code @Channel} enquanto o conector e o
 *       endereçamento viriam do outro: rota errada, protocolo possivelmente errado, nada no log. É a
 *       conferência de {@link #emitterOf}, e é ela que paga a duplicação do nome;</li>
 *   <li><b>outbox num canal que o SmallRye não ligou</b> (typo, bloco {@code mp.messaging.outgoing}
 *       ausente). O emitter existe, a mensagem é aceita, e o SmallRye só registra um
 *       {@code has no downstream} no meio da partida;</li>
 *   <li><b>conector sem {@link ChannelAddressing}</b>. A mensagem sairia sem routing key, e um exchange
 *       {@code topic} descarta o que não casa com binding nenhuma sem dizer nada;</li>
 *   <li><b>dois outboxes no mesmo canal</b> — o caso do arquivo copiado. Aqui nada falharia: todo
 *       evento que casasse com os dois sairia <b>duas vezes pelo mesmo emitter</b>, e quem consome veria
 *       duplicata onde o desenho promete "uma vez por aresta".</li>
 * </ol>
 * As quatro derrubam a resolução da tabela, com o nome do canal (ou do produtor) no erro.
 *
 * <h2>Por que a tabela é resolvida no primeiro encaminhamento, e não na partida</h2>
 * Pela mesma razão que o {@code EventConverter} e o {@code TagResolver} de {@link ChannelEventForwarder}:
 * este pacote é construído de dentro do {@code AxonExtension.init}, um recorder de {@code RUNTIME_INIT},
 * e nesse instante o SmallRye ainda não ligou channel nenhum — {@link ChannelRegistry#getOutgoingNames()}
 * responderia vazio e a validação acusaria todo mundo. No primeiro evento encaminhado a mensageria está
 * de pé há muito tempo.
 */
@ApplicationScoped
public class OutboxRouting {

    private static final Logger log = LoggerFactory.getLogger(OutboxRouting.class);

    private static final String CONNECTOR = "mp.messaging.outgoing.%s.connector";

    /**
     * <b>{@code Instance<Object>}, e não {@code Instance<Emitter<AxonEventEnvelope>>}</b> — que é o que
     * este ponto de injeção gostaria de dizer.
     *
     * <h3>Por quê</h3>
     * Porque o Quarkus valida em build time <b>todo</b> ponto de injeção cujo tipo requerido seja um
     * {@code Emitter}, inclusive através de um {@code Instance<>}, e exige um {@code @Channel} nele:
     * <pre>
     * DeploymentException: Invalid emitter injection - &#64;Channel is required for parameter
     * 'outboxes' of OutboxRouting constructor
     * </pre>
     * E {@code @Channel} é exatamente o que a lib não pode ter: ele nomeia UM canal, e o que ela precisa
     * é de todos. Pedindo {@code Object} o tipo requerido deixa de ser {@code Emitter}, a validação não
     * se aplica, e o qualifier continua selecionando só os outboxes. O elenco é conferido em
     * {@link #emitterOf}, que é onde um produtor anotado com o tipo errado falha com o nome dele no
     * erro.
     */
    private final Instance<Object> outboxes;
    private final Instance<ChannelAddressing> addressings;
    private final ChannelRegistry channels;
    private final Config config;

    OutboxRouting(@AxonOutbox Instance<Object> outboxes,
            Instance<ChannelAddressing> addressings, ChannelRegistry channels, Config config) {
        this.outboxes = outboxes;
        this.addressings = addressings;
        this.channels = channels;
        this.config = config;
    }

    /**
     * Um destino já resolvido: o canal, o emitter dele e como aquele broker endereça.
     */
    public record Route(String channel, Emitter<AxonEventEnvelope> emitter, ChannelAddressing addressing) {

        /**
         * O overload de {@code Emitter} que aceita {@code Message} — o único por onde passa metadado, e
         * portanto endereçamento — devolve {@code void}. Os callbacks de ack/nack são a única forma de
         * esperar o envio nesse caminho.
         */
        public CompletableFuture<Void> send(AxonEventEnvelope envelope, EventAddress address) {
            Metadata metadata = addressing.addressing(address);
            CompletableFuture<Void> sent = new CompletableFuture<>();
            emitter.send(Message.of(envelope, metadata,
                    () -> {
                        sent.complete(null);
                        return CompletableFuture.completedFuture(null);
                    },
                    failure -> {
                        sent.completeExceptionally(failure);
                        return CompletableFuture.completedFuture(null);
                    }));
            return sent;
        }
    }

    /**
     * Os namespaces vêm normalizados para minúsculas e num {@code TreeSet}: comparar sem diferenciar
     * caixa evita que um {@code "Posts"} distraído não case com nada em silêncio, e a ordem estável é o
     * que faz a linha de log da tabela ser a mesma entre partidas.
     */
    private record Entry(Set<String> namespaces, Route route) {
    }

    private volatile List<Entry> table;

    public List<Route> routesFor(EventAddress address) {
        String namespace = address.namespace().toLowerCase(Locale.ROOT);
        List<Route> matched = new ArrayList<>(1);
        for (Entry entry : table()) {
            if (entry.namespaces().contains(namespace)) {
                matched.add(entry.route());
            }
        }
        return matched;
    }

    private List<Entry> table() {
        List<Entry> resolved = table;
        if (resolved == null) {
            synchronized (this) {
                resolved = table;
                if (resolved == null) {
                    resolved = resolve();
                    table = resolved;
                }
            }
        }
        return resolved;
    }

    private List<Entry> resolve() {
        Set<String> wired = channels.getOutgoingNames();
        Set<String> taken = new HashSet<>();
        List<Entry> resolved = new ArrayList<>();

        for (Instance.Handle<Object> handle : outboxes.handles()) {
            Bean<?> declaration = handle.getBean();
            String channel = channelOf(declaration);

            if (!taken.add(channel)) {
                throw new IllegalStateException(
                        "mais de um @AxonOutbox produz o canal '" + channel + "' (o último foi declarado em "
                                + declaration.getBeanClass().getName() + "). Todo evento que casasse com "
                                + "os dois sairia DUAS vezes pelo mesmo emitter. Um canal, um outbox: "
                                + "destinos diferentes são canais diferentes.");
            }
            if (!wired.contains(channel)) {
                throw new IllegalStateException(
                        "o @AxonOutbox de " + declaration.getBeanClass().getName() + " injeta o canal '"
                                + channel + "', que o SmallRye não ligou. Canais outgoing ligados: " + wired
                                + ". Falta o bloco mp.messaging.outgoing." + channel + ".connector.");
            }
            resolved.add(new Entry(namespacesOf(declaration),
                    new Route(channel, emitterOf(handle, channel), addressingFor(channel))));
        }

        log.info("outboxes: {}", resolved.stream()
                .map(entry -> entry.route().channel() + " ← " + entry.namespaces())
                .collect(Collectors.joining(", ", "[", "]")));
        return resolved;
    }

    /**
     * O canal vem do {@link AxonOutbox#channel()}, e não do {@code @Channel} do parâmetro do produtor —
     * que seria a fonte única e não está disponível: o ArC devolve {@code Bean#getInjectionPoints()}
     * <b>vazio</b> para produtores. Medido, com o valor impresso: {@code injectionPoints=[]}. O que
     * impede os dois nomes de divergirem é {@link #emitterOf}.
     */
    private static String channelOf(Bean<?> declaration) {
        String channel = outboxOf(declaration).channel();
        if (channel.isBlank()) {
            throw new IllegalStateException(
                    "o @AxonOutbox de " + declaration.getBeanClass().getName() + " não diz channel(). "
                            + "É ele que aponta o bloco mp.messaging.outgoing do canal e o conector "
                            + "dele; sem o nome não há como endereçar coisa nenhuma.");
        }
        return channel;
    }

    private static AxonOutbox outboxOf(Bean<?> declaration) {
        for (var qualifier : declaration.getQualifiers()) {
            if (qualifier instanceof AxonOutbox outbox) {
                return outbox;
            }
        }
        throw new IllegalStateException(
                "bean sem @AxonOutbox chegou à coleta de outboxes: " + declaration.getBeanClass().getName());
    }

    /**
     * Duas conferências no mesmo lugar, porque as duas nascem de {@code Instance<Object>} e da
     * duplicação do nome do canal:
     * <ol>
     *   <li><b>é mesmo um {@code Emitter}?</b> Um {@code @AxonOutbox} num produtor de outro tipo passa
     *       pela injeção e só apareceria como {@code ClassCastException} no primeiro evento, longe da
     *       causa;</li>
     *   <li><b>é o emitter DESTE canal?</b> O {@code ChannelRegistry} tem, sob cada nome de canal, o
     *       emitter que o Quarkus criou para o {@code @Channel} correspondente. Se o que o produtor
     *       devolveu não for aquele objeto, {@code channel()} e {@code @Channel} discordam — e é este o
     *       guarda que torna a duplicação do nome segura em vez de meramente feia.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private Emitter<AxonEventEnvelope> emitterOf(Instance.Handle<Object> handle, String channel) {
        Object produced = handle.get();
        if (!(produced instanceof Emitter)) {
            throw new IllegalStateException(
                    "o @AxonOutbox de " + handle.getBean().getBeanClass().getName() + " devolve "
                            + produced.getClass().getName() + ", e não um Emitter<AxonEventEnvelope>. "
                            + "O qualifier marca o emitter de um canal; não há o que publicar noutro tipo.");
        }
        Emitter<?> registered = channels.getEmitter(channel);
        if (registered != null && registered != produced) {
            throw new IllegalStateException(
                    "o @AxonOutbox de " + handle.getBean().getBeanClass().getName() + " diz channel = '"
                            + channel + "', mas o @Channel que ele injeta é de OUTRO canal. O evento "
                            + "sairia por um canal com o conector e o endereçamento do outro. Os dois "
                            + "nomes têm de ser o mesmo — de preferência a mesma constante.");
        }
        return (Emitter<AxonEventEnvelope>) produced;
    }

    private static Set<String> namespacesOf(Bean<?> declaration) {
        Set<String> namespaces = new TreeSet<>();
        for (String namespace : outboxOf(declaration).namespaces()) {
            namespaces.add(namespace.toLowerCase(Locale.ROOT));
        }
        if (namespaces.isEmpty()) {
            throw new IllegalStateException(
                    "o @AxonOutbox de " + declaration.getBeanClass().getName() + " não declara namespace "
                            + "nenhum — ele nunca receberia evento, e um canal ligado ao broker sem nada "
                            + "a publicar não é uma configuração, é um esquecimento.");
        }
        return Set.copyOf(namespaces);
    }

    private ChannelAddressing addressingFor(String channel) {
        String connector = config.getOptionalValue(CONNECTOR.formatted(channel), String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "o canal '" + channel + "' não declara mp.messaging.outgoing." + channel
                                + ".connector"));
        return StreamSupport.stream(addressings.spliterator(), false)
                .filter(candidate -> connector.equals(candidate.connector()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "nenhum ChannelAddressing atende o conector '" + connector + "' do canal '"
                                + channel + "'. Conhecidos: " + knownConnectors()
                                + ". Sem endereçamento a mensagem sai sem routing key e o exchange a "
                                + "descarta sem uma linha no log."));
    }

    private String knownConnectors() {
        return StreamSupport.stream(addressings.spliterator(), false)
                .map(ChannelAddressing::connector)
                .collect(Collectors.joining(", "));
    }
}

package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventstreaming.Tag;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Traduz um {@code EventMessage} para {@link AxonEventEnvelope} e o entrega ao channel.
 *
 * <h2>Genérico sobre {@code EventMessage}</h2>
 * Nenhum tipo de evento é citado. Evento novo no domínio já atravessa, sem código — é a diferença
 * entre integrar o <i>framework</i> e integrar cada evento à mão.
 *
 * <h2>Serialização é a do Axon</h2>
 * O corpo sai do {@code EventConverter} do framework, o mesmo que o event store usa. Não há um segundo
 * formato para manter, e o {@code @Event(namespace, name, version)} sobrevive ao fio porque
 * {@code MessageType} tem {@code toString()}/{@code fromString()}.
 */
@ApplicationScoped
public class ChannelEventForwarder {

    private static final Logger log = LoggerFactory.getLogger(ChannelEventForwarder.class);

    private final Configuration axon;
    private final ChannelEventDispatcher dispatcher;
    private final ChannelAddressing addressing;
    /** Vai na metadata de todo evento que sai daqui, como marca de autoria. Ver {@link ChannelMetadata}. */
    private final String applicationName;

    /**
     * Recebe a {@code Configuration} do Axon, e não o {@code EventConverter} direto, por dois motivos
     * que só aparecem em runtime:
     * <ul>
     *   <li>o {@code EventConverter} <b>não é bean CDI</b> — é componente da configuração do Axon.
     *       Injetá-lo dá {@code UnsatisfiedResolutionException} na partida;</li>
     *   <li>resolvê-lo no construtor tocaria a configuração do Axon durante a inicialização da
     *       extensão, que é o mesmo poço de ordem de partida que já mordeu o Flyway e o emitter do
     *       SmallRye nesta integração. Resolvido no primeiro encaminhamento, não há ordem a respeitar.
     * </ul>
     */
    ChannelEventForwarder(Configuration axon, ChannelEventDispatcher dispatcher,
            ChannelAddressing addressing,
            @ConfigProperty(name = "quarkus.application.name") String applicationName) {
        this.axon = axon;
        this.dispatcher = dispatcher;
        this.addressing = addressing;
        this.applicationName = applicationName;
    }

    /**
     * Pool PRÓPRIO para a continuação do encaminhamento, e não o worker pool do Quarkus.
     *
     * <h3>O deadlock que isto evita</h3>
     * Quando um evento chega pela fila, a entrega roda numa thread do worker pool (via
     * {@code @Blocking}). O handler despacha um command; o after-commit do command encaminha os eventos
     * novos e <b>espera</b> pelo envio. Se essa espera depender de uma continuação agendada no
     * <b>mesmo</b> worker pool — que está ocupado com a entrega —, a tarefa nunca roda e o {@code join}
     * nunca retorna. Sem retorno não há ack, e sem ack o broker não entrega a próxima mensagem.
     * <p>
     * Medido: exatamente UMA mensagem entregue, o trabalho dela concluído com sucesso (a tag chegava a
     * ser atribuída), e a fila parada em seguida — sem erro, sem nack, sem uma linha no log. Trinta
     * segundos de silêncio e a espera do teste estourando.
     */
    private final Executor forwarding =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "axon-channel-outbox");
                thread.setDaemon(true);
                return thread;
            });

    private volatile EventConverter converter;
    private volatile TagResolver tags;

    private EventConverter converter() {
        EventConverter resolved = converter;
        if (resolved == null) {
            resolved = axon.getComponent(EventConverter.class);
            converter = resolved;
        }
        return resolved;
    }

    /**
     * O {@code TagResolver} do Axon — o mesmo que o event store usa para gravar as tags e que o DCB usa
     * para decidir fronteira de consistência. Resolver as tags aqui, e não reimplementar a leitura dos
     * {@code @EventTag}, é o que faz o roteamento usar <b>as propriedades do evento no sentido do
     * framework</b> em vez de uma convenção nossa.
     */
    private TagResolver tags() {
        TagResolver resolved = tags;
        if (resolved == null) {
            resolved = axon.getComponent(TagResolver.class);
            tags = resolved;
        }
        return resolved;
    }

    /** Ordenadas para o envelope ser estável entre publicações do mesmo evento. */
    private List<AxonEventEnvelope.EventTag> tagsOf(EventMessage event) {
        Set<Tag> resolved = tags().resolve(event);
        return resolved.stream()
                .sorted(Comparator.comparing(Tag::key).thenComparing(Tag::value))
                .map(tag -> new AxonEventEnvelope.EventTag(tag.key(), tag.value()))
                .toList();
    }

    @PreDestroy
    void shutdown() {
        if (forwarding instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }

    /**
     * Encaminha o evento — a menos que este serviço não seja o autor dele.
     *
     * <h3>A regra de uma linha que impede um laço infinito</h3>
     * Todo evento publicado localmente é encaminhado, e todo evento que chega do broker é apendado no
     * store local — o que o publica localmente. As duas regras juntas se alimentam: A publica, B apenda
     * e republica, A apenda e republica, sem fim. O evento ingerido chega com
     * {@link ChannelMetadata#ORIGIN} já preenchido (pelo serviço que o produziu), e é essa presença que
     * responde "eu não sou o autor" sem consultar nada.
     * <p>
     * A topologia de filas também protegeria hoje — nenhuma binding casa o que o próprio serviço
     * publica —, e é justamente por isso que a defesa não pode ser só ela: binding é configuração, e um
     * dia alguém acrescenta {@code posts.*} numa fila para depurar e derruba o cluster.
     */
    public CompletableFuture<Void> forward(EventMessage event) {
        String origin = event.metadata().get(ChannelMetadata.ORIGIN);
        if (origin != null) {
            log.debug("channel ← {} ({}) NÃO encaminhado: veio de '{}'",
                    event.type(), event.identifier(), origin);
            return CompletableFuture.completedFuture(null);
        }

        List<AxonEventEnvelope.EventTag> eventTags = tagsOf(event);
        Map<String, String> metadata = new LinkedHashMap<>(event.metadata());
        metadata.put(ChannelMetadata.ORIGIN, applicationName);
        AxonEventEnvelope envelope = new AxonEventEnvelope(
                event.type().toString(),
                event.identifier(),
                event.timestamp(),
                metadata,
                eventTags,
                AxonEventEnvelope.encodePayload(converter().convertPayload(event, byte[].class)));

        log.debug("channel ← {} ({})", envelope.messageType(), envelope.identifier());

        // O ack do emitter completa numa thread do EVENT-LOOP. Sem trazer a continuação de volta para
        // um worker, o que o Axon encadeia depois deste after-commit roda lá — e o
        // `AssignDefaultTagOnPostCreated`, que despacha um command @Transactional, estoura com
        // `@Transactional cannot start a JTA transaction within a reactive pipeline`. Foi medido: 35
        // testes falharam antes desta linha existir.
        return dispatcher.send(envelope, addressing.addressing(event, eventTags))
                .thenApplyAsync(ignored -> (Void) null, forwarding);
    }
}

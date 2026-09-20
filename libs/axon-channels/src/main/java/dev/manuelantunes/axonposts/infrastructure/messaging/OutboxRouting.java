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
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.ChannelRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;

@ApplicationScoped
public class OutboxRouting {
    private static final Logger log = LoggerFactory.getLogger(OutboxRouting.class);

    private static final String CONNECTOR = "mp.messaging.outgoing.%s.connector";

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

    public record Route(String channel, Emitter<AxonEventEnvelope> emitter, ChannelAddressing addressing) {
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

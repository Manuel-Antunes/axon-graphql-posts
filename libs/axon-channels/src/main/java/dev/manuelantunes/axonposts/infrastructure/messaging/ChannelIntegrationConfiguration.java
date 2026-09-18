package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.stream.Stream;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.meks.quarkiverse.axon.runtime.customizations.AxonEventProcessingConfigurer;
import at.meks.quarkiverse.axon.runtime.defaults.eventprocessors.EventhandlersPerNamespace;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Registra o {@link ChannelTagResolver} na configuração do Axon.
 *
 * <h2>Por que pelo gancho de EVENT PROCESSING, que não tem nada a ver com tags</h2>
 * Porque é o único gancho aditivo que a extensão oferece com acesso ao {@code EventSourcingConfigurer}.
 * O caminho natural seria o {@code EventstoreConfigurer}, e ele <b>não serve</b>: a extensão o injeta
 * como bean único, com o {@code InMemoryEventStoreConfigurer} marcado {@code @DefaultBean}. O
 * {@code quarkus-axon-jpa-eventstore} já ocupa esse lugar; um segundo bean faria a partida morrer com
 * {@code AmbiguousResolutionException}. O {@code AxonEventProcessingConfigurer}, ao contrário, é
 * coletado por {@code Instance} — várias implementações convivem.
 * <p>
 * É uso torto de um nome, e fica registrado como tal. O conserto certo é upstream, e é o mesmo tipo de
 * dívida que o {@code libs/axon-native-support} carrega: código escrito para ser doado à extensão.
 *
 * <h2>Decorador, não substituição</h2>
 * {@code registerTagResolver} trocaria o componente e quebraria o caminho local, onde as tags vêm das
 * anotações. {@code registerDecorator} embrulha o resolver do framework e o mantém como delegate.
 */
@ApplicationScoped
public class ChannelIntegrationConfiguration implements AxonEventProcessingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ChannelIntegrationConfiguration.class);

    @Override
    public void configure(EventSourcingConfigurer configurer,
            Stream<EventhandlersPerNamespace.EventhandlersOfANamespace> pooledNamespaces) {
        configurer.componentRegistry(registry -> registry.registerDecorator(
                TagResolver.class, 0,
                (configuration, name, delegate) -> new ChannelTagResolver(delegate)));
        log.info("TagResolver decorado: eventos ingeridos do channel serão apendados com as tags de origem");
    }
}

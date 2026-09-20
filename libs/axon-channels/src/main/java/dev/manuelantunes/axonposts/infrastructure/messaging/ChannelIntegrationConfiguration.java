package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.stream.Stream;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.meks.quarkiverse.axon.runtime.customizations.AxonEventProcessingConfigurer;
import at.meks.quarkiverse.axon.runtime.defaults.eventprocessors.EventhandlersPerNamespace;
import jakarta.enterprise.context.ApplicationScoped;

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

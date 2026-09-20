package dev.manuelantunes.axonposts.infrastructure.axon;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.extension.metrics.micrometer.MetricsConfigurationEnhancer;

import at.meks.quarkiverse.axon.runtime.customizations.AxonMetricsConfigurer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AxonMetrics implements AxonMetricsConfigurer {
    private static final boolean USE_DIMENSIONS = true;

    private final MeterRegistry meterRegistry;

    AxonMetrics(OpenTelemetry openTelemetry) {
        this.meterRegistry = OpenTelemetryMeterRegistry.create(openTelemetry);
    }

    @Override
    public void configure(EventSourcingConfigurer configurer) {
        var enhancer = new MetricsConfigurationEnhancer(meterRegistry, USE_DIMENSIONS);
        configurer.componentRegistry(componentRegistry -> componentRegistry.registerEnhancer(enhancer));
    }
}

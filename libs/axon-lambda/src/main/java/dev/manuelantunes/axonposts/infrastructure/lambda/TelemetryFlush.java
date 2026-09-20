package dev.manuelantunes.axonposts.infrastructure.lambda;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TelemetryFlush {
    private static final Logger log = LoggerFactory.getLogger(TelemetryFlush.class);

    private final OpenTelemetry openTelemetry;
    private final Duration timeout;

    TelemetryFlush(OpenTelemetry openTelemetry,
            @ConfigProperty(name = "axonposts.lambda.telemetry.flush-timeout",
                    defaultValue = "2s") Duration timeout) {
        this.openTelemetry = openTelemetry;
        this.timeout = timeout;
    }

    public void beforeFreeze() {
        if (!(openTelemetry instanceof OpenTelemetrySdk sdk)) {
            log.debug("telemetria: o bean OpenTelemetry não é o SDK ({}); nada a despachar",
                    openTelemetry.getClass().getName());
            return;
        }
        try {
            boolean completed = CompletableResultCode.ofAll(List.of(
                    sdk.getSdkTracerProvider().forceFlush(),
                    sdk.getSdkLoggerProvider().forceFlush(),
                    sdk.getSdkMeterProvider().forceFlush()))
                    .join(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .isSuccess();
            if (!completed) {
                log.warn("telemetria: o flush não terminou em {} — o que sobrou sai na próxima "
                        + "invocação desta função", timeout);
            }
        } catch (RuntimeException failure) {
            log.warn("telemetria: o flush falhou; a invocação segue normalmente", failure);
        }
    }
}

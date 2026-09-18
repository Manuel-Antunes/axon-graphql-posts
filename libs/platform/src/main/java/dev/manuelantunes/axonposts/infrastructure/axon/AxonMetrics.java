package dev.manuelantunes.axonposts.infrastructure.axon;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.extension.metrics.micrometer.MetricsConfigurationEnhancer;

import at.meks.quarkiverse.axon.runtime.customizations.AxonMetricsConfigurer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * <b>As métricas do Axon, ligadas ao OpenTelemetry.</b> Está na PLATAFORMA e não num app pela mesma razão
 * que {@link EventSourcedEntities}: os dois serviços precisam dela, e nenhum dos dois tem nada de próprio
 * a dizer sobre o assunto.
 *
 * <h2>O que ela faz existir</h2>
 * O que mede a saúde de um sistema movido a evento não é requisição por segundo, é o <b>atraso</b> — o
 * quanto cada processor está atrás do stream. O {@link MetricsConfigurationEnhancer} registra nos
 * componentes certos os {@code MessageMonitor} do Axon: contagem e timer por tipo de mensagem, capacidade,
 * e o {@code EventProcessorLatencyMonitor}, que é o que responde àquela pergunta. Cada processor aparece
 * com o próprio nome — {@code post_projection_latency} e {@code tag_decision_latency} saem do
 * {@code quarkus.axon.subscribingprocessor.name} de cada aplicação.
 * <p>
 * Trace nenhum mede isso: um trace conta uma requisição que já passou, e aqui a pergunta é sobre o que
 * ainda não passou. Os dois sinais são complementares, e este é o que falta quando só há trace.
 *
 * <h2>Por que escrito à mão, e não pelo {@code quarkus-axon-metrics}</h2>
 * A extensão faz exatamente as duas linhas de {@link #configure} — mas arrasta {@code quarkus-micrometer},
 * que depende de {@code quarkus-vertx-http} e <b>não</b> em escopo opcional. Isso daria uma porta HTTP a
 * quem a importasse, e {@code apps/tagging} não tem nem quer uma: ele disputaria o 8080 com o
 * {@code posts-api} em dev, e uma extensão de HTTP contradiz o desenho do módulo. O que a extensão precisa
 * de verdade é um {@link MeterRegistry} — e o {@link OpenTelemetryMeterRegistry} é um sobre o
 * {@link OpenTelemetry} que a aplicação já tem, num JAR em vez de numa extensão.
 * <p>
 * De quebra, as métricas saem pelo <b>mesmo OTLP</b> que os traces e os logs. A alternativa da extensão
 * traz o registry de Prometheus, e com ele metade do sinal iria para um coletor que este projeto não sobe.
 *
 * <h2>Funciona por ausência</h2>
 * Isto substitui o {@code NoMetricsConfigurer} da extensão, que é {@code @DefaultBean}. Apagar esta classe
 * não quebra compilação nenhuma — as métricas simplesmente deixam de existir, nos dois serviços. É a mesma
 * armadilha silenciosa do {@code TransactionManager} e do mapa de ids, e é por isso que ela está descrita
 * aqui e no CLAUDE.md.
 */
@ApplicationScoped
public class AxonMetrics implements AxonMetricsConfigurer {

    /**
     * {@code true} para que o nome do componente vá como TAG em vez de embutido no nome da métrica — é o
     * que permite comparar dois processors no mesmo gráfico em vez de duas séries sem relação. É o default
     * da extensão ({@code quarkus.axon.metrics.use-dimensions}); aqui é constante porque, sem a extensão,
     * não há propriedade que o diga, e não há caso neste projeto para o valor oposto.
     */
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

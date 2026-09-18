package dev.manuelantunes.axonposts.nativesupport.deployment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.pkg.steps.NativeBuild;

/**
 * O que falta ao Axon Framework 5 para rodar em GraalVM native sob a extensão
 * {@code at.meks.quarkiverse.axonframework-extension}.
 *
 * <h2>Por que isto existe</h2>
 * A extensão descobre entidades e handlers em build time e publica os gateways como beans, mas <b>não
 * emite um único build item de native</b> — nem {@code ReflectiveClassBuildItem}, nem
 * {@code ServiceProviderBuildItem}, e não há {@code META-INF/native-image} em nenhum jar dela. O Axon,
 * por outro lado, instancia e invoca por reflexão em três frentes. O binário <b>compila</b> e falha
 * depois, de três jeitos e todos tardios:
 * <ol>
 *   <li>na partida, {@code No suitable constructor found for entity of type
 *       [AnnotationBasedEventSourcedEntityFactoryDefinition]};</li>
 *   <li>na partida, {@code No suitable ParameterResolver found for type [TagCreatedEvent]}, porque o
 *       {@code ParameterResolverFactory} vem por {@code ServiceLoader} e o Quarkus compila com
 *       {@code -H:-UseServiceLoaderFeature};</li>
 *   <li><b>pior de todos</b>: a aplicação sobe, serve o schema, e toda mutation responde
 *       {@code no-handler-for-command} — porque os métodos {@code @CommandHandler} do projeto não
 *       estão registrados para reflexão. Nada falha no build.</li>
 * </ol>
 *
 * <h2>Por que build step e não um reachability-metadata.json</h2>
 * Um JSON capturado com o agente de tracing resolve, e foi como se provou que o Axon 5 funciona em
 * native. Mas ele é uma <b>lista nominal</b>: envelhece em silêncio a cada command ou handler novo, e
 * a falha aparece só em runtime, no terceiro formato acima. Aqui o que é do <b>projeto</b> sai de uma
 * regra sobre o índice Jandex, e só o que é <b>contrato do framework</b> é literal.
 *
 * <h2>Escrito para virar PR</h2>
 * Não depende de nada deste projeto: só das anotações públicas do Axon e da API de build items do
 * Quarkus. É para ser movido para {@code quarkus-axon-deployment} como está.
 */
public class AxonNativeImageProcessor {

    private static final String FEATURE = "axon-native-support";

    /**
     * As oito interfaces que o Axon 5.3.1 resolve por {@code ServiceLoader}. Literal porque é contrato
     * do framework, não do projeto.
     * <p>
     * O {@code allProvidersFromClassPath} registra <b>só estas</b>. A alternativa preguiçosa,
     * {@code quarkus.native.auto-service-loader-registration=true}, registra as de todos os jars — e o
     * build morre nos validadores Joda-Time do Hibernate Validator e no {@code TracingService} do
     * SmallRye, que referenciam classes ausentes. Foi medido, nesta ordem.
     */
    private static final List<String> AXON_SERVICE_INTERFACES = List.of(
            "org.axonframework.common.configuration.ConfigurationEnhancer",
            "org.axonframework.common.nullability.NullabilityResolver",
            "org.axonframework.common.property.PropertyAccessStrategy",
            "org.axonframework.conversion.ContentTypeConverter",
            "org.axonframework.messaging.core.annotation.HandlerDefinition",
            "org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition",
            "org.axonframework.messaging.core.annotation.ParameterResolverFactory",
            "org.axonframework.modelling.entity.annotation.EntityChildModelDefinition");

    /**
     * Os defaults dos três atributos {@code *Definition} de {@code @EventSourcedEntity}. O Axon os
     * instancia com {@code ConstructorUtils.getConstructorFunctionWithZeroArguments} — fora do
     * {@code ServiceLoader} —, então sem o construtor registrado a aplicação não sobe.
     *
     * <h3>Por que literal, e não lido do default da anotação</h3>
     * Ler o default exigiria a classe da anotação no índice Jandex, o que significa
     * {@code IndexDependencyBuildItem} sobre os jars do Axon. <b>Não fazer isso.</b> O índice é
     * compartilhado: a extensão Hibernate ORM descobre {@code @Entity} lendo o mesmo índice, e indexar
     * o Axon faz ela adotar as entidades JPA do event store dele na persistence unit. Com
     * {@code schema-management.strategy=validate} o resultado é a aplicação morrendo com
     * {@code Schema validation: missing table [AggregateEventEntry]}. Foi medido também.
     * <p>
     * Um atributo <b>explicitamente declarado</b> continua sendo lido do índice (ver
     * {@link #registerEntityDefinitions}); esta lista cobre apenas o caso de não haver declaração.
     */
    private static final List<String> AXON_DEFAULT_DEFINITIONS = List.of(
            "org.axonframework.eventsourcing.annotation.reflection.AnnotationBasedEventSourcedEntityFactoryDefinition",
            "org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolverDefinition",
            "org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolverDefinition");

    /** Anotações de classe: a entidade e os três tipos de mensagem, que são contrato serializado. */
    private static final List<DotName> ON_CLASS = List.of(
            DotName.createSimple("org.axonframework.eventsourcing.annotation.EventSourcedEntity"),
            DotName.createSimple("org.axonframework.messaging.commandhandling.annotation.Command"),
            DotName.createSimple("org.axonframework.messaging.eventhandling.annotation.Event"),
            DotName.createSimple("org.axonframework.messaging.queryhandling.annotation.Query"));

    /**
     * Anotações de membro. Registra-se a classe <b>declarante</b>: é nela que o Axon procura o método,
     * e é o método que ele invoca. Inclui as de parâmetro e de campo ({@code @InjectEntity},
     * {@code @TargetEntityId}, {@code @EventTag}) porque a resolução delas também é reflexiva.
     */
    private static final List<DotName> ON_MEMBER = List.of(
            DotName.createSimple("org.axonframework.messaging.commandhandling.annotation.CommandHandler"),
            DotName.createSimple("org.axonframework.messaging.queryhandling.annotation.QueryHandler"),
            DotName.createSimple("org.axonframework.messaging.eventhandling.annotation.EventHandler"),
            DotName.createSimple("org.axonframework.eventsourcing.annotation.EventSourcingHandler"),
            DotName.createSimple("org.axonframework.eventsourcing.annotation.reflection.EntityCreator"),
            DotName.createSimple("org.axonframework.modelling.annotation.InjectEntity"),
            DotName.createSimple("org.axonframework.modelling.annotation.TargetEntityId"),
            DotName.createSimple("org.axonframework.eventsourcing.annotation.EventTag"));

    private static final DotName EVENT_SOURCED_ENTITY = ON_CLASS.get(0);

    private static final List<String> DEFINITION_ATTRIBUTES = List.of(
            "entityFactoryDefinition", "criteriaResolverDefinition", "entityIdResolverDefinition");

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep(onlyIf = NativeBuild.class)
    void registerAxonServiceProviders(BuildProducer<ServiceProviderBuildItem> services) {
        for (String serviceInterface : AXON_SERVICE_INTERFACES) {
            services.produce(ServiceProviderBuildItem.allProvidersFromClassPath(serviceInterface));
        }
    }

    /**
     * A regra que substitui a lista nominal: tudo que o Axon alcança por reflexão está marcado por uma
     * anotação dele. Handler novo, command novo ou evento novo entram sozinhos.
     */
    @BuildStep(onlyIf = NativeBuild.class)
    void registerAnnotatedTypes(CombinedIndexBuildItem combinedIndex,
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        IndexView index = combinedIndex.getIndex();
        Set<String> types = new LinkedHashSet<>();

        for (DotName annotation : ON_CLASS) {
            for (AnnotationInstance instance : index.getAnnotations(annotation)) {
                if (instance.target().kind() == AnnotationTarget.Kind.CLASS) {
                    types.add(instance.target().asClass().name().toString());
                }
            }
        }
        for (DotName annotation : ON_MEMBER) {
            for (AnnotationInstance instance : index.getAnnotations(annotation)) {
                ClassInfo declaring = declaringClassOf(instance.target());
                if (declaring != null) {
                    types.add(declaring.name().toString());
                }
            }
        }
        // Os tipos concretos de um agregado polimórfico: o Axon fixa o tipo na criação e o instancia.
        for (AnnotationInstance entity : index.getAnnotations(EVENT_SOURCED_ENTITY)) {
            AnnotationValue concreteTypes = entity.value("concreteTypes");
            if (concreteTypes != null) {
                for (Type concrete : concreteTypes.asClassArray()) {
                    types.add(concrete.name().toString());
                }
            }
        }

        if (!types.isEmpty()) {
            reflective.produce(ReflectiveClassBuildItem.builder(types.toArray(String[]::new))
                    .constructors().methods().fields()
                    .reason("Axon invoca handlers, @EntityCreator e payloads de mensagem por reflexão")
                    .build());
        }
    }

    /**
     * As {@code *Definition} de {@code @EventSourcedEntity}: os defaults do framework, mais qualquer
     * implementação que o projeto declare explicitamente no atributo.
     */
    @BuildStep(onlyIf = NativeBuild.class)
    void registerEntityDefinitions(CombinedIndexBuildItem combinedIndex,
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        Set<String> definitions = new LinkedHashSet<>(AXON_DEFAULT_DEFINITIONS);

        for (AnnotationInstance entity : combinedIndex.getIndex().getAnnotations(EVENT_SOURCED_ENTITY)) {
            for (String attribute : DEFINITION_ATTRIBUTES) {
                AnnotationValue declared = entity.value(attribute);
                if (declared != null) {
                    definitions.add(declared.asClass().name().toString());
                }
            }
        }

        reflective.produce(ReflectiveClassBuildItem.builder(definitions.toArray(String[]::new))
                .constructors()
                .reason("o Axon instancia as *Definition de @EventSourcedEntity por reflexão")
                .build());
    }

    private static ClassInfo declaringClassOf(AnnotationTarget target) {
        return switch (target.kind()) {
            case CLASS -> target.asClass();
            case METHOD -> target.asMethod().declaringClass();
            case METHOD_PARAMETER -> target.asMethodParameter().method().declaringClass();
            case FIELD -> target.asField().declaringClass();
            case RECORD_COMPONENT -> target.asRecordComponent().declaringClass();
            default -> null;
        };
    }
}

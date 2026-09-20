package dev.manuelantunes.axonposts.nativesupport.deployment;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.RecordComponentInfo;
import org.jboss.jandex.Type;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.pkg.steps.NativeBuild;

public class AxonNativeImageProcessor {
    private static final String FEATURE = "axon-native-support";

    private static final List<String> AXON_SERVICE_INTERFACES = List.of(
            "org.axonframework.common.configuration.ConfigurationEnhancer",
            "org.axonframework.common.nullability.NullabilityResolver",
            "org.axonframework.common.property.PropertyAccessStrategy",
            "org.axonframework.conversion.ContentTypeConverter",
            "org.axonframework.messaging.core.annotation.HandlerDefinition",
            "org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition",
            "org.axonframework.messaging.core.annotation.ParameterResolverFactory",
            "org.axonframework.modelling.entity.annotation.EntityChildModelDefinition");

    private static final List<String> AXON_DEFAULT_DEFINITIONS = List.of(
            "org.axonframework.eventsourcing.annotation.reflection.AnnotationBasedEventSourcedEntityFactoryDefinition",
            "org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolverDefinition",
            "org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolverDefinition");

    private static final List<String> AXON_DEFAULT_IMPLEMENTATIONS = List.of(
            "org.axonframework.eventsourcing.annotation.reflection.AnnotationBasedEventSourcedEntityFactory",
            "org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolver",
            "org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver",
            "org.axonframework.modelling.PropertyBasedEntityIdResolver",
            "org.axonframework.eventsourcing.configuration.RepresentationConvertingEntityIdResolver",
            "org.axonframework.modelling.entity.annotation.AnnotatedEntityIdResolver",
            "org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent",
            "org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver");

    private static final List<DotName> ON_CLASS = List.of(
            DotName.createSimple("org.axonframework.eventsourcing.annotation.EventSourcedEntity"),
            DotName.createSimple("org.axonframework.messaging.commandhandling.annotation.Command"),
            DotName.createSimple("org.axonframework.messaging.eventhandling.annotation.Event"),
            DotName.createSimple("org.axonframework.messaging.queryhandling.annotation.Query"));

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
        for (AnnotationInstance entity : index.getAnnotations(EVENT_SOURCED_ENTITY)) {
            AnnotationValue concreteTypes = entity.value("concreteTypes");
            if (concreteTypes != null) {
                for (Type concrete : concreteTypes.asClassArray()) {
                    types.add(concrete.name().toString());
                }
            }
        }

        types.addAll(composedTypesOf(types, index));

        if (!types.isEmpty()) {
            reflective.produce(ReflectiveClassBuildItem.builder(types.toArray(String[]::new))
                    .constructors().methods().fields()
                    .reason("Axon invoca handlers, @EntityCreator e payloads de mensagem por reflexão,"
                            + " e serializa cada payload com tudo que ele contém")
                    .build());
        }
    }

    private static Set<String> composedTypesOf(Set<String> roots, IndexView index) {
        Set<String> found = new LinkedHashSet<>();
        Deque<DotName> pending = new ArrayDeque<>();
        roots.forEach(root -> pending.add(DotName.createSimple(root)));

        while (!pending.isEmpty()) {
            ClassInfo owner = index.getClassByName(pending.poll());
            if (owner == null) continue;

            for (RecordComponentInfo component : owner.recordComponents()) {
                collect(component.type(), index, found, pending);
            }
            for (FieldInfo field : owner.fields()) {
                if (!Modifier.isStatic(field.flags())) {
                    collect(field.type(), index, found, pending);
                }
            }
        }
        return found;
    }

    private static void collect(Type type, IndexView index, Set<String> found, Deque<DotName> pending) {
        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            for (Type argument : type.asParameterizedType().arguments()) {
                collect(argument, index, found, pending);
            }
            return;
        }
        if (type.kind() == Type.Kind.ARRAY) {
            collect(type.asArrayType().constituent(), index, found, pending);
            return;
        }
        if (type.kind() != Type.Kind.CLASS) return;

        DotName name = type.name();
        if (index.getClassByName(name) == null) return;
        if (found.add(name.toString())) {
            pending.add(name);
        }
    }

    @BuildStep(onlyIf = NativeBuild.class)
    void registerEntityDefinitions(CombinedIndexBuildItem combinedIndex,
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        Set<String> definitions = new LinkedHashSet<>(AXON_DEFAULT_DEFINITIONS);
        definitions.addAll(AXON_DEFAULT_IMPLEMENTATIONS);

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
                .reason("o Axon instancia as *Definition de @EventSourcedEntity por reflexão, "
                        + "e cada uma delas instancia o resolver/fábrica que faz o trabalho")
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

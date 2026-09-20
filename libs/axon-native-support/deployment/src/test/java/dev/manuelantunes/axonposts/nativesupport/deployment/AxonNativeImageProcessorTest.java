package dev.manuelantunes.axonposts.nativesupport.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import io.quarkus.builder.item.BuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AxonNativeImageProcessorTest {
    private static CombinedIndexBuildItem index;

    private final AxonNativeImageProcessor processor = new AxonNativeImageProcessor();

    @BeforeAll
    static void indexTheFixtures() throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> type : List.of(
                Fixtures.class,
                Fixtures.AssignedTag.class,
                Fixtures.TagColour.class,
                Fixtures.DeepTag.class,
                Fixtures.PostCreatedEvent.class,
                Fixtures.PostDeepenedEvent.class,
                Fixtures.CreatePost.class,
                Fixtures.User.class,
                Fixtures.Author.class,
                Fixtures.Reader.class,
                Fixtures.PostProjection.class,
                Fixtures.PostCommandHandler.class,
                Fixtures.Unrelated.class)) {
            try (InputStream bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                indexer.index(bytes);
            }
        }
        Index built = indexer.complete();
        index = new CombinedIndexBuildItem(built, built);
    }

    private static <T extends BuildItem> Collecting<T> collecting() {
        return new Collecting<>();
    }

    private static final class Collecting<T extends BuildItem> implements BuildProducer<T> {
        private final List<T> produced = new ArrayList<>();

        @Override
        public void produce(T item) {
            produced.add(item);
        }
    }

    private List<String> registeredForReflection() {
        Collecting<ReflectiveClassBuildItem> items = collecting();
        processor.registerAnnotatedTypes(index, items);
        return items.produced.stream().flatMap(item -> item.getClassNames().stream()).toList();
    }

    private List<String> registeredDefinitions() {
        Collecting<ReflectiveClassBuildItem> items = collecting();
        processor.registerEntityDefinitions(index, items);
        return items.produced.stream().flatMap(item -> item.getClassNames().stream()).toList();
    }

    @Test
    void registersTypesAnnotatedOnTheClass() {
        assertThat(registeredForReflection())
                .contains(Fixtures.PostCreatedEvent.class.getName(),
                        Fixtures.CreatePost.class.getName(),
                        Fixtures.User.class.getName());
    }

    @Test
    void registersTheClassThatDeclaresAnAnnotatedMember() {
        assertThat(registeredForReflection())
                .contains(Fixtures.PostProjection.class.getName(),
                        Fixtures.PostCommandHandler.class.getName());
    }

    @Test
    void registersTypesNestedInsideAMessagePayload() {
        assertThat(registeredForReflection())
                .as("um record aninhado dentro de List<> no payload tem de ser registrado, "
                        + "senão a serialização morre com ConversionException")
                .contains(Fixtures.AssignedTag.class.getName());
    }

    @Test
    void theClosureIsTransitiveAndNotOneLevelDeep() {
        assertThat(registeredForReflection())
                .contains(Fixtures.DeepTag.class.getName(), Fixtures.TagColour.class.getName());
    }

    @Test
    void theClosureStopsAtTypesTheIndexDoesNotKnow() {
        assertThat(registeredForReflection())
                .doesNotContain("java.lang.String", "java.time.Instant");
    }

    @Test
    void doesNotRegisterUnrelatedTypes() {
        assertThat(registeredForReflection()).doesNotContain(Fixtures.Unrelated.class.getName());
    }

    @Test
    void registersTheConcreteTypesOfAPolymorphicEntity() {
        assertThat(registeredForReflection())
                .contains(Fixtures.Author.class.getName(), Fixtures.Reader.class.getName());
    }

    @Test
    void registersBothTheDefinitionsAndWhatTheyInstantiate() {
        assertThat(registeredDefinitions())
                .as("a *Definition é instanciada por reflexão, e ela instancia o resolver que trabalha")
                .contains(
                        "org.axonframework.eventsourcing.annotation.reflection."
                                + "AnnotationBasedEventSourcedEntityFactoryDefinition",
                        "org.axonframework.eventsourcing.annotation.reflection."
                                + "AnnotationBasedEventSourcedEntityFactory",
                        "org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolverDefinition",
                        "org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver");
    }

    @Test
    void asksForConstructorsMethodsAndFields() {
        Collecting<ReflectiveClassBuildItem> items = collecting();
        processor.registerAnnotatedTypes(index, items);

        assertThat(items.produced).singleElement().satisfies(item -> {
            assertThat(item.isConstructors()).isTrue();
            assertThat(item.isMethods()).isTrue();
            assertThat(item.isFields()).isTrue();
        });
    }
}

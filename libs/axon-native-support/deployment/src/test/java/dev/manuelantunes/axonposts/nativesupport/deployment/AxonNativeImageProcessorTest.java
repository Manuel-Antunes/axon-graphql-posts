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

/**
 * O que esta extensão REGISTRA para o binário nativo — e cada teste aqui corresponde a uma falha que
 * já aconteceu.
 *
 * <h2>Por que este arquivo importa mais do que o tamanho dele sugere</h2>
 * Porque nenhuma das falhas que ele cobre aparece na JVM, e três das quatro só aparecem em RUNTIME, na
 * AWS, com mensagens que apontam para o lugar errado: o Lambda respondendo
 * {@code Runtime exited with error: exit status 1} sem causa, o cliente recebendo "System error" sem
 * uma linha no log do servidor, e — a pior — a saga parando na versão 1 com o contador de erros do
 * Lambda em ZERO e as filas vazias.
 *
 * <h2>Como ele funciona</h2>
 * Indexa as classes de {@link Fixtures} com o Jandex de verdade — o mesmo que o augmentation usa — e
 * chama os {@code @BuildStep} diretamente, coletando o que eles produzem. Não há Quarkus subindo: um
 * build step é um método, e o que ele devolve é um objeto.
 */
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

    /** Coleta o que um build step produz — `BuildProducer` é uma interface de um método só. */
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

    /**
     * Uma classe alcançável SÓ pela anotação num método também entra.
     * <p>
     * É o caso de toda projeção e todo command handler: a classe não tem anotação nenhuma, e sem este
     * caminho o Axon não conseguiria invocá-la por reflexão.
     */
    @Test
    void registersTheClassThatDeclaresAnAnnotatedMember() {
        assertThat(registeredForReflection())
                .contains(Fixtures.PostProjection.class.getName(),
                        Fixtures.PostCommandHandler.class.getName());
    }

    /**
     * <b>A FALHA Nº 6, e a razão de este arquivo existir.</b>
     * <p>
     * {@code PostCreatedEvent} era registrado; o {@code AssignedTag} que ele carrega dentro de um
     * {@code List<>} é um record ANINHADO, sem anotação, e ficava de fora. O serviço de tagueamento
     * decidia a tag e não conseguia publicar o evento:
     * <pre>
     * ConversionException: Exception when trying to convert object of type
     *   ...PostCreatedEvent to 'byte[]'
     * </pre>
     * O que tornava isso difícil de achar: a saga parava na versão 1, o contador {@code Errors} do
     * Lambda ficava em ZERO, e as filas ficavam VAZIAS. Tudo apontava para o lugar errado.
     */
    @Test
    void registersTypesNestedInsideAMessagePayload() {
        assertThat(registeredForReflection())
                .as("um record aninhado dentro de List<> no payload tem de ser registrado, "
                        + "senão a serialização morre com ConversionException")
                .contains(Fixtures.AssignedTag.class.getName());
    }

    /** E o fecho é TRANSITIVO, não de um nível: o aninhado do aninhado também entra. */
    @Test
    void theClosureIsTransitiveAndNotOneLevelDeep() {
        assertThat(registeredForReflection())
                .contains(Fixtures.DeepTag.class.getName(), Fixtures.TagColour.class.getName());
    }

    /**
     * O fecho PARA no que o índice não conhece.
     * <p>
     * `String` e `Instant` são do JDK e não precisam de registro — e mais: segui-los faria a varredura
     * percorrer meio `java.base` a cada evento. A guarda é `index.getClassByName(name) == null`.
     */
    @Test
    void theClosureStopsAtTypesTheIndexDoesNotKnow() {
        assertThat(registeredForReflection())
                .doesNotContain("java.lang.String", "java.time.Instant");
    }

    /** Uma classe sem anotação e que ninguém compõe NÃO entra — o registro não é "tudo". */
    @Test
    void doesNotRegisterUnrelatedTypes() {
        assertThat(registeredForReflection()).doesNotContain(Fixtures.Unrelated.class.getName());
    }

    /**
     * Os tipos concretos de um agregado POLIMÓRFICO só aparecem no atributo da anotação.
     * <p>
     * É o {@code User} deste projeto: {@code @EventSourcedEntity(concreteTypes = {Reader, Author})}.
     * O Axon fixa o tipo concreto na criação, então sem registrá-los o replay não consegue
     * reconstruir nem um nem outro.
     */
    @Test
    void registersTheConcreteTypesOfAPolymorphicEntity() {
        assertThat(registeredForReflection())
                .contains(Fixtures.Author.class.getName(), Fixtures.Reader.class.getName());
    }

    /**
     * <b>A FALHA Nº 2:</b> a reflexão do Axon tem DOIS níveis, e registrar só as {@code *Definition}
     * não basta — é preciso registrar o que elas INSTANCIAM.
     * <p>
     * Com só o primeiro nível a aplicação sobe o bastante para o health responder 200 e morre A CADA
     * REQUISIÇÃO com {@code Runtime exited with error: exit status 1}, que o Lambda reporta sem causa.
     */
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

    /** O registro pede construtores, métodos E campos: o Axon usa os três ao reidratar um payload. */
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

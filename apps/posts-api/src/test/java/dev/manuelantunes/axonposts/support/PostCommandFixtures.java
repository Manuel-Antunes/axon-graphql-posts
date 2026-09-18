package dev.manuelantunes.axonposts.support;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Monta o {@link AxonTestFixture} de um único command.
 * <p>
 * A "fatia" montada aqui é exatamente o que o Spring Boot registraria em runtime — a entidade anotada
 * {@link Post} e <b>um</b> componente de command handling — só que num configurer à mão, com event store
 * em memória e {@link #FIXED_CLOCK}. Como cada command tem a sua classe, cada teste carrega só o
 * command que está testando: se um teste de update passar a depender do de criação, ele quebra.
 */
public final class PostCommandFixtures {

    /** Instante fixo carimbado em todos os eventos dos testes. */
    public static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    /** O bean {@code Clock} do {@code AxonConfig}, congelado. */
    public static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private PostCommandFixtures() {
    }

    /**
     * @param moduleName nome do módulo de command handling (aparece nas mensagens de erro do Axon)
     * @param command    construtor do command sob teste
     */
    public static AxonTestFixture forCommand(String moduleName, ComponentBuilder<Object> command) {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create()
                .registerEntity(EventSourcedEntityModule.autodetected(PostId.class, Post.class))
                .registerCommandHandlingModule(
                        CommandHandlingModule.named(moduleName)
                                .commandHandlers()
                                .autodetectedCommandHandlingComponent(command)
                );
        return AxonTestFixture.with(configurer);
    }

    /** {@code true} se {@code thrown} ou alguma de suas causas for do tipo pedido. */
    public static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable t = thrown; t != null && t.getCause() != t; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}

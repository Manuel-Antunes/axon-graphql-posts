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

public final class PostCommandFixtures {
    public static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    public static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private PostCommandFixtures() {
    }

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

    public static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable t = thrown; t != null && t.getCause() != t; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}

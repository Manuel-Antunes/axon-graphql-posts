package dev.manuelantunes.axonposts.tagging.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent.AssignedTag;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.tagging.application.CompletePostWithDefaultTagCommand.CompletePostWithDefaultTag;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompletePostWithDefaultTagCommandTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final PostId POST_ID = PostId.of("11111111-1111-1111-1111-111111111111");
    private static final UserId AUTHOR_ID = UserId.of("author-1");
    private static final Instant BORN_AT = Instant.parse("2026-09-01T11:59:00Z");

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create()
                .registerEntity(EventSourcedEntityModule.autodetected(PostId.class, Post.class))
                .registerCommandHandlingModule(
                        CommandHandlingModule.named("tag-decision")
                                .commandHandlers()
                                .autodetectedCommandHandlingComponent(
                                        config -> new CompletePostWithDefaultTagCommand(FIXED_CLOCK))
                );
        fixture = AxonTestFixture.with(configurer);
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    private static PostPreCreatedEvent preCreated() {
        return new PostPreCreatedEvent(POST_ID, "Saga coreografada", "conteúdo", AUTHOR_ID, BORN_AT);
    }

    @Test
    void completesAPreCreatedPostWithTheDefaultTag() {
        fixture.given()
                .event(preCreated())
                .when()
                .command(new CompletePostWithDefaultTag(POST_ID))
                .then()
                .success()
                .events(new PostCreatedEvent(
                        POST_ID, "Saga coreografada", "conteúdo", AUTHOR_ID,
                        List.of(new AssignedTag(Tag.DEFAULT_ID.value(), Tag.DEFAULT_NAME)),
                        2L, NOW));
    }

    @Test
    void aSecondDeliveryDecidesNothingAndFailsNothing() {
        fixture.given()
                .event(preCreated())
                .event(new PostCreatedEvent(
                        POST_ID, "Saga coreografada", "conteúdo", AUTHOR_ID,
                        List.of(new AssignedTag(Tag.DEFAULT_ID.value(), Tag.DEFAULT_NAME)),
                        2L, NOW))
                .when()
                .command(new CompletePostWithDefaultTag(POST_ID))
                .then()
                .success()
                .noEvents();
    }

    @Test
    void theDefaultTagIdentityComesFromTheDomainAndNotFromThisService() {
        fixture.given()
                .event(preCreated())
                .when()
                .command(new CompletePostWithDefaultTag(POST_ID))
                .then()
                .success()
                .eventsSatisfy(events -> {
                    PostCreatedEvent created = (PostCreatedEvent) events.getFirst().payload();
                    assertThat(created.tags())
                            .singleElement()
                            .satisfies(tag -> {
                                assertThat(tag.name()).isEqualTo(Tag.DEFAULT_NAME);
                                assertThat(tag.tagId()).isEqualTo(Tag.DEFAULT_ID.value());
                            });
                });
    }

    @Test
    void thePostReachesVersionTwo() {
        fixture.given()
                .event(preCreated())
                .when()
                .command(new CompletePostWithDefaultTag(POST_ID))
                .then()
                .success()
                .eventsSatisfy(events -> assertThat(((PostCreatedEvent) events.getFirst().payload()).version())
                        .isEqualTo(2L));
    }
}

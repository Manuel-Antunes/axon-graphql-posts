package dev.manuelantunes.axonposts.application.tag.command;

import dev.manuelantunes.axonposts.application.tag.command.CreateTagCommand.CreateTag;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.event.TagCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import dev.manuelantunes.axonposts.domain.tag.exception.TagAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.tag.vo.TagName;
import dev.manuelantunes.axonposts.support.InMemoryTagRepository;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.manuelantunes.axonposts.support.PostCommandFixtures.FIXED_CLOCK;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.NOW;
import static dev.manuelantunes.axonposts.support.PostCommandFixtures.hasCause;
import static org.assertj.core.api.Assertions.assertThat;

/** Given-when-then do {@link CreateTagCommand}, e só dele. */
class CreateTagCommandTest {

    private InMemoryTagRepository tags;
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        tags = new InMemoryTagRepository();
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create()
                .registerEntity(EventSourcedEntityModule.autodetected(TagId.class, Tag.class))
                .registerCommandHandlingModule(
                        CommandHandlingModule.named("create-tag")
                                .commandHandlers()
                                .autodetectedCommandHandlingComponent(
                                        config -> new CreateTagCommand(FIXED_CLOCK, tags))
                );
        fixture = AxonTestFixture.with(configurer);
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Test
    void appendsTagCreatedSavesItAndReturnsTheId() {
        TagId id = TagId.newId();

        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreateTag(id, "  Untagged  "))
                .then()
                .success()
                .resultMessagePayload(id)
                .events(new TagCreatedEvent(id, "Untagged", NOW));

        assertThat(tags.findByName(TagName.of("untagged"))).isPresent();
    }

    @Test
    void rejectsABlankNameWithoutSavingAnything() {
        fixture.given()
                .noPriorActivity()
                .when()
                .command(new CreateTag(TagId.newId(), "   "))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, InvalidTagException.class)).isTrue());

        assertThat(tags.all()).isEmpty();
    }

    @Test
    void rejectsAnIdThatAlreadyHasAStream() {
        TagId id = TagId.newId();

        fixture.given()
                .event(new TagCreatedEvent(id, "Untagged", NOW))
                .when()
                .command(new CreateTag(id, "outra"))
                .then()
                .noEvents()
                .exceptionSatisfies(thrown -> assertThat(hasCause(thrown, TagAlreadyExistsException.class)).isTrue());

        assertThat(tags.all()).isEmpty();
    }
}

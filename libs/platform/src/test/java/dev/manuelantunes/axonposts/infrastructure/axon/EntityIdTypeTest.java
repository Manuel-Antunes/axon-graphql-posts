package dev.manuelantunes.axonposts.infrastructure.axon;

import java.time.Instant;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityIdTypeTest {
    record PostId(String value) {
    }

    record UserId(String value) {
    }

    record PostPreCreatedEvent(@EventTag PostId postId, String title, Instant occurredAt) {
    }

    record PostUpdatedEvent(@EventTag PostId postId, String title) {
    }

    @EventSourcedEntity(tagKey = "postId")
    static class Post {
        @EntityCreator
        Post(PostPreCreatedEvent event) {
        }

        @EventSourcingHandler
        void on(PostUpdatedEvent event) {
        }
    }

    @Test
    void theIdTypeIsTheOneDeclaredByTheEventTagMatchingTheEntitysTagKey() {
        assertThat(EntityIdType.of(Post.class)).contains(PostId.class);
    }

    record UserRegisteredEvent(@EventTag UserId userId, boolean author) {
    }

    @EventSourcedEntity(tagKey = "userId", concreteTypes = {Reader.class, Author.class})
    abstract static class User {
    }

    static class Reader extends User {
        @EntityCreator
        static User create(UserRegisteredEvent event) {
            return new Reader();
        }
    }

    static class Author extends User {
    }

    @Test
    void aPolymorphicEntityIsResolvedThroughItsConcreteTypes() {
        assertThat(EntityIdType.of(User.class)).contains(UserId.class);
    }

    record RenamedEvent(@EventTag(key = "postId") PostId identifier) {
    }

    @EventSourcedEntity(tagKey = "postId")
    static class RenamedTagKey {
        @EntityCreator
        RenamedTagKey(RenamedEvent event) {
        }
    }

    @Test
    void anExplicitTagKeyWinsOverTheMemberName() {
        assertThat(EntityIdType.of(RenamedTagKey.class)).contains(PostId.class);
    }

    record TaggedByGetter(Instant occurredAt) {
        @EventTag
        PostId getPostId() {
            return null;
        }
    }

    @EventSourcedEntity(tagKey = "postId")
    static class SourcedByAGetter {
        @EntityCreator
        SourcedByAGetter(TaggedByGetter event) {
        }
    }

    @Test
    void aTagOnAGetterIsReadByThePropertyNameItAccesses() {
        assertThat(EntityIdType.of(SourcedByAGetter.class)).contains(PostId.class);
    }

    record SharedByTwoEntitiesEvent(@EventTag(key = "postId") @EventTag(key = "courseId") PostId subject) {
    }

    @EventSourcedEntity(tagKey = "postId")
    static class TaggedForSeveralEntities {
        @EntityCreator
        TaggedForSeveralEntities(SharedByTwoEntitiesEvent event) {
        }
    }

    @Test
    void aMemberCarryingSeveralTagsIsStillRead() {
        assertThat(EntityIdType.of(TaggedForSeveralEntities.class)).contains(PostId.class);
    }

    record BikeRegisteredEvent(@EventTag(key = "Bike") String bikeId) {
    }

    @EventSourcedEntity
    static class Bike {
        @EntityCreator
        Bike(BikeRegisteredEvent event) {
        }
    }

    @Test
    void withNoTagKeyDeclaredTheEntitysSimpleNameIsTheKey() {
        assertThat(EntityIdType.of(Bike.class)).contains(String.class);
    }

    @EventSourcedEntity(tagKey = "postId")
    static class CreatedFromItsId {
        @EntityCreator
        CreatedFromItsId(@InjectEntityId PostId id) {
        }
    }

    @Test
    void anIdInjectedIntoTheCreatorIsTheMostDirectDeclaration() {
        assertThat(EntityIdType.of(CreatedFromItsId.class)).contains(PostId.class);
    }

    record DisagreeingEvent(@EventTag String postId) {
    }

    @EventSourcedEntity(tagKey = "postId")
    static class TaggedWithTwoTypes {
        @EntityCreator
        TaggedWithTwoTypes(PostPreCreatedEvent event) {
        }

        @EventSourcingHandler
        void on(DisagreeingEvent event) {
        }
    }

    @Test
    void eventsThatDisagreeOnTheTypeOfTheSameTagAreRefused() {
        assertThatThrownBy(() -> EntityIdType.of(TaggedWithTwoTypes.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostId")
                .hasMessageContaining("String");
    }

    @EventSourcedEntity(tagKey = "somethingNobodyTags")
    static class SourcedByCustomCriteria {
        @EntityCreator
        SourcedByCustomCriteria(PostPreCreatedEvent event) {
        }
    }

    @Test
    void anEntityNoEventTagsFallsBackToWhatTheExtensionResolved() {
        assertThat(EntityIdType.of(SourcedByCustomCriteria.class)).isEmpty();
    }

    static class NotAnEntity {
    }

    @Test
    void aClassWithoutTheAnnotationDeclaresNothing() {
        assertThat(EntityIdType.of(NotAnEntity.class)).isEmpty();
    }
}

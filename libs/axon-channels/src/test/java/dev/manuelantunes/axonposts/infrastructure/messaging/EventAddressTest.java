package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.util.List;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventAddressTest {
    private static EventMessage eventOf(String messageType, String identifier) {
        EventMessage event = mock(EventMessage.class);
        when(event.type()).thenReturn(MessageType.fromString(messageType));
        when(event.identifier()).thenReturn(identifier);
        return event;
    }

    @Test
    void readsNamespaceQualifiedNameAndIdentifierFromTheEvent() {
        EventAddress address = EventAddress.of(
                eventOf("posts.PostCreated#2.0.0", "msg-1"),
                List.of(new EventTag("postId", "p-1")));

        assertThat(address.namespace()).isEqualTo("posts");
        assertThat(address.qualifiedName()).isEqualTo("posts.PostCreated");
        assertThat(address.identifier()).isEqualTo("msg-1");
        assertThat(address.messageType()).isEqualTo("posts.PostCreated#2.0.0");
    }

    @Test
    void theOrderingKeyIsTheAggregateTagValue() {
        EventAddress address = EventAddress.of(
                eventOf("posts.PostCreated#2.0.0", "msg-1"),
                List.of(new EventTag("postId", "p-42")));

        assertThat(address.orderingKey()).isEqualTo("p-42");
    }

    @Test
    void anEventWithoutTagsStillGetsAnOrderingKey() {
        EventAddress address = EventAddress.of(eventOf("audit.Something#1.0.0", "msg-2"), List.of());

        assertThat(address.orderingKey()).isEqualTo(EventAddress.NO_AGGREGATE);
        assertThat(address.orderingKey()).isNotBlank();
    }

    @Test
    void withTwoTagsItTakesTheFirstOne() {
        EventAddress address = EventAddress.of(
                eventOf("posts.PostCreated#2.0.0", "msg-3"),
                List.of(new EventTag("postId", "p-1"), new EventTag("tagId", "t-2")));

        assertThat(address.orderingKey()).isEqualTo("p-1");
        assertThat(address.tags()).hasSize(2);
    }

    @Test
    void carriesTheTagsThrough() {
        List<EventTag> tags = List.of(new EventTag("postId", "p-1"));

        assertThat(EventAddress.of(eventOf("posts.PostCreated#2.0.0", "msg-4"), tags).tags())
                .isEqualTo(tags);
    }
}

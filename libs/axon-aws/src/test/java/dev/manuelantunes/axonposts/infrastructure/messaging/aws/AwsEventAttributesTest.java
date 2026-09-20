package dev.manuelantunes.axonposts.infrastructure.messaging.aws;

import java.util.List;
import java.util.Map;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwsEventAttributesTest {
    private static EventAddress address(String namespace, String qualifiedName, String orderingKey) {
        return new EventAddress(qualifiedName + "#1.0.0", qualifiedName, namespace, "msg-1",
                orderingKey, List.of(new EventTag("postId", orderingKey)));
    }

    @Test
    void carriesTheFiveAttributesAFilterPolicyCanMatchOn() {
        Map<String, String> attributes = AwsEventAttributes.of(
                address("posts", "posts.PostPreCreated", "p-42"));

        assertThat(attributes)
                .containsEntry(AwsEventAttributes.MESSAGE_NAME, "PostPreCreated")
                .containsEntry(AwsEventAttributes.ROUTING_KEY, "posts.PostPreCreated.p-42")
                .containsEntry(AwsEventAttributes.MESSAGE_TYPE, "posts.PostPreCreated#1.0.0")
                .containsEntry(AwsEventAttributes.MESSAGE_ID, "msg-1")
                .containsEntry(AwsEventAttributes.NAMESPACE, "posts");
    }

    @Test
    void theLocalNameDropsTheNamespacePrefix() {
        assertThat(AwsEventAttributes.localName(address("posts", "posts.PostCreated", "p-1")))
                .isEqualTo("PostCreated");
    }

    @Test
    void aQualifiedNameWithoutThePrefixIsKeptWhole() {
        assertThat(AwsEventAttributes.localName(address("posts", "outro.PostCreated", "p-1")))
                .isEqualTo("outro.PostCreated");
    }

    @Test
    void theRoutingKeyKeepsTheSameThreeSegmentShapeAsOnRabbitMq() {
        String routingKey = AwsEventAttributes.routingKey(address("posts", "posts.PostCreated", "p-1"));

        assertThat(routingKey).isEqualTo("posts.PostCreated.p-1");
        assertThat(routingKey.split("\\.")).hasSize(3);
    }

    @Test
    void anEventWithoutAnAggregateStillGetsAThirdSegment() {
        assertThat(AwsEventAttributes.routingKey(
                new EventAddress("audit.Something#1.0.0", "audit.Something", "audit", "msg-2",
                        EventAddress.NO_AGGREGATE, List.of())))
                .isEqualTo("audit.Something.none");
    }

    @Test
    void withoutAnActiveSpanTheAttributesAreStillComplete() {
        Map<String, String> attributes = AwsEventAttributes.of(
                address("posts", "posts.PostCreated", "p-1"));

        assertThat(attributes).hasSizeGreaterThanOrEqualTo(5);
        assertThat(attributes.get(AwsEventAttributes.MESSAGE_NAME)).isEqualTo("PostCreated");
    }
}

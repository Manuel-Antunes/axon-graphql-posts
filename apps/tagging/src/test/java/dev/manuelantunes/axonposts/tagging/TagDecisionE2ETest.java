package dev.manuelantunes.axonposts.tagging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope;
import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope.EventTag;
import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelEventIngestion;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
class TagDecisionE2ETest {
    private static final String OUT = "post-events-out";

    @Inject
    ChannelEventIngestion ingestion;

    @Inject
    ObjectMapper json;

    @Inject
    @Any
    InMemoryConnector connector;

    private byte[] preCreated(PostId postId) throws Exception {
        PostPreCreatedEvent event = new PostPreCreatedEvent(
                postId, "Saga coreografada", "conteúdo", UserId.of("author-1"), Instant.now());
        AxonEventEnvelope envelope = new AxonEventEnvelope(
                "posts.PostPreCreated#1.0.0",
                UUID.randomUUID().toString(),
                Instant.now(),
                Map.of("axon-channel-origin", "quarkus-axon-graphql-posts"),
                List.of(new EventTag("postId", postId.value())),
                AxonEventEnvelope.encodePayload(json.writeValueAsBytes(event)));
        return json.writeValueAsBytes(envelope);
    }

    private InMemorySink<Object> published() {
        return connector.sink(OUT);
    }

    @Test
    void aPreCreatedPostComesOutCompleteWithTheDefaultTag() throws Exception {
        PostId postId = PostId.newId();
        int before = published().received().size();

        ingestion.ingest(preCreated(postId));

        await().untilAsserted(() -> assertThat(published().received()).hasSizeGreaterThan(before));

        AxonEventEnvelope out = (AxonEventEnvelope) published().received().getLast().getPayload();

        assertThat(out.messageType()).startsWith("posts.PostCreated");
        assertThat(out.tags()).singleElement()
                .satisfies(tag -> assertThat(tag.value()).isEqualTo(postId.value()));

        assertThat(out.metadata()).containsEntry("axon-channel-origin", "axonposts-tagging");

        String payload = new String(out.decodePayload());
        assertThat(payload).contains(Tag.DEFAULT_NAME).contains(Tag.DEFAULT_ID.value());
    }

    @Test
    void redeliveringTheSameMessageDecidesNothingTwice() throws Exception {
        PostId postId = PostId.newId();
        byte[] message = preCreated(postId);
        int before = published().received().size();

        ingestion.ingest(message);
        await().untilAsserted(() -> assertThat(published().received()).hasSize(before + 1));

        ingestion.ingest(message);

        Thread.sleep(1_000);
        assertThat(published().received()).hasSize(before + 1);
    }
}

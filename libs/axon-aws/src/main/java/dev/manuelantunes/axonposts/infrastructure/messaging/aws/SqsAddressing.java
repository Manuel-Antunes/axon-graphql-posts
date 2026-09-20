package dev.manuelantunes.axonposts.infrastructure.messaging.aws;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.manuelantunes.axonposts.infrastructure.messaging.ChannelAddressing;
import dev.manuelantunes.axonposts.infrastructure.messaging.EventAddress;
import io.smallrye.reactive.messaging.aws.sqs.SqsConnector;
import io.smallrye.reactive.messaging.aws.sqs.SqsOutboundMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

@ApplicationScoped
public class SqsAddressing implements ChannelAddressing {
    private static final Logger log = LoggerFactory.getLogger(SqsAddressing.class);

    private static final String STRING = "String";

    @Override
    public String connector() {
        return SqsConnector.CONNECTOR_NAME;
    }

    @Override
    public Metadata addressing(EventAddress address) {
        Map<String, String> attributes = AwsEventAttributes.of(address);
        log.debug("sqs → grupo={} dedup={} atributos={}",
                address.orderingKey(), address.identifier(), attributes);
        return Metadata.of(SqsOutboundMetadata.builder()
                .groupId(address.orderingKey())
                .deduplicationId(address.identifier())
                .messageAttributes(asAttributeValues(attributes))
                .build());
    }

    private static Map<String, MessageAttributeValue> asAttributeValues(Map<String, String> attributes) {
        Map<String, MessageAttributeValue> values = new LinkedHashMap<>();
        attributes.forEach((key, value) -> values.put(key,
                MessageAttributeValue.builder().dataType(STRING).stringValue(value).build()));
        return values;
    }
}

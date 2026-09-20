package dev.manuelantunes.axonposts.infrastructure.lambda;

import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.ChannelRegistry;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SqsChannelBinding {
    private static final Logger log = LoggerFactory.getLogger(SqsChannelBinding.class);

    static final String PROPERTY = "axonposts.lambda.sqs.channel";

    private final String channel;
    private final ChannelRegistry channels;

    private volatile boolean verified;

    SqsChannelBinding(@ConfigProperty(name = PROPERTY) String channel, ChannelRegistry channels) {
        this.channel = channel;
        this.channels = channels;
    }

    public String channel() {
        if (!verified) {
            verify();
        }
        return channel;
    }

    private synchronized void verify() {
        if (verified) {
            return;
        }
        Set<String> wired = channels.getIncomingNames();
        if (!wired.contains(channel)) {
            throw new IllegalStateException(
                    PROPERTY + " diz '" + channel + "', e o SmallRye não ligou esse canal. Canais "
                            + "incoming ligados: " + wired + ". Um canal alimentado pelo Lambda precisa "
                            + "de mp.messaging.incoming." + channel
                            + ".connector=smallrye-in-memory e de um @Incoming que o consuma.");
        }
        log.info("entrada por Lambda: todo registro do SQS vai para o canal '{}'", channel);
        verified = true;
    }
}

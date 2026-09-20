package dev.manuelantunes.axonposts.infrastructure.lambda;

import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.ChannelRegistry;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A que canal esta função entrega — <b>uma linha de configuração</b>, e nada mais.
 *
 * <pre>
 * axonposts.lambda.sqs.channel=post-precreated-in
 * </pre>
 *
 * <h2>Por que não há tabela de roteamento aqui</h2>
 * Porque roteamento é do <b>IaC</b>, e não sobra nada para o código decidir. Quem recebe qual evento é
 * a <i>filter policy</i> de cada subscription do topic SNS — o equivalente exato da binding do exchange
 * topic, e igualmente declarativo:
 *
 * <pre>
 * postEvents.subscribeQueue("Precreated", precreated, {
 *   filterPolicy: { "axon-message-name": ["PostPreCreated"] },
 * });
 * postEvents.subscribeQueue("Changes", changes, {
 *   filterPolicy: { "axon-message-name": ["PostUpdated", "PostDeleted", "PostRestored"] },
 * });
 * </pre>
 *
 * Depois disso a fila JÁ contém exatamente o que aquele canal ingere. Restaria uma pergunta — "este
 * registro vai para qual {@code @Incoming}?" — e ela some se cada fila tiver a sua função: aí a
 * resposta é constante, e uma constante por função é uma variável de ambiente.
 *
 * <h2>Uma função por fila não é custo, é o motivo de as filas serem duas</h2>
 * Houve aqui uma versão que casava {@code eventSourceARN} contra um mapa de nomes de fila, para uma
 * função servir vários canais. Ela funcionava e era trinta linhas de análise de string para reconstruir,
 * dentro do processo, uma informação que o provisionamento já tinha.
 * <p>
 * E o que ela economizava — uma função a menos — é justamente o que este projeto <b>não</b> quer
 * economizar. As duas filas do serviço de tagueamento existem por isolamento de falha e vazão por
 * fatia: uma mensagem-veneno de atualização não deve ser rejeitada junto com as decisões pendentes, e
 * escalar um fluxo não deve mexer no outro. Juntá-las numa função só devolveria as duas na mesma
 * concorrência, na mesma DLQ e no mesmo <i>throttle</i> — desfazendo em runtime a separação que a
 * topologia comprou.
 *
 * <h2>A conferência, e o que ela pega</h2>
 * O canal tem de estar entre os que o SmallRye ligou. Sem isso, um nome errado na variável de ambiente
 * — {@code post-precreated} em vez de {@code post-precreated-in} — daria o
 * {@code IllegalArgumentException: Unknown channel} nu do conector in-memory, na primeira mensagem, com
 * a fila já tendo consumido. Aqui ele vira um erro que diz quais canais existem.
 */
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

    /** Conferido na primeira mensagem — na construção, o registro de canais ainda não está povoado. */
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

package dev.manuelantunes.axonposts.tagging.infrastructure.outbox;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope;
import dev.manuelantunes.axonposts.infrastructure.messaging.AxonOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * O destino de saída deste serviço: o que ele decide sobre o ciclo de vida do Post — hoje, o
 * {@code posts.PostCreated} que ele apenda ao completar o post.
 *
 * <h2>Ele publica no namespace POSTS, e isso é o desenho</h2>
 * O fato é do Post; quem o decidiu foi este serviço. É o que "o ciclo de vida do post tocado por dois
 * serviços" significa na prática, e o que mantém isso honesto é o evento ser o do agregado, e não uma
 * invenção local. Quem reage é problema de quem reage — este serviço não sabe que alguém escuta.
 *
 * <h2>Declarar o namespace, e não o nome do evento</h2>
 * Hoje este serviço produz um evento só, então "namespace {@code posts}" e "{@code posts.PostCreated}"
 * selecionam exatamente a mesma coisa. Os outros eventos de post que existem no store dele chegaram do
 * outro lado, e são descartados antes do roteamento pela marca de origem.
 * <p>
 * A diferença aparece no dia em que este serviço apendar outro fato no stream do Post: com o namespace,
 * ele sai — o que é o certo, porque é um fato que <b>este</b> serviço decidiu, num namespace que ele
 * publica. Prender a saída ao nome de um evento seria pedir que alguém se lembrasse de acrescentar uma
 * linha aqui para que uma decisão nova chegasse a quem já a espera, e "esqueci de listar" é falha
 * silenciosa — exatamente o que o resto desta integração gasta código para evitar.
 */
@ApplicationScoped
public class PostEventsOutbox {

    /**
     * A MESMA constante nas duas anotações, e não dois literais iguais. O nome precisa aparecer duas
     * vezes — o ArC não expõe o {@code @Channel} de um produtor —, e é a constante que torna isso uma
     * repetição em vez de uma oportunidade de divergir. O guarda de última instância está em
     * {@code OutboxRouting}, que confere o emitter produzido contra o do {@code ChannelRegistry}.
     */
    static final String CHANNEL = "post-events-out";

    @Produces
    @Singleton
    @AxonOutbox(channel = CHANNEL, namespaces = "posts")
    Emitter<AxonEventEnvelope> postEvents(@Channel(CHANNEL) Emitter<AxonEventEnvelope> channel) {
        return channel;
    }
}

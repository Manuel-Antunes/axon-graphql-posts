package dev.manuelantunes.axonposts.infrastructure.outbox;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import dev.manuelantunes.axonposts.infrastructure.messaging.AxonEventEnvelope;
import dev.manuelantunes.axonposts.infrastructure.messaging.AxonOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * O destino de saída desta aplicação: o ciclo de vida do Post, para quem quiser ouvi-lo.
 *
 * <h2>Uma declaração, dois fatos</h2>
 * {@code @AxonOutbox} diz <b>o que sai</b>; {@code @Channel} diz <b>para onde vai</b>. O nome do canal
 * aparece uma vez só — o roteamento o lê deste ponto de injeção, não de uma constante repetida.
 *
 * <h2>Por que a declaração está AQUI, e não na lib</h2>
 * Porque para onde um serviço publica é decisão dele. A lib tem o mecanismo inteiro — envelope,
 * encaminhamento, endereçamento, idempotência — e nenhum destino. É o mesmo argumento, invertido, que o
 * {@code ChannelEventIngestion} faz para os listeners de entrada: <b>quais fatias do fluxo esta máquina
 * troca com o mundo</b> é da aplicação.
 *
 * <h2>{@code @Singleton}, e não {@code @ApplicationScoped}</h2>
 * Porque o que este produtor devolve é o {@code Emitter} de verdade, e não um client proxy dele. Em
 * escopo normal o CDI embrulharia o emitter num proxy sem ganho nenhum — o objeto não tem estado a
 * isolar por requisição. Singleton também garante que o produtor roda <b>uma vez</b>, na primeira
 * publicação; é ali que o {@code @Channel} é resolvido, muito depois de o {@code AxonExtension.init} ter
 * passado, que é o que impede o {@code SRMSG00019} da partida.
 *
 * <h2>O que este outbox NÃO publica</h2>
 * Só {@code posts}. Antes todo evento saía — inclusive {@code users.*} e {@code tags.*}, que nenhuma
 * fila jamais vinculou: cópias que o exchange descartava em silêncio. Publicar o que ninguém ouve não é
 * inofensivo; é o que faz {@code list_bindings} deixar de descrever o sistema. Quando alguém precisar
 * dos eventos de usuário, é outro produtor destes, declarando {@code users} — e aí a topologia diz quem
 * os quis.
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

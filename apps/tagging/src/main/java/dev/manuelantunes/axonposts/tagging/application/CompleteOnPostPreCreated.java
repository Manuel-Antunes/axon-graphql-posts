package dev.manuelantunes.axonposts.tagging.application;

import java.util.concurrent.CompletableFuture;

import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.tagging.application.CompletePostWithDefaultTagCommand.CompletePostWithDefaultTag;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A porta de entrada deste serviço: um post nasceu sem tag, decida e complete.
 *
 * <h2>O evento é o de verdade, importado de {@code libs/posts-domain}</h2>
 * Não há record redeclarado aqui. Isso foi tentado — com o argumento de que "o contrato entre serviços
 * é o envelope no fio" — e é a saída errada num monorepo que já separa domínio em biblioteca: um evento
 * de domínio escrito duas vezes é a mesma regra em dois lugares, e o primeiro campo novo faz as duas
 * divergirem em silêncio. O módulo de domínio existe justamente para ser importado.
 * <p>
 * O que continua valendo da outra ideia: este serviço <b>não</b> depende de {@code libs/posts}, a
 * camada de aplicação. Ele importa o domínio (entidade, evento, value object, regra) e escreve o
 * próprio command. Herdar a aplicação traria o GraphQL, a projeção e todo command handler de lá — que o
 * Axon descobre em build time — para um serviço que não tem tabela nenhuma para eles.
 *
 * <h2>Por que este handler não sabe que existe um broker</h2>
 * Porque o evento não chega da fila: chega do <b>event store local</b>. O {@code ChannelEventInbox}
 * recebe a mensagem, grava no inbox e apenda o evento aqui; é o append que aciona o processor, como em
 * qualquer aplicação Axon sem mensageria. A consequência prática é grande — este handler tem token,
 * replay e a mesma unidade de trabalho que teria se o evento fosse local.
 *
 * <h2>Por que no {@code AFTER_COMMIT}</h2>
 * Este método roda durante o commit do append do {@code PostPreCreated}. Despachar ali dentro faria o
 * Axon tentar reidratar o Post de um stream que ainda não fechou — o mesmo poço em que a atribuição de
 * tag original já caiu, com {@code EntityNotFoundException}. E como o Axon <b>espera</b> o futuro do
 * after-commit, falha aqui falha o processamento: nack, e a mensagem volta pela política da fila. É o
 * que dá retentativa à saga sem uma linha de código de retentativa.
 */
@ApplicationScoped
public class CompleteOnPostPreCreated {

    private static final Logger log = LoggerFactory.getLogger(CompleteOnPostPreCreated.class);

    private final CommandGateway commandGateway;

    public CompleteOnPostPreCreated(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @EventHandler
    public void on(PostPreCreatedEvent event, ProcessingContext context) {
        log.debug("post {} de {} nasceu sem tag — completando", event.postId(), event.authorId());
        context.onAfterCommit(committed -> complete(event.postId()));
    }

    private CompletableFuture<Void> complete(PostId postId) {
        return commandGateway.send(new CompletePostWithDefaultTag(postId), Void.class)
                .thenRun(() -> {
                });
    }
}

package dev.manuelantunes.axonposts.application.post.saga;

import java.util.concurrent.CompletableFuture;

import dev.manuelantunes.axonposts.application.post.command.CompletePostCommand.CompletePost;
import dev.manuelantunes.axonposts.domain.post.event.PostPreCreatedEvent;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O serviço de tagueamento <b>dublado dentro do processo</b>, para quando não há broker nem vizinho.
 *
 * <h2>Isto é dublagem, e não um segundo caminho de produção</h2>
 * Em produção o passo de tagueamento é de {@code apps/tagging}, e esta classe <b>não existe</b> — o
 * {@code @IfBuildProperty} a remove do build. Ela existe porque a suíte desta aplicação não tem o
 * vizinho de pé, e sem ninguém decidindo a tag todo post ficaria pré-criado para sempre, na versão 1 e
 * sem tag. Dezenas de testes afirmam o contrário — e afirmam corretamente, porque o sistema completo faz
 * o post chegar à versão 2.
 *
 * <h2>Por que não deixar a suíte falar com o broker</h2>
 * Porque foi medido e não funciona: consistência eventual faz mensagem em voo <b>cruzar a fronteira do
 * teste</b>. A suíte isola cada método com {@code truncate}, então um post criado no teste N recebe a
 * tag depois de o teste N+1 ter limpado o banco. Medido: 54 eventos publicados, 36 entregues, 6
 * {@code CommandExecutionException} e sete testes estourando espera.
 * <p>
 * O caminho real — dois processos, um broker — é coberto por {@code docker/e2e/run.sh}, fora do
 * Surefire, onde não há ninguém com quem disputar isolamento.
 *
 * <h2>O que ela dubla, exatamente</h2>
 * O <b>momento</b> da decisão, e só — o corpo dela é uma linha. Qual é a tag padrão não é decisão de
 * ninguém aqui: nome e identidade vêm de {@link Tag#DEFAULT_NAME} e {@link Tag#DEFAULT_ID}, no domínio,
 * e a linha correspondente é semeada pela migration {@code V5}. A regra de como um post se completa é do
 * {@code Post}, a mesma classe nos dois caminhos.
 * <p>
 * É por isso que a dublagem não esconde defeito: ela e o serviço real chegam ao mesmo id porque a regra
 * é a mesma, não porque duas constantes foram mantidas iguais à mão.
 * <p>
 * Ela já criou a tag quando não a encontrava — {@code findByName} e, depois, {@code findById} seguido de
 * {@code CreateTag}. Era um "verifica-então-cria" com a corrida que esse padrão sempre tem, e o sintoma
 * aparecia em teste vizinho: {@code Tag já existe} numa conclusão e {@code Tag não encontrada} na
 * próxima. Semente no schema resolveu o problema em vez de proteger dele.
 */
@ApplicationScoped
@IfBuildProperty(name = "axonposts.saga.tagging-in-process", stringValue = "true")
public class InProcessTagAssignment {

    private static final Logger log = LoggerFactory.getLogger(InProcessTagAssignment.class);

    private final CommandGateway commandGateway;

    public InProcessTagAssignment(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    /**
     * Reage ao mesmo evento que atravessaria o broker, e no {@code AFTER_COMMIT} pela mesma razão que
     * ele: durante o commit do {@code CreatePost} o {@code PostPreCreated} ainda não é legível de volta
     * do event store, e o {@code CompletePost} falharia com {@code EntityNotFoundException}.
     * <p>
     * E é por o Axon <b>esperar</b> o futuro do after-commit que o {@code createPost}, em teste, já
     * responde na versão 2 — o que em produção é eventual.
     */
    @EventHandler
    public void on(PostPreCreatedEvent event, ProcessingContext context) {
        context.onAfterCommit(committed -> assignDefaultTag(event.postId()));
    }

    private CompletableFuture<Void> assignDefaultTag(PostId postId) {
        return commandGateway.send(new CompletePost(postId, Tag.DEFAULT_ID), Void.class)
                .thenRun(() -> log.debug("post {} completado em processo com a tag {}",
                        postId, Tag.DEFAULT_NAME));
    }
}

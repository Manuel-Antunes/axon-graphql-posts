package dev.manuelantunes.axonposts.domain.shared;

/**
 * Porta do <b>domínio</b> para disparar eventos: a saída pela qual uma decisão de domínio vira fato.
 * <p>
 * É o que permite {@code Post.create(...)} e {@code post.update(...)} <i>dispararem</i> o evento sem que
 * o domínio conheça o Axon. Quem implementa é a camada de aplicação
 * ({@code AppendingDomainEventPublisher}, sobre o {@code EventAppender} do Axon), e quem <i>ouve</i> o
 * evento também é a aplicação (os {@code @EventHandler} de {@code application.post.event}).
 * <p>
 * Interface funcional de propósito: em teste de domínio o duplo é uma lambda que só coleciona os eventos.
 */
@FunctionalInterface
public interface DomainEventPublisher {

    /**
     * Dispara o evento. Para o domínio, isso é o fim da decisão: o que acontece depois (append no event
     * store, projeção, subscription) é responsabilidade de quem implementa a porta.
     *
     * @param event o fato decidido pelo domínio
     */
    void raise(DomainEvent event);
}

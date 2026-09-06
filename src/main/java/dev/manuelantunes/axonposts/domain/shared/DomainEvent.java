package dev.manuelantunes.axonposts.domain.shared;

/**
 * Marcador dos <b>eventos de domínio</b>: fatos que o domínio decidiu que aconteceram.
 * <p>
 * Existe por dois motivos:
 * <ul>
 *   <li>dá um tipo ao contrato do {@link DomainEventPublisher} — o domínio só dispara fatos seus,
 *       nunca um objeto qualquer;</li>
 *   <li>separa, no nome, os <b>eventos de domínio</b> (aqui, em {@code domain.*.event}) dos
 *       <b>event handlers de aplicação</b> (em {@code application.*.event}), que são quem "ouve"
 *       o que o domínio disparou.</li>
 * </ul>
 * Um evento de domínio é imutável, no passado, e carrega o estado resultante do fato.
 */
public interface DomainEvent {
}

/**
 * <b>Event handlers da aplicação</b>: o lado que <i>ouve</i> o que o domínio disparou.
 * <p>
 * A separação que este pacote materializa:
 * <ul>
 *   <li>os <b>eventos de domínio</b> ({@code domain.post.event}) são fatos, e quem os dispara é a
 *       entidade {@code Post}, pela porta {@code DomainEventPublisher};</li>
 *   <li>as <b>reações</b> a esses fatos são da aplicação e moram aqui: uma classe por evento. Nenhuma
 *       delas é chamada pelo domínio; todas são chamadas pelo Axon.</li>
 * </ul>
 *
 * <h2>Hoje eles só notificam</h2>
 * Quem grava o read model é o command handler, junto com a decisão e dentro da mesma transação. Quando
 * um handler daqui roda, a view já está salva — então ele apenas a lê e emite para as subscriptions.
 * <p>
 * A consequência a ter em mente: o read model deixa de ser <i>derivado</i> do stream. Um replay dos
 * eventos não o reconstrói mais, porque estes handlers não escrevem nada. Voltar a projetar aqui (e
 * tirar o {@code save} do command) é o que devolve essa propriedade.
 *
 * <h2>{@code @Namespace} no pacote</h2>
 * O {@code @Namespace} abaixo vale para <b>todas</b> as classes do pacote (o Axon procura a anotação no
 * tipo, nas classes envolventes, no pacote e no módulo, nessa ordem). É o que casa estes handlers com o
 * {@code EventProcessorDefinition.subscribingMatching(...)} do {@code AxonConfig}: em vez de repetir a
 * anotação em cada handler novo, basta pôr a classe neste pacote.
 * <p>
 * Em modo <b>subscribing</b> os handlers executam na mesma thread e no mesmo {@code ProcessingContext}
 * (e transação) do command — é isso que garante que o {@code save} do command já aconteceu quando o
 * handler roda, e que o emit sai uma única vez, depois do commit no SQLite.
 */
@Namespace(PostProjection.PROCESSOR)
package dev.manuelantunes.axonposts.application.post.event;

import org.axonframework.messaging.core.annotation.Namespace;

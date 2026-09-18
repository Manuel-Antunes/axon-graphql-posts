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
 * Quem grava o read model é o command, junto com a decisão e dentro da mesma transação. Quando
 * um handler daqui roda, a view já está salva — então ele apenas a lê e emite para as subscriptions.
 * <p>
 * A consequência a ter em mente: o read model deixa de ser <i>derivado</i> do stream. Um replay dos
 * eventos não o reconstrói mais, porque estes handlers não escrevem nada. Voltar a projetar aqui (e
 * tirar o {@code save} do command) é o que devolve essa propriedade.
 *
 * <h2>O nome deste pacote é configuração</h2>
 * Estes handlers rodam num processor <b>subscribing</b>, e quem diz isso é uma linha de
 * {@code application.properties}:
 * <pre>{@code
 * quarkus.axon.subscribingprocessor.namespaces=dev.manuelantunes.axonposts.application.post.event
 * }</pre>
 * O valor é o <b>nome deste pacote</b>, e não um apelido. A extensão de Quarkus agrupa os event handlers
 * por {@code @Namespace} lido <i>da classe</i>, caindo no nome do pacote quando não há anotação — então
 * é o pacote que identifica o grupo. A propriedade continua valendo a regra de sempre: <b>handler novo
 * aqui dentro entra no processor sem tocar em configuração</b>. Mover a classe para outro pacote, não.
 * <p>
 * Em modo subscribing os handlers executam na mesma thread e no mesmo {@code ProcessingContext} (e
 * transação) do command — é isso que garante que o {@code save} do command já aconteceu quando o handler
 * roda, e que o emit sai uma única vez, depois do commit no Postgres. Sem a propriedade, a extensão não
 * cria processor subscribing nenhum e estes handlers caem num pooled, que é assíncrono: o
 * {@code createPost} passaria a responder <b>antes</b> da tag padrão. Quem pega isso é o
 * {@code PostLifecycleE2ETest.aNewPostArrivesAlreadyTaggedAtVersionTwo}, que afirma a versão 2 na
 * resposta da mutation.
 */
package dev.manuelantunes.axonposts.application.post.event;

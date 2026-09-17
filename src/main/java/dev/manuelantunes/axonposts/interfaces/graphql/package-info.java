/**
 * <b>A camada de apresentação.</b> Tudo o que só existe porque o protocolo é GraphQL, e nada além disso.
 *
 * <h2>Os seis pacotes, e o que separa um do outro</h2>
 * <ul>
 *   <li>{@code api} — os {@code @GraphQLApi}. Traduzem a operação numa mensagem do Axon e devolvem o
 *       resultado. Sem regra, sem banco, sem domínio;</li>
 *   <li>{@code dto} — os {@code *Input}: a forma que o dado tem <i>no protocolo</i>, com as constraints de
 *       Bean Validation. Não são commands nem value objects, de propósito — um input pode chegar
 *       inválido, e é isso que ele existe para descrever;</li>
 *   <li>{@code mapper} — {@code input → command}, em MapStruct. É o ponto em que o dado deixa de ser
 *       "o que o cliente mandou" e vira "o que a aplicação executa";</li>
 *   <li>{@code relay} — a cursor connection inteira: a mecânica genérica e as subclasses concretas que
 *       dão os nomes da convenção;</li>
 *   <li>{@code error} — como cada falha aparece para o cliente.</li>
 * </ul>
 *
 * <h2>Threading: o {@code Supplier} não é estilo</h2>
 * O {@code SimpleCommandBus} e o {@code SimpleQueryBus} executam o handler <b>na thread que despacha</b>, e
 * lá dentro tem JPA bloqueante. Um resolver que devolve {@code Uni} roda no event-loop do Vert.x, e
 * bloquear um event-loop trava todas as requisições que ele serve — não só a atual. É a mesma armadilha
 * que o projeto Spring resolve com {@code subscribeOn(boundedElastic())}, e a mesma resposta:
 * <pre>{@code
 * return Uni.createFrom()
 *         .completionStage(() -> queryGateway.query(…))   // Supplier, não o future pronto
 *         .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
 * }</pre>
 * A sobrecarga que recebe o {@code CompletableFuture} <b>pronto</b> exigiria que ele já existisse — ou
 * seja, o {@code gateway.send(...)} teria rodado na thread que montou o {@code Uni}, que é justamente o
 * event-loop. Com o {@code Supplier}, a chamada só acontece na assinatura, e o
 * {@code runSubscriptionOn} garante que a assinatura aconteça no worker. O detalhe é de uma palavra e é a
 * diferença entre offload de verdade e offload aparente.
 * <p>
 * Isto já foi um utilitário ({@code support/Dispatch}). Virou três linhas em cada resolver porque é o que
 * são: duas chamadas do Mutiny, visíveis onde acontecem, como no WebFlux.
 *
 * <h2>O que NÃO mora aqui, e é a parte que importa</h2>
 * Os {@code *View}. Eles são o resultado das queries — atravessam o query bus, são emitidos pelas
 * subscriptions, viajam dentro de {@code PostPage} — então pertencem a {@code application.*.view}, e esta
 * camada apenas os <b>consome</b>. É o que mantém a seta apontando para dentro: a apresentação conhece a
 * aplicação, e a aplicação não conhece a apresentação.
 * <p>
 * O preço dessa escolha está anotado onde ele existe: os {@code *View} carregam {@code @Name}/{@code @Id}
 * do MicroProfile GraphQL, que é a aplicação sabendo de protocolo. A alternativa — uma view da aplicação e
 * outra do schema, com um mapper entre elas — dobra os tipos sem mudar nenhuma decisão.
 */
package dev.manuelantunes.axonposts.interfaces.graphql;

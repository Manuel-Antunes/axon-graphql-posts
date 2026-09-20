/**
 * <b>As projeções</b>: o que precisa ser <i>gravado</i> quando um evento chega sem um command local
 * atrás dele.
 *
 * <h2>Por que elas não moram em {@code application.post.event}</h2>
 * Porque o pacote é o que escolhe o processor, e o processor é o que decide a <b>entrega</b>. As duas
 * reações a um mesmo evento querem entregas opostas:
 *
 * <table border="1">
 *   <caption>o que cada reação precisa do processor</caption>
 *   <tr><th></th><th>projetar (aqui)</th><th>notificar ({@code ..post.event})</th></tr>
 *   <tr><td>quantas vezes</td><td>uma</td><td>em todo container</td></tr>
 *   <tr><td>onde</td><td>na transação do append</td><td>fora dela</td></tr>
 *   <tr><td>se ninguém ouvir</td><td>grava assim mesmo</td><td>não há o que fazer</td></tr>
 *   <tr><td>se falhar</td><td>aborta o append</td><td>avisa e segue</td></tr>
 *   <tr><td>processor</td><td>subscribing</td><td>pooled streaming, token em memória, HEAD</td></tr>
 * </table>
 *
 * Uma classe só, num processor só, teria de escolher uma das duas colunas para as duas
 * responsabilidades — e foi o que aconteceu enquanto elas estavam juntas: escolhida a de baixo, a
 * subscription não funcionava com mais de um container; escolhida a de cima, a materialização de um
 * evento vindo de outro serviço deixaria de commitar com o append e poderia se perder num container
 * congelado entre invocações.
 *
 * <h2>O nome deste pacote também é configuração</h2>
 * <pre>{@code
 * quarkus.axon.subscribingprocessor.namespaces=...application.post.projection
 * quarkus.axon.subscribingprocessor.name=post-projection
 * }</pre>
 * Subscribing é o que faz o handler rodar na mesma thread e no mesmo {@code ProcessingContext} (e
 * transação) de quem apendou o evento. É daí que vem a garantia que o resto do sistema usa sem saber:
 * <b>quem enxerga o evento no store enxerga a linha</b> — e é por isso que o handler que notifica pode
 * ler o banco sem correr atrás da escrita.
 * <p>
 * <b>Pacote novo de projeção = mais um item na propriedade.</b> Quem fica de fora não dá erro: cai num
 * pooled anônimo, assíncrono, e a garantia acima deixa de valer em silêncio. Quem pega isso é o
 * {@code AxonWiringTest.everyPackageWithAnEventHandlerIsAssignedToAProcessor}.
 */
package dev.manuelantunes.axonposts.application.post.projection;

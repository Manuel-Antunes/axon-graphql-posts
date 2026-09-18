/**
 * A saga de criação de post, <b>coreografada</b>.
 *
 * <h2>Coreografia, não orquestração</h2>
 * Não existe aqui um objeto "saga" que conheça os passos e mande em cada um. Existem dois serviços que
 * reagem a eventos e publicam eventos, e a sequência <i>emerge</i> disso:
 * <pre>
 * esta aplicação             fila / routing key                serviço de tagueamento
 * ─────────────────────────────────────────────────────────────────────────────────────
 * PostPreCreated      ──▶   posts.PostPreCreated.&lt;postId&gt;   ──▶   decide a tag
 *                                                                        │
 * CompletePost        ◀──   tagging.DefaultTagAssigned.&lt;postId&gt; ◀──  DefaultTagAssigned
 *      │
 * PostCreated  ──▶  onPostCreated (o post pronto, versão 2)
 * </pre>
 * Nenhum dos dois nomeia o outro: cada um declara a quais routing keys se vincula. É o que permite
 * trocar, duplicar ou substituir o serviço de tagueamento sem recompilar esta aplicação.
 *
 * <h2>As três coisas que uma coreografia precisa ter, e onde elas estão</h2>
 * <ol>
 *   <li><b>não duplicar execução.</b> Três guardas independentes: a marca de origem descarta o eco do
 *       próprio serviço ({@code ChannelMetadata}); o inbox descarta reentrega, no mesmo commit do
 *       append ({@code MessageInbox}); e o agregado descarta a decisão repetida
 *       ({@code CompletePostCommand} + {@code Post.isComplete()}). A última é a que sobrevive a um
 *       inbox limpo, e é por isso que ela existe mesmo havendo as outras duas;</li>
 *   <li><b>não perder contexto.</b> O evento carrega o que o próximo passo precisa — o
 *       {@code PostPreCreated} vai com título, conteúdo e autor; o {@code DefaultTagAssigned} volta com
 *       id E nome da tag. Nenhum passo precisa consultar o serviço anterior, e é isso que impede a
 *       coreografia de virar uma cadeia de chamadas síncronas disfarçada;</li>
 *   <li><b>não perder o passo no meio.</b> Cada serviço apenda no próprio event store <b>antes</b> de
 *       publicar, e o que chega do broker é apendado antes de ser processado. O estado intermediário da
 *       saga é durável nos dois lados, então um restart retoma em vez de esquecer.</li>
 * </ol>
 *
 * <h2>O que ela custa</h2>
 * O {@code createPost} responde na <b>versão 1, sem tag</b>: a tag chega depois. Era imediato quando o
 * passo rodava na mesma transação. Quem quiser o post pronto observa {@code onPostCreated}, que agora
 * significa "está completo" — e não mais "nasceu".
 */
package dev.manuelantunes.axonposts.application.post.saga;

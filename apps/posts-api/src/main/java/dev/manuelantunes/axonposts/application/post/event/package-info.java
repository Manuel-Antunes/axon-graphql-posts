/**
 * <b>Event handlers da aplicação</b>: o lado que <i>ouve</i> o que o domínio disparou e <b>avisa quem
 * está assinando</b>. Uma classe por evento, e cada uma faz uma coisa só.
 * <p>
 * A separação que este pacote materializa:
 * <ul>
 *   <li>os <b>eventos de domínio</b> ({@code domain.post.event}) são fatos, e quem os dispara é a
 *       entidade {@code Post}, pela porta {@code DomainEventPublisher};</li>
 *   <li>as <b>reações</b> a esses fatos são da aplicação e moram aqui. Nenhuma delas é chamada pelo
 *       domínio; todas são chamadas pelo Axon.</li>
 * </ul>
 *
 * <h2>Eles só notificam — e quem grava mora ao lado</h2>
 * Nenhum handler daqui escreve no banco. O read model é gravado pelo command, junto com a decisão e
 * dentro da mesma transação; o que chega de outro serviço sem command local é materializado em
 * {@code application.post.projection}, que roda noutro processor de propósito.
 * <p>
 * A consequência a ter em mente: o read model não é <i>derivado</i> do stream. Um replay dos eventos não
 * o reconstrói, porque estes handlers não escrevem nada. Voltar a projetar aqui (e tirar o {@code save}
 * do command) é o que devolve essa propriedade — e seria uma mudança de processor, não de código.
 *
 * <h2>O NOME DESTE PACOTE É A SOLUÇÃO INTEIRA</h2>
 * Estes handlers rodam num {@code PooledStreamingEventProcessor}, e quem diz isso são três linhas de
 * {@code application.properties} — nenhuma delas em código:
 * <pre>{@code
 * quarkus.axon.pooledprocessor.post-subscriptions.namespaces=...application.post.event
 * quarkus.axon.pooledprocessor.post-subscriptions.use-in-memory-token-store=true
 * quarkus.axon.pooledprocessor.post-subscriptions.initial-position.at-head-or-tail=HEAD
 * }</pre>
 * O valor é o <b>nome deste pacote</b>, e não um apelido: a extensão de Quarkus agrupa os event handlers
 * por {@code @Namespace} lido <i>da classe</i>, caindo no nome do pacote quando não há anotação. Handler
 * novo aqui dentro entra no processor sem tocar em configuração; mover a classe para outro pacote, não.
 * <p>
 * Cada palavra das três linhas decide uma coisa, e juntas elas são a razão de uma subscription funcionar
 * com mais de uma instância:
 * <ul>
 *   <li><b>streaming</b> — o processor lê o <b>event store</b>, que é compartilhado. Ele enxerga o que
 *       qualquer processo apendou, não só este. Um processor {@code subscribing} dispararia apenas no
 *       processo que apendou, e em Lambda esse nunca é o que segura a conexão SSE: quem a segura está
 *       numa invocação que não retornou, então a mutation seguinte cai obrigatoriamente noutro
 *       container;</li>
 *   <li><b>token store em memória</b> — cada container tem o próprio cursor. O default é o token store
 *       JPA, que existe para garantir que um evento seja processado UMA vez no cluster: um container
 *       reclamaria o segmento e os outros ficariam sem ver nada. Aqui a regra é a inversa, porque cada
 *       container tem os próprios assinantes na memória;</li>
 *   <li><b>HEAD</b> — quem sobe agora quer o que vier a partir de agora. Com TAIL, cada deploy
 *       reemitiria o histórico inteiro para assinantes que acabaram de chegar.</li>
 * </ul>
 * Quem faz a leitura, o cursor, o lote e o retry é o Axon, com o {@code EventStorageEngine} e o
 * {@code TokenStore} que a aplicação já configura. Houve uma tentativa de escrever uma "porta de leitura
 * de eventos" aqui, com consulta própria, e ela era reimplementar o que o framework já faz.
 *
 * <h2>O que se paga por isso</h2>
 * A notificação é <b>assíncrona</b>: ela sai quando o processor alcança o evento, não no commit. Para um
 * aviso isso é o certo — ele não pertence à transação de escrita —, e o que a resposta da mutation
 * afirma continua sendo garantido pelo processor subscribing do pacote de projeção. Quem confere a
 * versão 2 na resposta do {@code createPost} é o
 * {@code PostLifecycleE2ETest.aNewPostArrivesAlreadyTaggedAtVersionTwo}.
 * <p>
 * E como o processor tem retry, um handler daqui <b>não lança</b> quando a linha não está no banco:
 * lançar faria o mesmo evento voltar para sempre, travando o cursor e com ele todas as notificações
 * seguintes. Quem estoura nesse caso é a projeção, que roda na transação do append e pode abortá-la.
 */
package dev.manuelantunes.axonposts.application.post.event;

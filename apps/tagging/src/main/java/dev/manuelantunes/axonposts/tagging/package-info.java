/**
 * O serviço de <b>tagueamento</b>: um microserviço Axon completo, sem uma linha de HTTP.
 *
 * <h2>O que ele é</h2>
 * Um passo da saga de criação de post, e nada mais. Ele reage a {@code posts.PostPreCreated}, decide
 * qual é a primeira tag e publica {@code posts.PostCreated}. Tem event store próprio (banco próprio) e
 * filas próprias — e nenhum agregado seu: ele trabalha com o {@code Post} de verdade, importado de
 * {@code libs/posts}.
 *
 * <h2>O que ele NÃO tem, e por quê</h2>
 * <ul>
 *   <li><b>porta HTTP.</b> Ele não é consultado por ninguém: o que o aciona é mensagem, e o que ele
 *       produz é mensagem. Uma porta existia na versão anterior deste módulo — e existia só para o
 *       teste poder perguntar "o que você recebeu?", o que é o próprio teste dizendo que estava olhando
 *       para a coisa errada. O que prova que a saga funciona é o {@code onPostCreated} do outro lado
 *       chegar completo, não este serviço confessar;</li>
 *   <li><b>read model.</b> Nenhuma consulta, nenhuma projeção, nenhuma entidade JPA. O único uso de
 *       Postgres aqui é o que o Axon faz: o stream e o inbox;</li>
 *   <li><b>agregado próprio.</b> Houve um {@code TagAssignment}, e era invenção: a pergunta que
 *       importa é "este post já está completo?", e o {@code Post} responde. Entidade para guardar o que
 *       outra entidade já sabe é estado duplicado, e estado duplicado diverge;</li>
 *   <li><b>evento redeclarado.</b> Houve uma versão com records próprios para os eventos trocados, com
 *       o argumento de que "o contrato entre serviços é o envelope no fio". O argumento não é falso e a
 *       conclusão era errada num monorepo que já separa domínio em biblioteca: evento de domínio escrito
 *       duas vezes é a mesma regra em dois lugares, e o primeiro campo novo faz as duas divergirem em
 *       silêncio.</li>
 * </ul>
 *
 * <h2>O que ele importa, então</h2>
 * {@code libs/posts} — o DOMÍNIO e a INFRAESTRUTURA de posts: o {@code Post}, os eventos, as regras
 * (inclusive qual é a tag padrão, em {@code Tag.DEFAULT_ID}) e os repositórios. Mais
 * {@code libs/axon-channels} (o maquinário de integração, que não conhece domínio nem broker) e
 * {@code libs/platform}.
 * <p>
 * O que ele <b>não</b> importa é {@code apps/posts-api} — a camada de APLICAÇÃO do outro serviço. Ela
 * traria o GraphQL, a projeção e todo command handler de lá, que o Axon descobre em build time e ligaria
 * contra tabelas que este serviço não tem. A fronteira útil é domínio/aplicação, não serviço/serviço:
 * domínio é regra, e regra se compartilha; aplicação é fluxo, e fluxo é de quem o executa.
 *
 * <h2>Como ele encontra o outro serviço</h2>
 * Não encontra. Ele se vincula a {@code posts.PostPreCreated.*} e publica em {@code tagging.*}; quem
 * reage é problema de quem reage. Nem o nome, nem o endereço, nem a versão do serviço de posts aparecem
 * em lugar nenhum deste módulo — e é isso que "coreografia" significa na prática.
 */
package dev.manuelantunes.axonposts.tagging;

package dev.manuelantunes.axonposts.application.post.projection;

import java.util.List;
import java.util.Optional;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.event.TagCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Materializa o read model quando o {@code PostCreated} veio de <b>outro serviço</b>.
 *
 * <h2>Por que este handler ESCREVE, contra a regra de camada 3</h2>
 * A regra do projeto é "o command decide e salva; o evento notifica e orquestra", e ela vale porque
 * <b>há</b> um command atrás de cada evento. Um evento que chega pelo broker não tem: quem decidiu foi
 * outro processo, contra o event store dele. Deste lado o evento é apendado pela ingestão e mais nada
 * acontece — a linha do read model continuaria na versão 1, sem tag, para sempre.
 * <p>
 * Então a regra ganha uma exceção, e ela é estreita: <b>evento sem command local materializa a
 * projeção</b>. É o papel clássico de uma projeção em CQRS; o que era incomum aqui era o command
 * acumular esse papel, o que só funcionava enquanto tudo era local.
 *
 * <h2>Por que ele mora num pacote separado dos {@code *EventHandler}</h2>
 * Porque projetar e notificar precisam de <b>entregas diferentes</b>, e quem decide entrega é o
 * processor — que é escolhido pelo pacote. Este pacote é <b>subscribing</b>: roda na thread e na
 * transação de quem apendou o evento, uma vez, e o que ele grava commita junto com o append. É o que
 * torna a materialização durável: se ela falhar, o append falha com ela.
 * <p>
 * Notificar quer o oposto — <b>todo</b> container, e nada a recuperar se ninguém estiver ouvindo. Está
 * em {@code application.post.event}, num processor streaming. Ver o {@code package-info} de lá.
 * <p>
 * Isto <b>não</b> é uma lista de coisas a fazer quando um evento novo chega: as duas classes são
 * {@code @EventHandler} comuns, e a única diferença entre elas é o pacote. Quem lê o store, guarda o
 * cursor e trata falha é o Axon, nos dois casos.
 *
 * <h2>Como ele sabe que não houve command local</h2>
 * Pela versão, e não pela metadata da mensagem. Se a linha já está na versão do evento, alguém a
 * salvou — foi o {@code CompletePostCommand}, no mesmo commit. Se está atrás, não foi.
 * <p>
 * Derivar em vez de perguntar tem duas vantagens que valem a escolha: não depende de a marca de origem
 * ter sobrevivido ao caminho, e <b>conserta sozinho</b> qualquer divergência entre stream e projeção,
 * de onde quer que ela tenha vindo.
 *
 * <h2>Por que reconciliar ANTES de notificar — e por que isso agora é de graça</h2>
 * Porque {@code PostView} não carrega as tags: o campo {@code tags} do GraphQL é resolvido por um
 * resolvedor de lote que <b>lê o banco</b> no momento de serializar a resposta. Notificar antes de
 * escrever entregaria ao assinante um post cujas tags ele iria buscar e não encontrar.
 * <p>
 * Antes isso obrigava as duas coisas a estarem no mesmo método, nesta ordem. Com a notificação num
 * processor streaming a ordem deixou de precisar de cuidado: ela só enxerga o evento <b>depois</b> do
 * commit que gravou esta linha. A garantia passou a vir da transação, e não de uma sequência de
 * chamadas que alguém pode inverter.
 */
@ApplicationScoped
public class PostCreatedProjection {

    private static final Logger log = LoggerFactory.getLogger(PostCreatedProjection.class);

    private final PostRepository posts;
    private final TagRepository tags;

    public PostCreatedProjection(PostRepository posts, TagRepository tags) {
        this.posts = posts;
        this.tags = tags;
    }

    @EventHandler
    public void on(PostCreatedEvent event) {
        Post post = posts.findById(event.postId())
                .orElseThrow(() -> new IllegalStateException(
                        "PostCreated de um Post que não está no banco: " + event.postId()
                                + " — só quem criou o post materializa a linha dele"));

        if (post.version().value() < event.version()) {
            materialize(post, event);
        }
    }

    /**
     * Aplica o evento à linha, pelo <b>mesmo caminho do replay</b>: {@code post.on(event)} é o
     * {@code @EventSourcingHandler} do agregado, e ele é idempotente por contrato (todo campo do evento
     * é valor absoluto). Não há um segundo lugar descrevendo o que {@code PostCreated} faz com o estado.
     */
    private void materialize(Post post, PostCreatedEvent event) {
        log.info("post {} veio completo de outro serviço (v{} contra v{} no banco) — materializando",
                event.postId(), event.version(), post.version());

        List<Tag> managed = event.tags().stream()
                .map(assigned -> materializeTag(assigned, event))
                .toList();

        post.materializeCompletion(event, managed);
        posts.save(post);
    }

    /**
     * Cria a linha da tag se ela não existir aqui.
     *
     * <h3>Por que o construtor do {@code TagCreatedEvent}, e não {@code Tag.create}</h3>
     * Porque {@code Tag.create} <b>dispara</b> o evento — e o fato já aconteceu, no serviço que o
     * decidiu. O que se quer aqui é projetar um fato conhecido, e o caminho que o domínio oferece para
     * isso é o {@code @EntityCreator}: a mesma construção que o replay usa.
     * <p>
     * A consequência a conhecer: esta Tag tem <b>linha e não tem stream</b> nesta aplicação. Está certo
     * — o agregado dela vive no serviço que a criou, e daqui ela é read model. Um {@code CreateTag}
     * local criaria um agregado concorrente com o mesmo id.
     */
    private Tag materializeTag(PostCreatedEvent.AssignedTag assigned, PostCreatedEvent event) {
        TagId tagId = TagId.of(assigned.tagId());
        Optional<Tag> existing = tags.findById(tagId);
        if (existing.isPresent()) {
            return existing.get();
        }

        log.debug("a tag {} ({}) não existe neste serviço — projetando a linha", assigned.name(), tagId);
        tags.saveIfAbsent(new Tag(new TagCreatedEvent(tagId, assigned.name(), event.occurredAt())));

        /*
         * Relê depois de gravar, e a releitura é o ponto: `save` é um `merge`, que devolve a instância
         * GERENCIADA — e é ela que precisa entrar na lista de tags do post. Passar a instância recém
         * construída faria o Hibernate ver um objeto destacado com o mesmo id e tentar inserir a tag
         * outra vez. Ver `Post.materializeCompletion`.
         */
        return tags.findById(tagId).orElseThrow(() -> new IllegalStateException(
                "a tag " + tagId + " foi gravada e não foi encontrada em seguida"));
    }
}

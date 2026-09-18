package dev.manuelantunes.axonposts.application.post.event;

import dev.manuelantunes.axonposts.application.post.subscription.OnPostCreatedSubscription.OnPostCreated;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.event.TagCreatedEvent;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Emite para {@code onPostCreated} quando um post fica <b>completo</b> — e materializa o read model
 * quando o evento veio de outro serviço.
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
 * <h2>Como ele sabe que não houve command local</h2>
 * Pela versão, e não pela metadata da mensagem. Se a linha já está na versão do evento, alguém a
 * salvou — foi o {@code CompletePostCommand}, no mesmo commit. Se está atrás, não foi.
 * <p>
 * Derivar em vez de perguntar tem duas vantagens que valem a escolha: não depende de a marca de origem
 * ter sobrevivido ao caminho, e <b>conserta sozinho</b> qualquer divergência entre stream e projeção,
 * de onde quer que ela tenha vindo.
 *
 * <h2>Por que reconciliar ANTES de emitir</h2>
 * Porque {@code PostView} não carrega as tags: o campo {@code tags} do GraphQL é resolvido por um
 * resolvedor de lote que <b>lê o banco</b> no momento de serializar a resposta. Emitir antes de
 * escrever entregaria ao assinante um post cujas tags ele iria buscar e não encontrar — e o sintoma
 * seria uma subscription devolvendo lista vazia de forma intermitente, que é o pior tipo de defeito
 * para depurar.
 * <p>
 * É também por isso que a reconciliação não vai num {@code onAfterCommit} nem num command despachado
 * daqui: as duas coisas aconteceriam depois da emissão.
 *
 * <h2>Este handler NÃO espera o commit, e não é descuido</h2>
 * Ele emite a linha que lhe cabe e acabou. <b>Quando</b> aquele update chega ao assinante — depois do
 * commit, e fora da thread que commitou — é decisão do {@code QueryBus} decorado em
 * {@code libs/platform}: {@code SubscriptionUpdatesAfterCommit}. Havia aqui uma sincronização JTA
 * fazendo isso à mão, e ela era infraestrutura na camada de aplicação, repetida em cada handler que
 * emitisse — com dois defeitos silenciosos que só o caminho entre processos revelava. Estão descritos lá.
 */
@ApplicationScoped
public class PostCreatedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PostCreatedEventHandler.class);

    private final PostRepository posts;
    private final TagRepository tags;
    private final PostViewMapper viewMapper;

    public PostCreatedEventHandler(PostRepository posts, TagRepository tags, PostViewMapper viewMapper) {
        this.posts = posts;
        this.tags = tags;
        this.viewMapper = viewMapper;
    }

    @EventHandler
    public void on(PostCreatedEvent event, QueryUpdateEmitter emitter) {
        Post post = posts.findById(event.postId())
                .orElseThrow(() -> new IllegalStateException(
                        "PostCreated de um Post que não está no banco: " + event.postId()
                                + " — só quem criou o post materializa a linha dele"));

        if (post.version().value() < event.version()) {
            materialize(post, event);
        }

        PostView view = viewMapper.toView(post);
        log.debug("PostCreated {} (v{}) de {} → emitindo para onPostCreated", view.id(), view.version(),
                view.authorId());
        emitter.emit(OnPostCreated.class, subscription -> subscription.matches(view.authorId()), view);
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

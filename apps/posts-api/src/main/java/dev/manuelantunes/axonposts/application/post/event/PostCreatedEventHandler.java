package dev.manuelantunes.axonposts.application.post.event;

import java.util.Optional;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.manuelantunes.axonposts.application.post.subscription.OnPostCreatedSubscription.OnPostCreated;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Handler de <b>um</b> evento de domínio: {@link PostCreatedEvent}. Faz uma coisa só — notificar quem
 * estiver ouvindo {@code onPostCreated}.
 *
 * <h2>Só notificar é a estrutura toda, e ela é a mesma da versão Spring</h2>
 * O {@link QueryUpdateEmitter} é injetado <b>por parâmetro</b> (Axon 5) e já vem ligado ao
 * {@code ProcessingContext} do evento: o emit sai só depois do commit. Não há registro em lugar nenhum,
 * não há porta de leitura de eventos, não há consulta escrita à mão. Quem lê a tabela, guarda o cursor,
 * faz o lote e trata a falha é o Axon — ver o {@code package-info} deste pacote, que é onde a única
 * decisão desta solução está escrita.
 *
 * <h2>Quem materializa a linha NÃO é este handler</h2>
 * Um {@code PostCreated} que chega de outro serviço precisa virar linha no read model, e isso é
 * trabalho de <b>projeção</b>: tem de acontecer uma vez, na transação do append, ou o post fica na
 * versão 1 para sempre. Está em {@code application.post.projection.PostCreatedProjection}, que roda no
 * processor subscribing por essa razão exata.
 * <p>
 * Os dois pacotes existem porque as duas reações precisam de <b>entregas diferentes</b>, e é o processor
 * que decide isso: projetar é uma vez e durável; avisar é em todo container e descartável. Juntar as
 * duas numa classe obrigaria a escolher uma das duas entregas para as duas responsabilidades.
 *
 * <h2>Por que a view vem do BANCO, e não do payload — ao contrário da versão Spring</h2>
 * Lá a view era montada do evento para não depender da ordem entre dois handlers do mesmo processor.
 * Aqui não há essa corrida: quando este handler roda, o append e a projeção já commitaram <b>juntos</b>
 * — quem enxerga o evento no store enxerga a linha. E ler o banco é o que faz o assinante receber
 * exatamente o que a query {@code post} devolveria, com as tags que o resolvedor de lote vai buscar
 * logo em seguida. Montar do payload publicaria um post sem as tags que ele já tem.
 */
@ApplicationScoped
public class PostCreatedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PostCreatedEventHandler.class);

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public PostCreatedEventHandler(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @EventHandler
    public void on(PostCreatedEvent event, QueryUpdateEmitter emitter) {
        Optional<PostView> view = posts.findById(event.postId()).map(viewMapper::toView);

        if (view.isEmpty()) {
            /*
             * Avisa e sai, em vez de lançar — e a diferença é de PROCESSOR, não de rigor. Este handler
             * roda num pooled streaming, que tem retry: uma exceção faria o mesmo evento voltar para
             * sempre, travando o cursor e com ele TODAS as notificações seguintes. Quem estoura quando
             * a linha falta é a projeção, que roda na transação do append e pode abortá-la.
             * Um aviso não entregue é uma perda pequena; um processor parado é o recurso inteiro fora.
             */
            log.warn("PostCreated de {} sem linha na projeção — nada a notificar."
                    + " O post foi apagado, ou a projeção ainda não o materializou.", event.postId());
            return;
        }

        PostView post = view.get();
        log.debug("PostCreated {} (v{}) de {} → emitindo para onPostCreated",
                post.id(), post.version(), post.authorId());

        // cada assinante decide pelo próprio tópico: sem authorId recebe tudo, com authorId só o dele
        emitter.emit(OnPostCreated.class, subscription -> subscription.matches(post.authorId()), post);
    }
}

package dev.manuelantunes.axonposts.application.post.command;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.exception.PostAlreadyExistsException;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.exception.TagNotFoundException;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static dev.manuelantunes.axonposts.infrastructure.axon.AppendingDomainEventPublisher.appendingTo;

/**
 * O command <b>CreatePost</b>: a mensagem {@link CreatePost} e o que acontece quando ela chega, num
 * arquivo só. A classe leva o nome do command porque é isso que ela é — a decisão inteira de criar um
 * Post; o record aninhado é só o formato da mensagem que a dispara, e por isso se chama apenas
 * {@code CreatePost}. Quem despacha escreve {@code new CreatePostCommand.CreatePost(...)}.
 *
 * <h2>Criar e salvar</h2>
 * O {@link #handle} faz as duas coisas: pede ao domínio que crie o Post ({@code Post.create}, que valida,
 * dispara o {@code PostCreatedEvent} e devolve a entidade pronta) e <b>grava</b> o resultado no read
 * model. Como isso acontece dentro do {@code ProcessingContext} do command, o append do evento e a
 * escrita no Postgres commitam juntos: ou os dois valem, ou nenhum.
 * <p>
 * O event handler correspondente não projeta nada — quando ele roda, a view já está salva; ele só emite
 * para as subscriptions.
 *
 * <h2>O autor não é carregado aqui — e o motivo NÃO é economizar consulta</h2>
 * Este handler lia o agregado {@code User} para confirmar que o {@code authorId} existia e era de um
 * autor. A leitura saiu, e vale registrar o que a medição mostrou para ninguém reintroduzi-la esperando o
 * ganho errado: <b>o custo é idêntico</b>. Com e sem o {@code findById}, um {@code createPost} gasta 11
 * statements — o autor já está no contexto de persistência quando o handler roda, então a consulta batia
 * no cache de primeiro nível e nunca chegava ao banco.
 * <p>
 * O que a remoção compra é <b>dependência</b>, não desempenho:
 * <ul>
 *   <li><b>um agregado deixa de consultar outro</b>: a fronteira entre {@code Post} e {@code User} existe
 *       para que a decisão de um não dependa do estado carregado do outro. O {@code Post} sempre
 *       referenciou o autor por identidade; agora o command também;</li>
 *   <li><b>o trabalho para de ser repetido</b>: o controller já fez {@code requireAuthor()} — carregando o
 *       usuário e conferindo o tipo — para poder montar esta mensagem.</li>
 * </ul>
 *
 * <h3>Quem recusa agora</h3>
 * O próprio Hibernate, ao resolver a associação no {@code merge}: um {@code authorId} que não tem linha em
 * {@code authors} vira {@code EntityNotFoundException} antes de o INSERT sair. A chave estrangeira
 * {@code fk_posts_author} continua atrás disso como garantia final — ela aponta para {@code authors} e não
 * para {@code users}, então "não existe" e "é leitor" são a mesma recusa.
 * <p>
 * O {@code DataIntegrityTranslator} transforma as duas em {@code NotAnAuthorException}. Perde-se a
 * distinção entre os dois casos, o que é um <b>ganho</b>: a versão anterior devolvia
 * {@code UserNotFoundException} e com isso confirmava quais ids existem.
 * <p>
 * O preço, honesto: a recusa deixa de acontecer no "decidir" e passa a acontecer no flush — o evento chega
 * a ser apendado à unidade de trabalho antes de tudo ser desfeito. Nada é persistido (evento e linha
 * commitam juntos, ou nenhum dos dois), mas a falha fica mais longe da decisão do que o resto do projeto
 * costuma pôr.
 *
 * <h2>Por que {@code Optional<Post>} num command criacional</h2>
 * O Post ainda não existe, então pedir {@code Post} obrigatório falharia sempre. Pedindo
 * {@code Optional<Post>} ganham-se duas coisas: dá para rejeitar id duplicado, e carregar a entidade
 * coloca o stream {@code postId=<id>} na <b>consistency boundary</b> do append — duas criações
 * concorrentes com o mesmo id conflitam no event store em vez de gerarem dois streams.
 */
@ApplicationScoped
public class CreatePostCommand {

    /**
     * A mensagem: criar um Post. O id é gerado por quem despacha, assim o caller já sabe o id antes do
     * command ser processado (e o {@code createPost} do GraphQL consegue devolver o Post projetado).
     * <p>
     * {@code @TargetEntityId} (o {@code @TargetAggregateIdentifier} do Axon 5) é lido pelo
     * {@code @InjectEntity} do {@link #handle} para descobrir <i>qual</i> stream carregar.
     * <p>
     * O nome da mensagem é explícito ({@code posts.CreatePost#1.0.0}), então aninhar o record não muda
     * nada no wire: o que trafega no command bus continua sendo esse nome, não o da classe.
     * <p>
     * O {@code authorId} <b>não</b> vem do input do GraphQL — vem de quem está autenticado. É a
     * diferença entre "quem eu digo que sou" e "quem o token diz que eu sou", e só a segunda serve:
     * aceitar o autor como campo do input deixaria qualquer um publicar em nome de qualquer outro.
     */
    @Command(namespace = "posts", name = "CreatePost", version = "1.0.0")
    public record CreatePost(
            @TargetEntityId PostId postId,
            String title,
            String content,
            UserId authorId,
            /**
             * As tags com que o post já nasce. Vazio é o caso normal, e é o que põe a saga de
             * tagueamento em movimento: sem tag o post fica <b>pré-criado</b> na versão 1 e o
             * {@code PostPreCreatedEvent} sai para o serviço que decide a primeira tag.
             * <p>
             * Não vazio, o post nasce completo na versão 2 e nada atravessa o broker. Este caminho
             * <b>não é alcançável pelo GraphQL hoje</b>, e a razão é mundana: não existe mutation que
             * crie tag, então não haveria como obter um {@code tagId} válido para mandar. Ele existe no
             * command porque é o command que define a capacidade — e é ele, não o schema, que a saga
             * precisa poder contornar.
             */
            List<TagId> tagIds
    ) {
        public CreatePost {
            tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
        }
    }

    private final Clock clock;
    private final PostRepository posts;
    private final TagRepository tags;

    public CreatePostCommand(Clock clock, PostRepository posts, TagRepository tags) {
        this.clock = clock;
        this.posts = posts;
        this.tags = tags;
    }

    /**
     * @return o id do Post criado, que vira o payload do {@code CommandResultMessage}
     */
    @CommandHandler
    public PostId handle(CreatePost command,
                         @InjectEntity Optional<Post> existing,
                         EventAppender eventAppender) {
        if (existing.isPresent()) {
            throw new PostAlreadyExistsException(command.postId());
        }
        Author author = Author.reference(command.authorId());

        Post post = Post.create(
                command.postId(),
                command.title(),
                command.content(),
                author,
                initialTags(command),
                clock.instant(),
                appendingTo(eventAppender)
        );

        posts.save(post);
        return post.id();
    }

    /**
     * Carrega as tags pedidas, pela mesma razão do {@code AssignTagToPostCommand}: o vínculo é uma linha
     * em {@code post_tags} com foreign key para {@code tags}, e um id inexistente estouraria como erro
     * de integridade no flush. Carregar antes transforma isso numa {@link TagNotFoundException} com nome
     * e lugar — e entrega ao agregado uma Tag <b>gerenciada</b>, que o {@code merge} do Post
     * reaproveita em vez de resolver um objeto destacado por id.
     */
    private List<Tag> initialTags(CreatePost command) {
        return command.tagIds().stream()
                .map(tagId -> tags.findById(tagId).orElseThrow(() -> new TagNotFoundException(tagId)))
                .toList();
    }
}

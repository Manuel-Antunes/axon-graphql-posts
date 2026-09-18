package dev.manuelantunes.axonposts.domain.post.event;

import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.shared.DomainEvent;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

import java.time.Instant;
import java.util.List;

/**
 * Evento de domínio: um Post está <b>completo e visível</b> — tem ao menos uma tag.
 *
 * <h2>Ele não é mais o nascimento do Post</h2>
 * Quem afirma que o post existe é {@link PostPreCreatedEvent}, e é ele o {@code @EntityCreator}. Este
 * evento afirma a conclusão: ou logo em seguida, na mesma unidade de trabalho, quando o post já nasce
 * com tags; ou depois de a decisão de tagueamento voltar do serviço vizinho, pelo broker.
 * <p>
 * A consequência para quem observa: {@code onPostCreated} passa a emitir o post <b>com</b> as tags, na
 * versão 2. Antes ele emitia a versão 1 sem tag e o cliente precisava esperar um {@code onPostUpdated}
 * para ver o post inteiro. A subscription passou a significar "o post está pronto".
 *
 * <h2>Por que carrega as tags, e por que a versão</h2>
 * As tags porque quem o recebe precisa gravar o vínculo sem consultar mais nada — este evento é o que
 * chega pelo broker do outro lado da saga, e lá não há de onde carregar a Tag. A versão porque
 * {@code @EventSourcingHandler} tem de ser idempotente e todo campo de evento deste projeto é valor
 * ABSOLUTO: aplicar duas vezes tem de dar o mesmo estado, o que {@code version.next()} dentro do
 * {@code on(...)} não dá.
 *
 * <h2>UMA tag, e isso foi uma perda</h2>
 * {@code @EventTag} no {@code postId} é o que liga o evento ao stream da entidade no Axon 5 (dynamic
 * consistency boundary): o {@code Post} é reidratado com {@code EventCriteria.havingTags(postId=<id>)}.
 * <p>
 * O {@code authorId} <b>tinha</b> {@code @EventTag} também, e por um bom motivo: ele não serve para
 * reidratar nada, serve para <b>consultar</b> — com ele, "todos os eventos do autor X" é um critério de
 * event store e não uma varredura, o que é a base da newsletter e o que permitiria reconstruir uma
 * projeção por autor a partir do zero. Era de graça enquanto o event store era o em memória, que
 * implementa DCB por inteiro.
 * <p>
 * Ele saiu quando o event store passou a ser o Postgres, e a razão é do <b>framework</b>, não deste
 * código: o Axon 5.3.1 tem exatamente dois {@code EventStorageEngine} —
 * {@code InMemoryEventStorageEngine}, com DCB completo, e {@code AggregateBasedJpaEventStorageEngine},
 * que é o modo de compatibilidade com o modelo de agregado do Axon 4. Nele cada linha tem UM
 * {@code aggregateIdentifier}, então um evento com duas tags não tem onde caber, e o append morre com
 * <pre>
 * TooManyTagsOnEventMessageException: An Event Storage engine in Aggregate mode does not
 * support multiple tags per event
 * </pre>
 * Medido: 8 de 8 testes de ciclo de vida falhando no primeiro {@code createPost} depois de ligar o
 * store persistente. DCB de verdade em armazenamento relacional não existe na 5.3.1 — só no Axon
 * Server, pelo {@code axon-server-connector}.
 * <p>
 * Então a escolha real foi: <b>durabilidade ou a segunda tag</b>. Durabilidade ganhou, porque sem ela a
 * saga coreografada entre dois serviços perde os passos intermediários no restart. Consultar eventos
 * por autor volta a ser varredura — e o dia em que isso pesar, o conserto é trocar de store, não mexer
 * neste arquivo.
 *
 * <h2>Só o id do autor, e não o nome</h2>
 * O nome viajava aqui até o {@code PostView} parar de carregar o objeto do autor. Depois disso ele era
 * lido por <b>uma</b> linha do sistema — para montar uma referência cujo nome ninguém consultava. Um
 * campo desnormalizado num contrato imutável só se justifica enquanto alguém o lê; quando para, ele vira
 * uma promessa que o event store carrega para sempre sem ninguém cobrar.
 * <p>
 * Quem mostra o autor no GraphQL é o DataLoader, que carrega a entidade de verdade e sempre com o nome
 * atual — que é o comportamento certo para um perfil, ao contrário de uma tag, onde a cópia histórica faz
 * sentido.
 * <p>
 * O payload é de tipos primitivos e value objects de identidade, não de entidades: um evento é
 * <b>contrato</b> — atravessa processo, é serializado e fica gravado para sempre.
 */
/*
 * VERSÃO 2.0.0, e a mudança de número é o contrato mudando: o record ganhou `tags` e `version`, e o
 * significado do evento deixou de ser "nasceu" para ser "está completo". `MessageType` carrega a versão
 * no fio (`posts.PostCreated#2.0.0`), então um consumidor escrito contra a 1.0.0 não é entregue por
 * engano — ele simplesmente não casa. Era o dia de usar o campo que o @Event sempre teve.
 */
@Event(namespace = "posts", name = "PostCreated", version = "2.0.0")
public record PostCreatedEvent(
        @EventTag PostId postId,
        String title,
        String content,
        /*
         * SEM @EventTag — ver "UMA tag, e isso foi uma perda", acima.
         */
        UserId authorId,
        List<AssignedTag> tags,
        long version,
        Instant occurredAt
) implements DomainEvent {

    public PostCreatedEvent {
        tags = List.copyOf(tags);
    }

    /**
     * A tag como ela viaja: id e nome, não a entidade.
     * <p>
     * O nome vem junto de propósito, e é uma desnormalização deliberada num contrato imutável — o
     * oposto da decisão sobre o nome do autor, logo abaixo. A razão é a diferença entre os dois: o nome
     * do autor é um perfil, que muda e deve ser mostrado atual; o nome da tag no instante da atribuição
     * é parte do fato. E há uma razão operacional que decide o caso: quem recebe este evento do outro
     * lado do broker <b>não tem a tabela de tags</b> — sem o nome aqui, ele não conseguiria gravar o
     * vínculo sem uma chamada de volta, que é exatamente o acoplamento que a coreografia evita.
     */
    public record AssignedTag(String tagId, String name) {
    }
}

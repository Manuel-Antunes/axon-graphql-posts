package dev.manuelantunes.axonposts.infrastructure.messaging;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

/**
 * A memória de "esta mensagem eu já ingeri". Uma linha por mensagem recebida do broker, gravada no
 * <b>mesmo commit</b> do append no event store.
 *
 * <h2>Por que precisa existir</h2>
 * Porque broker nenhum entrega exatamente uma vez. RabbitMQ garante <i>ao menos</i> uma: um nack, um
 * restart do consumidor antes do ack, um requeue por timeout — todos reentregam a MESMA mensagem. E
 * apendar o mesmo evento duas vezes no stream não é desperdício, é <b>estado errado</b>: o agregado é
 * reidratado somando eventos, então um evento duplicado é uma decisão aplicada duas vezes.
 * <p>
 * A metadata do envelope não serve para isto: ela viaja com a mensagem, e a reentrega traz a mesma
 * metadata. Quem sabe o que já processou é o serviço, e essa memória tem de sobreviver ao restart.
 *
 * <h2>Por que {@code on conflict do nothing}, e não consultar antes</h2>
 * Porque consultar-depois-inserir tem janela de corrida: dois nós (ou duas threads do mesmo consumidor)
 * passam pela consulta antes de qualquer um inserir, e os dois apendam. O {@code insert} condicional
 * resolve isso no banco, que é o único lugar onde a decisão é serializável — e a contagem de linhas
 * afetadas <b>é</b> a resposta.
 * <p>
 * Isso amarra a lib ao PostgreSQL, que é uma dependência nova e real. É deliberado: a alternativa
 * portável é {@code insert} + capturar violação de unicidade, e uma exceção de constraint dentro de uma
 * transação JTA marca a transação para rollback em vários drivers — o que abortaria o append junto. O
 * dia em que outro banco entrar, o conserto é aqui e é uma linha de SQL.
 *
 * <h2>SQL nativo, sem {@code @Entity}</h2>
 * Uma entidade JPA seria mapeamento a manter em sincronia com a migration por nada: ninguém consulta
 * esta tabela por objeto, e ela não tem comportamento. O preço é que o {@code validate} do Hibernate
 * não confere o schema dela — em troca, não há entidade de biblioteca vazando para a persistence unit
 * de quem usar a lib.
 */
@ApplicationScoped
public class MessageInbox {

    private static final String RECORD = """
            insert into axon_message_inbox (identifier, message_type, origin, received_at)
            values (?1, ?2, ?3, ?4)
            on conflict (identifier) do nothing
            """;

    private final EntityManager em;
    private final Clock clock;

    MessageInbox(EntityManager em, Clock clock) {
        this.em = em;
        this.clock = clock;
    }

    /**
     * @return {@code true} se a mensagem é nova (e portanto deve ser apendada), {@code false} se já
     *         havia sido ingerida — caso em que confirmar a entrega e descartar é o comportamento
     *         correto, não um erro.
     */
    public boolean register(String identifier, String messageType, String origin) {
        return em.createNativeQuery(RECORD)
                .setParameter(1, identifier)
                .setParameter(2, messageType)
                .setParameter(3, origin)
                .setParameter(4, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .executeUpdate() == 1;
    }
}

package dev.manuelantunes.axonposts.domain.post.vo;

import dev.manuelantunes.axonposts.domain.post.exception.InvalidPostException;
import jakarta.persistence.Embeddable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.io.Serializable;
import java.util.UUID;

/**
 * Identidade do Post. Value object imutável, validado e <b>mapeado</b>.
 * <p>
 * {@code @Embeddable} num {@code record}: o Hibernate instancia pelo construtor canônico, então a
 * invariante roda também quando a linha volta do banco. A chave primária pede
 * {@link Serializable} — daí a interface.
 * <p>
 * Três papéis num tipo só: {@code @EmbeddedId} da entidade JPA, tipo de id com que a entidade é
 * registrada no Axon ({@code EventSourcedEntityModule.autodetected(PostId.class, Post.class)}, em
 * {@code AxonProducer}) e {@code @TargetEntityId} dos commands. O {@link #toString()} devolve o valor cru <b>de propósito</b> —
 * é ele que vira o valor da tag ({@code postId=<uuid>}) no event store.
 */
/*
 * AS DUAS ANOTAÇÕES DE JACKSON, E POR QUE ELAS ESTÃO NO DOMÍNIO
 * =============================================================
 * Porque este id atravessa o FIO. Ele é campo de evento, e evento é contrato: serializado, gravado no
 * event store para sempre e lido por outro serviço.
 *
 * Sem `@JsonValue` um record de um componente sai como OBJETO — `{"postId":{"value":"abc"}}` — e quem
 * consome do outro lado tem de modelar um invólucro que só existe porque aqui dentro ele é um tipo.
 * Com ela, sai `{"postId":"abc"}`: o identificador é escalar no fio e objeto na memória, que é
 * exatamente a divisão que a regra "eventos carregam primitivos" sempre quis dizer.
 *
 * Isto não foi teoria. Enquanto o event store era em memória nada era serializado e ninguém notou; no
 * primeiro serviço que desserializou o payload num record próprio, a saga morreu com
 *   MismatchedInputException: Cannot deserialize value of type `java.lang.String`
 *   from Object value (token `JsonToken.START_OBJECT`)
 * e o evento ficou preso, reentregue e rejeitado.
 *
 * `@JsonCreator(DELEGATING)` é a volta: sem ele Jackson usaria o construtor canônico como criador por
 * propriedades e esperaria o objeto de novo.
 *
 * É uma dependência de `domain` para uma biblioteca de serialização, da mesma família da dívida
 * consciente que as views carregam com as anotações do MicroProfile GraphQL. A diferença é que esta é
 * menor e mais defensável: ela não descreve um protocolo, descreve que a identidade é o valor.
 */
@Embeddable
public record PostId(@JsonValue String value) implements Serializable {

    public PostId {
        if (value == null || value.isBlank()) {
            throw new InvalidPostException("postId não pode ser vazio");
        }
        value = value.strip();
    }

    /** Id que já existe — veio de fora (GraphQL, outro contexto). */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PostId of(String value) {
        return new PostId(value);
    }

    /** Id novo, gerado antes do command ser despachado, para o caller já saber o id. */
    public static PostId newId() {
        return new PostId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}

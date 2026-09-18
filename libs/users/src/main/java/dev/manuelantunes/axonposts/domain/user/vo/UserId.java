package dev.manuelantunes.axonposts.domain.user.vo;

import dev.manuelantunes.axonposts.domain.user.exception.InvalidUserException;
import jakarta.persistence.Embeddable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.io.Serializable;
import java.util.UUID;

/**
 * Identidade de um usuário — e, por tabela-por-tipo, também a de um {@code Author}: a subclasse não tem
 * id próprio, ela compartilha o do {@code User}. É por isso que não existe um {@code AuthorId}.
 * <p>
 * Mesmo desenho do {@code PostId} e do {@code TagId}: {@code @EmbeddedId} e {@code @TargetEntityId}.
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
public record UserId(@JsonValue String value) implements Serializable {

    public UserId {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("userId não pode ser vazio");
        }
        value = value.strip();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UserId of(String value) {
        return new UserId(value);
    }

    public static UserId newId() {
        return new UserId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}

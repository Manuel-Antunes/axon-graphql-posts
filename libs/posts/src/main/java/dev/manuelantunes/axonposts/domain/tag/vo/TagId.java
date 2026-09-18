package dev.manuelantunes.axonposts.domain.tag.vo;

import dev.manuelantunes.axonposts.domain.tag.exception.InvalidTagException;
import jakarta.persistence.Embeddable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.io.Serializable;
import java.util.UUID;

/**
 * Identidade da Tag. Mesmo desenho do {@code PostId}: {@code @EmbeddedId} da entidade JPA, tipo de id do
 * {@code EventSourcedEntityModule} e {@code @TargetEntityId} dos commands.
 */
/*
 * AS DUAS ANOTAÇÕES DE JACKSON, E POR QUE ELAS ESTÃO NO DOMÍNIO
 * =============================================================
 * Porque este id atravessa o FIO. Ele é campo de evento, e evento é contrato: serializado, gravado no
 * event store para sempre e lido por outro serviço.
 *
 * Sem `@JsonValue` um record de um componente sai como OBJETO — `{"tagId":{"value":"abc"}}` — e quem
 * consome do outro lado tem de modelar um invólucro que só existe porque aqui dentro ele é um tipo. Com
 * ela, sai `{"tagId":"abc"}`: o identificador é escalar no fio e objeto na memória, que é exatamente a
 * divisão que a regra "eventos carregam primitivos" sempre quis dizer.
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
public record TagId(@JsonValue String value) implements Serializable {

    public TagId {
        if (value == null || value.isBlank()) {
            throw new InvalidTagException("tagId não pode ser vazio");
        }
        value = value.strip();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TagId of(String value) {
        return new TagId(value);
    }

    public static TagId newId() {
        return new TagId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}

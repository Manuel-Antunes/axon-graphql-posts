package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.event.UserRegisteredEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;

/**
 * Um usuário que lê e não escreve. Tipo concreto padrão da hierarquia.
 *
 * <h2>Por que ele existe agora, e não existia antes</h2>
 * Até o {@code User} virar entidade polimórfica, "leitor" era a <b>ausência</b> de linha em
 * {@code authors} — uma definição por negação. Com a raiz abstrata e {@code concreteTypes} declarando os
 * tipos, ser leitor passa a ser algo que se <i>é</i>, e não algo que se deixa de ser.
 * <p>
 * O ganho não é filosófico: uma terceira subclasse (moderador, editor) quebraria a definição por negação
 * em silêncio — todo mundo que não fosse autor viraria leitor. Com tipos declarados, o
 * {@code @EntityCreator} teria de decidir explicitamente, em tempo de compilação.
 *
 * <h2>Sem estado próprio, e ainda assim com tabela</h2>
 * {@code readers} tem só a chave primária. É o preço do {@code JOINED} com uma subclasse sem campos, e é
 * um preço honesto: a linha é a afirmação de que este usuário é um leitor.
 */
@Entity
@Table(name = "readers")
@PrimaryKeyJoinColumn(name = "id")
/*
 * O mesmo cuidado do Author: numa herança JOINED o Hibernate emite um DELETE POR TABELA, e o @SQLDelete
 * do User cobre só `users`. Sem isto, a linha de `readers` seria removida de verdade e o usuário voltaria
 * de um restore sem tipo concreto nenhum — nem leitor, nem autor.
 */
@SQLDelete(sql = "update readers set id = id where id = ?")
public class Reader extends User {

    /** Exigido pelo JPA. */
    protected Reader() {
    }

    /** Chamado pelo {@code @EntityCreator} do {@link User} quando o evento diz que não é autor. */
    Reader(UserRegisteredEvent event) {
        super(event);
    }
}

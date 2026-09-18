package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.event.UserRegisteredEvent;
import dev.manuelantunes.axonposts.domain.user.vo.DisplayName;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Um usuário que também escreve. Tipo concreto escolhido pelo {@code @EntityCreator} do {@link User}
 * quando o {@link UserRegisteredEvent} diz {@code author = true}.
 *
 * <h2>Onde ele "possui" os posts</h2>
 * A posse é expressa do lado do {@code Post}, que tem um {@code @ManyToOne Author}. <b>Não</b> existe
 * aqui um {@code @OneToMany Set<Post>}: um autor produtivo tem milhares de posts, e uma coleção mapeada é
 * um convite a carregar todos para responder qualquer coisa. O campo {@code Author.posts} do GraphQL é
 * uma <i>consulta paginada</i> resolvida por DataLoader.
 * <p>
 * É a mesma escolha do {@code Post.tags}, pelo motivo oposto: lá a coleção é pequena e limitada, então
 * mora no agregado; aqui é aberta, então mora numa consulta.
 *
 * <h2>Referência</h2>
 * Como {@code Tag.reference}, existe {@link #reference} para o replay do {@code Post} montar o autor a
 * partir do evento sem ir ao banco.
 */
@Entity
@Table(name = "authors")
@PrimaryKeyJoinColumn(name = "id")
/*
 * Numa herança JOINED o Hibernate emite um DELETE POR TABELA: sem isto, `users` seria marcada como
 * apagada e a linha de `authors` removida de verdade — o autor voltaria de um restore como se fosse um
 * leitor, com a bio perdida. O UPDATE não muda nada de propósito: só ocupa o lugar do DELETE.
 */
@SQLDelete(sql = "update authors set id = id where id = ?")
public class Author extends User {

    /** Texto curto de apresentação. Só autor tem, e é por isso que a coluna pode ser NOT NULL. */
    @Column(name = "bio", length = 280, nullable = false)
    private String bio;

    /** Exigido pelo JPA. */
    protected Author() {
    }

    /** Chamado pelo {@code @EntityCreator} do {@link User} quando o evento diz que é autor. */
    Author(UserRegisteredEvent event) {
        super(event);
        this.bio = normalized(event.bio());
    }

    /**
     * Um Author <b>não carregado</b>: identidade e nome, nada mais — o mesmo papel de
     * {@code Tag.reference}.
     * <p>
     * <b>Só o id.</b> É tudo o que o {@code Post} precisa saber sobre o autor: ele compara identidade
     * ({@code isWrittenBy}) e grava {@code posts.author_id}. O nome não vem porque ninguém o lê a partir
     * de um post — quem mostra o autor no GraphQL é o DataLoader, que carrega a entidade de verdade.
     * <p>
     * Nunca salvar: nome, bio e {@code createdAt} vêm vazios. O {@code merge} do Post resolve a
     * associação pelo id (um proxy, sem SELECT) e não toca em {@code users}, porque não há cascade.
     */
    public static Author reference(UserId id) {
        Author author = new Author();
        author.initReference(id);
        author.bio = "—";
        return author;
    }

    /** Bio em branco vira travessão: a coluna é NOT NULL, e é isso que a subclasse existe para permitir. */
    static String normalized(String bio) {
        return bio == null || bio.isBlank() ? "—" : bio.strip();
    }

    /**
     * O acréscimo da subclasse à autorização: um autor é {@code ROLE_USER} <b>e</b> {@code ROLE_AUTHOR}.
     * É a única linha de código em que ser {@code Author} vira permissão.
     */
    @Override
    public Set<Role> roles() {
        return EnumSet.of(Role.USER, Role.AUTHOR);
    }

    public String bio() {
        return bio;
    }

    @Override
    public String toString() {
        return Objects.toString(name(), String.valueOf(id()));
    }
}

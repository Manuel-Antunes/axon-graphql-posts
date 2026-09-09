package dev.manuelantunes.axonposts.domain.user;

import dev.manuelantunes.axonposts.domain.user.vo.DisplayName;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * O autor: um {@link User} que também escreve. Subclasse table-per-type — linha em {@code users} e linha
 * em {@code authors}, mesma chave primária.
 *
 * <h2>Onde ele "possui" os posts</h2>
 * A posse é expressa do lado do {@code Post}, que tem um {@code @ManyToOne Author}. <b>Não</b> existe
 * aqui um {@code @OneToMany Set<Post>}, e a ausência é a decisão: um autor produtivo tem milhares de
 * posts, e uma coleção mapeada é um convite a carregar todos para responder qualquer coisa. O campo
 * {@code Author.posts} do GraphQL é uma <i>consulta paginada</i> resolvida por DataLoader
 * ({@code AuthorPostsController}), que é o que uma cursor connection pede.
 * <p>
 * É a mesma escolha do {@code Post.tags}, pelo motivo oposto: lá a coleção é pequena e limitada, então
 * mora no agregado; aqui ela é aberta, então mora numa consulta.
 *
 * <h2>Referência</h2>
 * Como {@code Tag.reference}, existe {@link #reference} para o replay do {@code Post} montar o autor a
 * partir do evento sem ir ao banco.
 */
@Entity
@Table(name = "authors")
@PrimaryKeyJoinColumn(name = "id")
/*
 * O @SQLDelete de User cuida da tabela `users`, mas numa herança JOINED o Hibernate emite UM delete POR
 * TABELA: sem isto, `users` seria marcada como apagada e a linha de `authors` seria removida de verdade —
 * o autor voltaria de um restore como se fosse um Reader, com a bio perdida.
 *
 * O UPDATE abaixo não muda nada de propósito: só existe para ocupar o lugar do DELETE. É o preço de
 * combinar exclusão lógica com table-per-type, e é o tipo de detalhe que só aparece quando se testa o
 * ciclo apagar → restaurar inteiro.
 */
@SQLDelete(sql = "update authors set id = id where id = ?")
public class Author extends User {

    /** Texto curto de apresentação. Só autor tem, e é por isso que a coluna pode ser NOT NULL. */
    @Column(name = "bio", length = 280, nullable = false)
    private String bio;

    /** Exigido pelo JPA. */
    protected Author() {
    }

    private Author(UserId id, Email email, DisplayName name, String bio, Instant createdAt) {
        super(id, email, name, createdAt);
        this.bio = bio;
    }

    /** Um autor: lê e escreve. Como o {@code User}, nasce sem credencial — quem liga é {@code link}. */
    public static Author register(UserId id, String email, String name, String bio, Instant now) {
        return new Author(id, Email.of(email), DisplayName.of(name), normalized(bio), now);
    }

    /** Bio em branco vira travessão: a coluna é NOT NULL, e é isso que a subclasse existe para permitir. */
    static String normalized(String bio) {
        return bio == null || bio.isBlank() ? "—" : bio.strip();
    }

    /**
     * Um Author <b>não carregado</b>: identidade e nome, nada mais — o mesmo papel de
     * {@code Tag.reference}.
     * <p>
     * O {@code Post} guarda um {@code @ManyToOne Author}, e o replay reconstrói o post a partir do
     * {@code PostCreatedEvent}, que carrega {@code authorId} e {@code authorName}. Sem sessão JPA para
     * resolver a entidade, o replay produz esta referência. Nunca salvar: {@code bio} e credenciais vêm
     * vazias, e o {@code merge} do Post não toca em {@code users}/{@code authors} porque não há cascade.
     */
    public static Author reference(UserId id, DisplayName name) {
        Author author = new Author();
        author.initReference(id, name);
        author.bio = "—";
        return author;
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

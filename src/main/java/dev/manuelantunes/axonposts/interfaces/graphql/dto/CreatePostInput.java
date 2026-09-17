package dev.manuelantunes.axonposts.interfaces.graphql.dto;

import org.eclipse.microprofile.graphql.Input;
import org.eclipse.microprofile.graphql.NonNull;

import dev.manuelantunes.axonposts.domain.post.vo.PostTitle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Input GraphQL de {@code createPost}.
 * <p>
 * O schema já garante que os dois campos vêm não-nulos ({@code String!}); as constraints abaixo cobrem
 * o que o GraphQL não sabe expressar — texto só de espaços e título longo demais.
 * <p>
 * <b>Não há campo {@code author}.</b> Ele saiu quando o autor passou a vir do token: um input que
 * carrega o autor é um input que deixa publicar em nome dos outros.
 *
 * <h2>{@code @Input}, e por que ele não é decorativo</h2>
 * Num schema code-first o SmallRye nomeia um input type acrescentando o sufixo {@code Input} ao nome da
 * classe — o que daria {@code CreatePostInputInput}. A anotação fixa o nome, e é ela que mantém o
 * contrato idêntico ao do SDL escrito à mão do projeto Spring.
 *
 * <h2>Isto não substitui a validação do domínio</h2>
 * {@code PostTitle} e {@code PostContent} continuam validando por conta própria, e é
 * essa a validação que vale: ela protege a invariante venha o command de onde vier. As constraints aqui
 * são <b>fail-fast de borda</b> — rejeitam a requisição malformada antes de gastar um command, e
 * devolvem ao cliente um erro de campo em vez de uma exceção de domínio traduzida. O limite duplicado é
 * amarrado à fonte: {@link PostTitle#MAX_LENGTH}.
 */
@Input("CreatePostInput")
public record CreatePostInput(

        @NonNull
        @NotBlank(message = "title não pode ser vazio")
        @Size(max = PostTitle.MAX_LENGTH, message = "title excede {max} caracteres")
        String title,

        @NonNull
        @NotBlank(message = "content não pode ser vazio")
        String content
) {
}

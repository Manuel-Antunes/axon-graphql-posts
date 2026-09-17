package dev.manuelantunes.axonposts.application.user.view;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * DTO de saída de um usuário: a {@code interface User} do schema.
 * <p>
 * <b>Selada</b>, e as duas permitidas correspondem exatamente aos dois tipos concretos do schema —
 * {@link ReaderView} → {@code Reader}, {@link AuthorView} → {@code Author}, os mesmos dois que o
 * {@code @EventSourcedEntity(concreteTypes = ...)} declara no domínio. O compilador reclama se um
 * terceiro tipo aparecer sem par aqui.
 *
 * <h2>Uma interface Java vira uma interface GraphQL, e só</h2>
 * O construtor de schema do SmallRye encontra os implementadores desta interface no índice e registra os
 * dois tipos concretos sozinho. É onde a versão Spring precisava de duas peças: o {@code interface User}
 * escrito no {@code .graphqls} e um {@code ClassNameTypeResolver} num {@code @Bean} dizendo qual classe
 * Java era qual type — porque o resolver padrão casava pelo nome simples, e {@code AuthorView} não existe
 * no schema. Aqui o {@code @Name} de cada implementação responde as duas perguntas de uma vez.
 *
 * <h2>Por que cada método leva {@code @Name}</h2>
 * Não é redundância com o nome do método: é o que faz os campos existirem.
 * <p>
 * O {@code InterfaceCreator} do SmallRye só considera campo de interface o método que <b>parece um
 * getter</b> ({@code getX()}/{@code isX()}) <i>ou</i> que traz um {@code @Name}. Estes acessores seguem o
 * estilo de record ({@code id()}, {@code name()}), que os implementadores — records de verdade — dão de
 * graça, então sem a anotação a interface sairia com <b>zero campos</b>. E uma interface sem campos é
 * descartada em silêncio pelo modelo ({@code Schema.addInterface} só guarda a que tem campos): o tipo
 * {@code User} simplesmente não entraria no schema, e o erro só apareceria na partida, como
 * "type User not found in schema" ao resolver a referência pendente de {@code me}.
 * <p>
 * A alternativa seria renomear tudo para {@code getId()}/{@code getName()} e escrever os delegadores em
 * cada record. Quatro anotações custam menos.
 *
 * <h2>Ela carrega tudo, e isso eliminou consultas</h2>
 * Antes tinha só id e nome, e {@code email}, {@code bio} e {@code accounts} eram três lotes separados,
 * cada um voltando ao banco para preencher um campo — inclusive quando quem montou a view já tinha o
 * usuário inteiro carregado. A view completa é montada de uma vez, a partir do que o Hibernate já
 * hidratou (a herança {@code JOINED} traz a bio no mesmo join; o {@code join fetch} traz as contas).
 */
@Name("User")
@Description("Quem tem conta. Interface porque a hierarquia do domínio é polimórfica")
public sealed interface UserView permits ReaderView, AuthorView {

    @Name("id")
    @Id
    @NonNull
    String id();

    @Name("name")
    @NonNull
    String name();

    @Name("email")
    @NonNull
    String email();

    /** As credenciais ligadas — o account linking, visível. */
    @Name("accounts")
    @NonNull
    @Description("As credenciais deste usuário, uma por provedor")
    List<AccountView> accounts();
}

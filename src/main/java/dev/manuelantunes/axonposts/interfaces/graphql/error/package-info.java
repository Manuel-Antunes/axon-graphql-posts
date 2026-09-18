/**
 * <b>Tradução de exceções para o protocolo.</b> Um único lugar decide como cada falha aparece para o
 * cliente GraphQL — {@code BAD_REQUEST}, {@code UNAUTHORIZED}, {@code FORBIDDEN}, {@code NOT_FOUND} —
 * venha ela da Bean Validation, do domínio, do banco ou do Axon.
 * <p>
 * As <b>exceções de domínio</b> continuam onde nascem: {@code InvalidPostException} e
 * {@code PostAlreadyExistsException} moram em {@code domain.post.exception} porque fazem parte do
 * vocabulário do domínio — ele as lança sem saber que existe HTTP, GraphQL ou código de erro. O que mora
 * aqui é quem <i>ouve</i> essas exceções na borda, e as quatro exceções que existem só para nomear a
 * classificação no protocolo.
 * <p>
 * As classificadas levam {@code @ErrorCode} do SmallRye, que vira {@code errors[].extensions.code}. Para
 * a mensagem delas chegar ao cliente é preciso listá-las em
 * {@code quarkus.smallrye-graphql.show-runtime-exception-message}, no {@code application.properties} —
 * o padrão do SmallRye é responder "System Error" a toda exceção não-checada, e esse padrão é o certo
 * para todas as outras. <b>Mover uma destas classes de pacote quebra aquela lista</b>, que é por nome
 * qualificado.
 * <p>
 * Quem aplica a tradução é o {@code ErrorTranslationInterceptor}, ligado por {@code @TranslatesErrors} na
 * classe de cada {@code @GraphQLApi}.
 *
 * <h2>Por que o {@code DataIntegrityTranslator} está aqui, e não em {@code infrastructure}</h2>
 * Porque ele é parte do <b>mecanismo de classificação</b>, não do acesso a dados: o que ele faz é dizer
 * que uma violação de restrição do Postgres significa {@code EmailAlreadyInUseException}, e quem precisa
 * dessa resposta é o {@code GraphQlErrors}, uma linha acima. Pô-lo em {@code infrastructure} criaria a
 * única dependência de apresentação → infraestrutura do projeto, para ganhar nada.
 * <p>
 * O que ele sabe de Hibernate é o que qualquer classificador de erro precisa saber. O que ele sabe de
 * <i>nomes de constraint</i> é acoplamento real com as migrations, e está travado por teste.
 */
package dev.manuelantunes.axonposts.interfaces.graphql.error;

# quarkus-axon-graphql-posts

Conversão para **Quarkus** da POC [axon-graphql-posts](https://github.com/Manuel-Antunes/axon-graphql-posts),
escrita em Spring Boot.

O objetivo não foi "fazer compilar no Quarkus": foi manter **as mesmas decisões de arquitetura** —
Axon Framework 5 com entidades anotadas e DCB, DDD com value objects `@Embeddable`, Keycloak como único
provedor de identidade, MapStruct em todas as fronteiras, cursor connections do Relay — e trocar cada
peça de infraestrutura do Spring pela peça equivalente do Quarkus, usando o que cada uma tem de melhor em
vez de imitar a outra.

```bash
./mvnw quarkus:dev     # http://localhost:8080/q/graphql-ui/  — só precisa de Docker ligado
./mvnw test            # 123 testes, incluindo ponta a ponta com Postgres e Keycloak de verdade
```

---

## O que mudou, peça por peça

| Papel | Spring Boot | Quarkus |
|---|---|---|
| GraphQL | Spring for GraphQL, schema-first (`posts.graphqls`) | SmallRye GraphQL, **code-first** (o schema sai das classes) |
| Assíncrono | Reactor (`Mono`/`Flux`) + extensão `axon-reactor` | **Mutiny** (`Uni`/`Multi`) sobre os gateways do núcleo do Axon |
| Cursor connections | `ScrollSubrange` + `Window<T>` + `ConnectionTypeDefinitionConfigurer` | `interfaces/graphql/relay`: `Connection<N, E>` e `Edge<N>` genéricos |
| Config do Axon | `axon-spring-boot-starter` | produtor CDI sobre o `EventSourcingConfigurer` do núcleo |
| Persistência | Spring Data JPA (interfaces geradas) | Hibernate ORM + **Panache** |
| Transações | `PlatformTransactionManager` | **JTA/Narayana**, via `JtaTransactionManager` |
| Segurança | `SecurityWebFilterChain` + `@PreAuthorize` + conversor de roles | **quarkus-oidc** + `@RolesAllowed`, sem conversor |
| Senha | `BCryptPasswordEncoder` | `BcryptUtil` |
| Mappers | `@Mapper(componentModel = "spring")` | `-Amapstruct.defaultComponentModel=jakarta-cdi` |
| Infra de teste | Testcontainers à mão (3 classes) | **Dev Services** (zero classes) |
| Subscriptions | GraphQL over SSE | GraphQL over WebSocket (`graphql-transport-ws`) |

O domínio (`domain/`) atravessou **quase intacto**: mudou uma anotação (`@EventSourced` do módulo Spring
virou `@EventSourcedEntity` do núcleo do Axon) e o `Role`, que perdeu o prefixo `ROLE_` porque o Quarkus
não usa nenhum. Isso é o resultado que a arquitetura hexagonal promete e raramente se tem a chance de
medir: as portas (`PostRepository`, `DomainEventPublisher`, `AuthenticatedUser`, `PasswordVerifier`)
absorveram a troca inteira de plataforma.

---

## Os pacotes, e a seta que eles desenham

A conversão começou com três pacotes na raiz agrupados **por papel** — `dto/`, `mapper/`, `exceptions/` —
com a justificativa de "um lugar só para procurar todo input, todo mapeamento, toda tradução de erro".
Funcionava para achar arquivo e escondia a única coisa que um pacote deveria mostrar: **a camada**. Um
pacote de mappers na raiz enxerga domínio e protocolo no mesmo arquivo, e nada no projeto dizia se isso
era permitido ou apenas tolerado.

Hoje cada tipo mora na camada que o possui:

```
application/<agregado>/view/     PostView, TagView, UserView…, PostPage, *ViewMapper
interfaces/graphql/api/          os @GraphQLApi — só resolver
interfaces/graphql/dto/          CreatePostInput, UpdatePostInput
interfaces/graphql/mapper/       PostInputMapper (input → command)
interfaces/graphql/relay/        Connection/Edge/PageInfo/Connections/Cursors + Post/TagConnection/Edge
interfaces/graphql/error/        GraphQlErrors, @TranslatesErrors, as 4 exceções com @ErrorCode
```

### A regra que decidiu cada caso

**Quem atravessa o bus é da aplicação; quem só existe no schema é da apresentação.**

Os `*View` não são "DTOs de saída do controller": são o **resultado das queries**. `FindPost` devolve
`PostView`, `FindAllPosts` devolve `PostPage`, as subscriptions emitem `PostView` — tudo isso acontece
antes de existir um resolver. Deixá-los em `interfaces` faria a aplicação importar a apresentação, que é a
seta ao contrário. Os `*ViewMapper` (entidade → view) foram junto, porque quem os chama são os query
handlers.

Os `*Input` são o oposto: nunca saem da borda. O `PostInputMapper` os transforma em command antes de
qualquer coisa, e depois disso eles não existem mais. Ficaram na apresentação, e o mapper deles também.

### As duas dívidas, anotadas onde elas estão

- **Os `*View` carregam anotações do MicroProfile GraphQL** (`@Name("Post")`, `@Id`, `@Description`). É a
  aplicação sabendo de protocolo. Separar isso significa duas views por agregado — uma da aplicação, uma do
  schema — e um mapper a mais entre elas; para o tamanho desta POC, é ceremônia sem decisão nova. Se um dia
  valer, a fronteira a mexer é o `*ViewMapper`, e nada além dele.
- **`DataIntegrityTranslator` conhece o Hibernate e mora em `error/`.** Ele traduz violação de constraint
  em exceção de domínio, e quem precisa disso é o `GraphQlErrors`, uma linha acima. Em `infrastructure` ele
  criaria a única dependência de apresentação → infraestrutura do projeto, para não ganhar nada.

O `domain/` não foi tocado — de novo. É o segundo rearranjo de pacotes que ele atravessa sem uma linha
alterada.

---

## Connection e Edge genéricos

Era o pedido mais concreto: *"queria que connection e edge fossem tipos genéricos, que eu passasse a
classe com que quero trabalhar e simplesmente funcionasse"*.

```java
// escrito uma vez, em interfaces/graphql/relay
public abstract class Edge<N>                              { N node; String cursor; }
public abstract class Connection<N, E extends Edge<N>>     { List<E> edges; PageInfo pageInfo; }

// uma linha por tipo paginado
public final class PostEdge       extends Edge<PostView> { }
public final class PostConnection extends Connection<PostView, PostEdge> { }
```

```java
// no resolver
ConnectionArgs args = ConnectionArgs.of("post", first, after);
return Connections.page(page.items(), page.hasNext(), args, PostEdge::new, PostConnection::new);
```

### Por que a subclasse de uma linha, e não `Connection<PostView, PostEdge>` direto

Porque o SmallRye nomeia um genérico instanciado pelo tipo **mais um sufixo por argumento**:
`Connection<PostView>` vira `Connection_Post` no schema. É legal, é estável, e não se parece com nenhum
cliente Relay do mundo.

Uma classe **sem parâmetros de tipo próprios** é, para o construtor de schema, um tipo comum — e um tipo
comum leva o nome da classe. O que ela não perde é a resolução dos genéricos: o SmallRye sobe a
hierarquia, encontra `Edge<PostView>` e resolve `N` para `PostView` ao montar o campo `node`.

O segundo parâmetro (`E extends Edge<N>`) não é redundância: é ele que faz o schema sair com
`edges: [PostEdge]!` em vez de `[Edge_Post]!`. Declarado como `List<Edge<N>>`, o construtor de schema
veria um genérico instanciado ali dentro e desfaria o que a subclasse conseguiu.

Duas declarações de uma linha por tipo paginado, e nenhuma lógica de paginação duplicada. É o mais perto
que dá para chegar do `ConnectionTypeDefinitionConfigurer` do Spring — com a diferença de que os tipos
existem em Java, o compilador os confere e o IDE navega até eles. O `RelaySchemaTest` guarda o mecanismo:
ele lê o SDL gerado e falha se `Connection_` voltar a aparecer.

**O que ganhamos de quebra**, e o Spring não tinha:

- **teto de página** (`ConnectionArgs.MAX_LIMIT`): `posts(first: 100000)` é recusado, em vez de descer até
  o `Limit` do Spring Data;
- **cursor com prefixo de tipo**: o cursor de `tags` é recusado em `posts`. O `CursorStrategy` do Spring
  codificava `O_<offset>` sem prefixo, e um cursor servia em qualquer conexão.

---

## O lote: onde o Quarkus ficou mais simples

No projeto Spring, `Post.tags` e `Author.posts` **não podiam** usar `@BatchMapping`: ele não enxerga
`@Argument` nem `ScrollSubrange`, e os dois campos são paginados. A saída eram duas classes de cinquenta
linhas — registrar a função no `BatchLoaderRegistry` pelo construtor, nomear o loader numa constante,
buscar o `DataLoader` no `DataFetchingEnvironment` dentro de um `@SchemaMapping`.

O `BatchDataFetcher` do SmallRye passa os argumentos do campo junto com as chaves no contexto do lote,
então um `@Source` em lote **pode** ter argumentos:

```java
@Name("tags")
public Uni<List<TagConnection>> tags(@Source List<PostView> posts,
                                     @Name("first") Integer first,
                                     @Name("after") String after) { … }
```

Duas classes viraram dois métodos. O `BatchLoadingE2ETest` mede que a simplificação não custou o lote:
uma resposta com 5 posts gasta **o mesmo número de statements** que uma com 1.

---

## Axon 5 sem starter

Não existe `axon-quarkus-starter`, e não precisa existir: o `EventSourcingConfigurer` é a API **do
núcleo**, sem dependência de framework. O `infrastructure/axon/AxonProducer` faz, explicitamente, as três
coisas que o starter do Spring faz por dentro — descobrir handlers, registrar entidades, publicar os
gateways como beans.

Perde-se a mágica, ganha-se poder apontar para o lugar onde cada decisão foi tomada. E os gateways
deixam de ser beans invisíveis: no projeto Spring, **todo** ponto de injeção de `CommandGateway` carrega
um `@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")`, porque o bean existe mas nenhuma
declaração estática o anuncia. Aqui é um `@Produces` comum.

A descoberta de handlers também não se perdeu. O `AxonHandlerLookup` varre o `BeanManager` procurando
classes com métodos `@CommandHandler`/`@QueryHandler`/`@EventHandler` — é o que o `MessageHandlerLookup`
do módulo Spring faz sobre `BeanDefinition`. A regra do projeto continua valendo: **uma mensagem nova é um
arquivo novo**, e o `@Namespace` no `package-info.java` continua sendo o que põe um event handler no
processor certo.

### Três armadilhas que só um teste ponta a ponta pega

Estão documentadas no código porque custaram caro:

1. **`ClientProxy.unwrap`** — o Axon lê os métodos de `instance.getClass()`. Um bean `@ApplicationScoped`
   chega como *client proxy*, uma subclasse que sobrescreve todo método público — e método sobrescrito não
   herda anotações. Sem o unwrap, a aplicação sobe e o primeiro command falha com "no handler for …".
2. **`eventSource` do processor subscribing** — é obrigatório e **não tem default** quando o módulo é
   registrado avulso: o registry do Axon indexa por tipo exato, e o `EventStore` (que *é* um
   `SubscribableEventSource`) não é encontrado por uma busca por `SubscribableEventSource.class`.
3. **`.build()` no módulo do processor** — é ele que registra o processor e os handlers. Registrar o
   módulo sem construí-lo compila e sobe.

As três falham do mesmo jeito: **silêncio**. A aplicação inicia, o log lista os handlers, e nenhum evento
chega à projeção. Foi o `PostLifecycleE2ETest` (`aNewPostArrivesAlreadyTaggedAtVersionTwo`) que as
encontrou.

---

## Mutiny, e onde o trabalho bloqueante acontece

O `SimpleCommandBus` e o `SimpleQueryBus` executam o handler **na thread que despacha**, e lá dentro tem
JPA bloqueante. Um resolver que devolve `Uni` roda no event-loop do Vert.x, e bloquear um event-loop trava
todas as requisições que ele serve. É a mesma armadilha que o projeto Spring resolve com
`subscribeOn(boundedElastic())`, e a mesma resposta, escrita no resolver:

```java
return Uni.createFrom()
        .completionStage(() -> queryGateway.query(new FindPost(id), PostView.class))   // Supplier
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
```

O `Supplier` é o detalhe que decide: a sobrecarga que recebe o `CompletableFuture` **pronto** exigiria que
ele já existisse — ou seja, o `gateway.send(...)` teria rodado no event-loop. É a diferença entre offload
de verdade e offload aparente.

Isto já foi um utilitário (`interfaces/graphql/support/Dispatch`, um método de duas linhas). Saiu: são
duas chamadas do Mutiny, e escondê-las atrás de um nome próprio custava uma classe e uma indireção para
economizar nada — no WebFlux a gente chamava direto. O *porquê* do `Supplier`, que é a única coisa ali que
não é óbvia, ficou no `package-info` da camada, onde vale para todos os resolvers em vez de um só.

**A extensão `axon-reactor` não foi usada.** O `QueryGateway` do núcleo do Axon 5 já devolve
`CompletableFuture` e, para subscriptions, um `Publisher` de Reactive Streams — que
`Multi.createFrom().publisher(FlowAdapters.toFlowPublisher(…))` consome direto. Uma dependência a menos, e
o contrato é o padrão da JVM.

### A subscription que entregava um evento só

O `Publisher` do `subscriptionQuery` **não honra demanda incremental**. Medido nas duas pontas, com o
mesmo Axon e os mesmos três posts:

```java
publisher.subscribe(...)                  // request(Long.MAX_VALUE)  → [um, dois, tres]
publisher.subscribe(umDeCadaVez)          // request(1) a cada onNext → [um]
```

E `request(1)` a cada item é **exatamente** o que o `SubscriptionSubscriber` do SmallRye faz. Resultado: o
handshake completa, o primeiro evento chega, a conexão fica aberta e silenciosa para sempre, e nada no log
reclama. Os quatro testes de subscription que já existiam passavam, porque cada um afirmava sobre **um**
evento.

```java
return Multi.createFrom()
        .publisher(FlowAdapters.toFlowPublisher(queryGateway.subscriptionQuery(…)))
        .onOverflow().buffer(UPDATE_BUFFER);   // ← separa as duas demandas
```

O operador faz o Mutiny pedir ilimitado ao Axon e servir o assinante de baixo a partir do próprio buffer.
O teto existe para a falha ser barulhenta se um assinante travar de vez — melhor um `BackPressureFailure`
do que memória crescendo em silêncio. O `theSameSubscriptionKeepsReceivingEventAfterEvent` é o teste que
teria pego isso, e agora pega.

---

## Segurança: o que sumiu

O `SecurityConfig` do projeto Spring tinha cadeia de filtros, `JwtAuthenticationConverter` e um
`KeycloakRealmRolesConverter` de setenta linhas — este último só porque o conversor de fábrica do Spring
não lê claim aninhada e porque o Spring exige o prefixo `ROLE_`.

Nada disso tem equivalente aqui. O `quarkus-oidc` descobre o JWKS pelo `auth-server-url`, valida
assinatura/`exp`/`iss` e põe as roles de `realm_access.roles` no `SecurityIdentity` **como estão**. Sobrou
um produtor de uma linha (`PasswordVerifier` sobre o `BcryptUtil`).

A divisão continua a mesma, e é a que um endpoint GraphQL exige: a aplicação **não** autentica toda
requisição (`quarkus.http.auth.proactive=false`), porque `post`/`posts` são públicas e `me`/`createPost`
não, e as duas chegam pelo mesmo `POST /graphql`. Quem autoriza é o método.

Uma diferença real de comportamento, e para melhor: o Quarkus distingue **`unauthorized`** (sem token) de
**`forbidden`** (com token, sem a role). No Spring as duas chegavam como `FORBIDDEN`.

---

## Dev Services: três classes de teste viraram três linhas de configuração

O projeto Spring precisava de `Containers` (o `static` que subia Postgres e Keycloak em paralelo),
`KeycloakContainerConfig` (o container com o realm) e `KeycloakTokens` (o grant `password`). E de
`docker compose up -d` antes de `spring-boot:run`.

```properties
quarkus.keycloak.devservices.realm-path=realm-axon-posts.json
quarkus.keycloak.devservices.realm-name=axon-posts
quarkus.keycloak.devservices.create-realm=false
```

O Quarkus sobe os dois containers em dev e em teste, importa **o mesmo arquivo de realm** que o
`docker-compose.yml` monta (ele entra no classpath pelo `<resources>` do `pom.xml`, sem cópia), e injeta
as URLs. `./mvnw quarkus:dev` e `./mvnw test` só precisam do Docker ligado.

O `docker-compose.yml` continua, para dois casos: rodar o JAR empacotado, e ter um console de
administração do Keycloak para mexer no realm à mão.

---

## Schema code-first, e o que ele resolveu

O projeto Spring mantinha, no fim do `posts.graphqls`, um bloco de SDL **gerado por um teste** com os
tipos que o `ConnectionTypeDefinitionConfigurer` criaria — porque o schema era um arquivo e o que o Spring
montava em memória não existia para o IDE nem para geradores de cliente.

Aqui o schema **é** gerado, e o Quarkus o serve em `/graphql/schema.graphql`. Não há segunda definição
para manter em dia. O que era um teste que reescrevia um arquivo virou um teste que lê a única definição
que existe.

O `schema.graphql` na raiz é uma **cópia** desse SDL, para gerador de cliente e para o diff da revisão
mostrar o que uma mudança fez com o contrato. Ele não é lido por nada em runtime; para atualizá-lo:

```bash
curl -s http://localhost:8080/graphql/schema.graphql > schema.graphql
```

### O teto de profundidade, que vem ligado e é baixo demais

O SmallRye liga um `MaxQueryDepthInstrumentation` **por padrão, com 10**, e não há nada a configurar para
descobrir isso — só para consertar. Dez é menos do que este schema precisa em dois lugares:

- a consulta de introspecção tem profundidade **15**, então a GraphiQL abre em branco com um único
  `"maximum query depth exceeded 15 > 10"` — e nenhum gerador de cliente funciona;
- `posts { edges { node { author { posts { edges { node { tags { edges { node { name` são **11**, ou seja,
  um cliente Relay legítimo já é recusado.

O sintoma engana porque quase tudo continua de pé: a aplicação sobe, o SDL em `/graphql/schema.graphql`
é servido normalmente (é HTTP, não é query) e toda query rasa responde.

```properties
quarkus.smallrye-graphql.instrumentation-query-depth=20
```

Vinte mantém a defesa contra aninhamento patológico — que é o motivo de o teto existir — com folga para o
mais fundo que o schema oferece. É o irmão do `ConnectionArgs.MAX_LIMIT`: um limite explícito e anotado, em
vez de um default herdado. O `SchemaIntrospectionTest` guarda os dois casos.

Duas coisas o code-first cobra, e as duas estão anotadas nas classes:

- **os nomes**: `PostView` precisa de `@Name("Post")`, e `CreatePostInput` de `@Input("CreatePostInput")`
  (senão o SmallRye o chamaria de `CreatePostInputInput`). É o mesmo problema que o
  `ClassNameTypeResolver` resolvia num `@Bean` — agora a resposta está na própria classe;
- **a interface polimórfica**: os acessores de `UserView` levam `@Name` porque o `InterfaceCreator` do
  SmallRye só considera campo o método que parece um getter *ou* que traz `@Name`. Uma interface sem
  campos é descartada em silêncio, e o erro aparece na partida como `type User not found in schema`.
  O `RelaySchemaTest` guarda isso também.

Ganhos de contrato que vieram de graça: `createdAt` é `DateTime!` em vez de `String!`, e `provider` é o
enum `AuthProvider!` — no SDL escrito à mão o campo era `AuthProvider!` enquanto o DTO carregava `String`,
e ninguém era obrigado a notar.

---

## A tradução de erro

O Spring GraphQL tem `@GraphQlExceptionHandler`: um método, num bean, e toda exceção de todo controller
passa por lá. O SmallRye não tem equivalente — o `EventingService` dele *observa* o erro, mas não o
substitui.

O que o Quarkus tem é **CDI**, e isso basta. O SmallRye não instancia o `@GraphQLApi`: ele o pede ao
`LookupService`, que aqui é o CDI, e o que volta é o *client proxy* do ArC — então a chamada do resolver
entra pela cadeia de interceptadores como qualquer outra. É a mesma razão pela qual `@RolesAllowed` e
`@Valid` já funcionavam ali.

```java
@GraphQLApi
@ApplicationScoped
@TranslatesErrors           // uma anotação por classe; os resolvers não sabem que ela existe
public class PostQueryApi { … }
```

O `ErrorTranslationInterceptor` chama o mesmo `GraphQlErrors` de antes — a classificação continua sendo um
`if` por família percorrendo a **cadeia de causas**, porque uma exceção de dentro de um command chega
embrulhada pelo `CompletableFuture` do gateway e pelo commit do `ProcessingContext`.

**O que o interceptador conserta**, e uma chamada no corpo do resolver não conseguia: o caminho
**síncrono**. Um `@Valid` que estoura acontece *antes* de o método rodar, então nunca chegava ao
`translating(…)` — um input inválido saía como `ValidationError` **sem `extensions.code`**, apesar de a
decisão documentada ser `BAD_REQUEST`. Agora os dois caminhos convergem no mesmo lugar:

```
createPost(input: {title: "", content: "y"})
  antes:  { classification: ValidationError, violations: [...] }          ← sem code
  agora:  { code: BAD_REQUEST, message: "title não pode ser vazio" }
```

A contrapartida é que o `violations[]` detalhado do SmallRye deu lugar às mensagens juntadas por
`GraphQlErrors.describe` — a mesma mensagem que uma violação de value object produziria, que é o ponto da
validação em duas alturas.

O `extensions.classification` do Spring virou `extensions.code`, alimentado por quatro exceções com
`@ErrorCode` do SmallRye: `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`.

### A segurança entrou junto, e foi o que arrumou a saída

Os interceptadores de segurança do Quarkus são `@Priority(150)`; deixar o nosso em `APPLICATION` (2000)
significava rodar **por dentro** deles, e as recusas escapavam sem tradução. O resultado era ruim de três
jeitos ao mesmo tempo:

```jsonc
// sem token, antes
{"message": null,                      // ← io.quarkus.security.UnauthorizedException não tem mensagem,
 "extensions": {"code": "unauthorized"}}  //   e listá-la em show-runtime-exception-message publicava o null

// sem token, agora
{"message": "credenciais inválidas ou ausentes",
 "extensions": {"code": "UNAUTHORIZED"}}
```

1. **`"message": null`** — pior que "System Error", porque parece bug da aplicação;
2. **`code` em minúsculas**, derivado do nome da classe do Quarkus, convivendo com os
   `BAD_REQUEST`/`FORBIDDEN`/`NOT_FOUND` do projeto;
3. e no log, a `AuthenticationFailedException` crua: a pilha inteira do Mutiny, uma `CompositeException`
   e um `Caused by: [CIRCULAR REFERENCE: ...]` — cinquenta linhas para dizer "token inválido".

`@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)` põe a tradução por fora de tudo e resolve os três
de uma vez: a recusa vira uma exceção classificada, com mensagem e código, e o log passa a mostrar **uma
linha** (`SRGQL012000: ... ForbiddenException: sem permissão para esta operação`) porque a exceção
traduzida é rasa e não tem causa. O que **não** é esperado continua subindo inteiro, com causa e pilha —
`GraphQlErrors.translate` devolve o original quando nada casa.

A distinção que o Quarkus dá de graça continua: sem token é `UNAUTHORIZED`, com token e sem a role é
`FORBIDDEN`. Só os nomes ficaram consistentes com o resto.

**O que não dá para arrumar por aqui**: token presente mas com assinatura inválida é recusado pelo
`HttpAuthenticator` do Quarkus *antes* de qualquer resolver, e a resposta é **HTTP 401 com corpo vazio** —
sem `errors`. Token ausente passa pelo `@Authenticated` e vira erro de GraphQL; token podre não chega lá.
Mudar isso exigiria um `HttpAuthenticationMechanism` próprio, que é muita máquina para o que se ganha.

---

## Testes

123 testes, nas mesmas três alturas do projeto original:

| | o que exercita |
|---|---|
| `domain/*` | domínio puro: sem Axon, sem CDI, sem JPA. O único colaborador é `RecordingDomainEvents` |
| `application/*` | `AxonTestFixture` given-when-then, um por command, com repositório em memória |
| `interfaces/graphql/relay/ConnectionsTest` | cursor ↔ offset, teto de página, montagem da connection |
| `interfaces/graphql/relay/RelaySchemaTest` | o SDL gerado carrega `PostConnection`/`PostEdge` e a `interface User` |
| `interfaces/graphql/SchemaIntrospectionTest` | a GraphiQL consegue introspectar, e a query Relay mais funda passa |
| `e2e/*` | HTTP → token do realm → `@RolesAllowed` → Axon → domínio → JPA → projeção → JSON |

Os testes de domínio e de command atravessaram **sem uma linha alterada** — eles nunca souberam que
existia Spring.

---

## Event store em memória

Como no projeto original: `InMemoryEventStorageEngine`, Postgres só com o read model, nenhuma tabela do
Axon. **Cada reinício apaga os eventos enquanto as linhas continuam no Postgres.** Um post criado antes de
um live reload segue respondendo em `post(id:)` e passa a dar `NOT_FOUND` no `updatePost`, que reidrata o
agregado do stream. Não é bug; é a POC — e o live reload do Quarkus torna isso mais frequente do que o
DevTools do Spring tornava.

Trocar por um event store persistente é trocar um método em `AxonProducer`.

---

## Rodando

```bash
# dev: Dev Services sobem Postgres + Keycloak; só precisa de Docker
./mvnw quarkus:dev
#   GraphiQL   http://localhost:8080/q/graphql-ui/
#   SDL        http://localhost:8080/graphql/schema.graphql
#   Dev UI     http://localhost:8080/q/dev/   (a URL do Keycloak aparece lá)

# testes (mesma coisa: só Docker)
./mvnw test
./mvnw test -Dtest=PostTest                      # uma classe
./mvnw test -Dtest='*E2ETest'                    # só os ponta a ponta

# JAR empacotado, contra o docker-compose
docker compose up -d                             # POSTGRES_PORT=5433 se a 5432 estiver ocupada
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
docker compose down -v                           # reset total
```

### O token precisa vir do Keycloak que a aplicação está validando

É a pegadinha mais fácil de cair, porque **existem dois Keycloak**: o do `docker-compose` (porta 8081,
fixa) e o do Dev Services (porta aleatória, novo a cada `quarkus:dev`). Em dev mode a aplicação usa o do
Dev Services — `quarkus.oidc.auth-server-url` só é configurado no perfil `prod`.

Pegar o token no 8081 e mandar para o `quarkus:dev` dá isto no log, e `unauthorized` para o cliente:

```
JWK with kid '…' is not available
Request http://localhost:<porta-aleatória>/…/token/introspect has failed: 403 "Client not allowed."
```

Não é erro de configuração: é uma assinatura que o emissor daquela aplicação não conhece. O Quarkus tenta
a introspecção como plano B, e o realm não permite (é um cliente público, sem segredo).

Então pegue o issuer da própria aplicação:

```bash
ISS=$(curl -s localhost:8080/q/dev-v1/io.quarkus.quarkus-oidc/provider | grep -o 'http[^"]*realms/axon-posts')
# ou simplesmente: a URL aparece no /q/dev/ e no log da partida

TOKEN=$(curl -s -X POST $ISS/protocol/openid-connect/token \
  -d grant_type=password -d client_id=axon-posts-api \
  -d username=manuel@example.com -d password=segredo123 | jq -r .access_token)
```

Com o JAR empacotado contra o compose, o issuer é fixo: `http://localhost:8081/realms/axon-posts`.

### E precisa ser um *access token*, não o cookie de sessão do Keycloak

O mesmo par de erros (`JWK ... is not available` + `introspect ... 403`) aparece por um segundo motivo, e
esse é mais difícil de ver: quem faz login no console do Keycloak e copia o JWT que está lá copia o
**cookie `KEYCLOAK_IDENTITY`**, que é um JWT legítimo, do issuer certo, e não serve para nada aqui.

Dá para distinguir sem adivinhar — decodifique o payload:

| | cookie de sessão | access token |
|---|---|---|
| `alg` | `HS512` (simétrico, chave **interna** do realm) | `RS256` (a chave publicada no JWKS) |
| `typ` | `Serialized-ID` | `Bearer` |
| claims | `sid`, `state_checker` | `azp`, `scope`, `realm_access.roles`, `preferred_username` |
| validade | 10 horas | 30 minutos |

O `HS512` é a explicação do log: a chave HMAC do realm **nunca** vai para o JWKS, então o `kid` é
genuinamente desconhecido. O Quarkus então tenta a introspecção, e o `axon-posts-api` é cliente público —
403. Um token sem `realm_access.roles` também nunca passaria pelo `@RolesAllowed("author")`.

O access token sai do `grant_type=password` acima, e só de lá.

Usuários semeados (senha `segredo123`): `manuel@example.com` (role `author`), `leitor@example.com`
(sem role), `promovido@example.com` (role `author`, existe para exercitar a promoção `Reader` → `Author`).
O realm habilita `directAccessGrantsEnabled` só para esse grant `password`.

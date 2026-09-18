# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Comandos

Em dev e em teste **não é preciso subir nada**: o Dev Services do Quarkus levanta Postgres e Keycloak
(com o realm importado) sozinho. Basta o Docker ligado.

```bash
./mvnw quarkus:dev             # http://localhost:8080/q/graphql-ui/
./mvnw test                    # suíte inteira — EXIGE Docker
./mvnw package                 # build + testes
./mvnw test -Dtest=PostTest                                       # uma classe
./mvnw test -Dtest=PostLifecycleE2ETest#aNewPostArrivesAlreadyTaggedAtVersionTwo   # um método
./mvnw test -Dtest='*E2ETest'                                     # só os ponta a ponta
open target/jacoco-report/index.html   # cobertura — o quarkus-jacoco roda junto com `test`
```

Federado (Apollo Router na frente). O `-Dquarkus.http.host=0.0.0.0` **não** é detalhe: em dev o Quarkus
escuta só em `127.0.0.1` e o router roda em container — sem isso ele não alcança a aplicação.

```bash
./mvnw quarkus:dev -Dquarkus.http.host=0.0.0.0
rover supergraph compose --config docker/federation/supergraph.yaml > docker/federation/supergraph.graphql
docker compose --profile federation up -d router          # http://localhost:4000
rover supergraph compose --config docker/federation/supergraph-example.yaml   # compõe com um vizinho fictício
```

O `docker-compose.yml` serve para **dois** casos, e só: rodar o JAR empacotado (perfil `prod`) e ter o
console de administração do Keycloak. Os containers levam prefixo `quarkus-` para não colidirem com os do
projeto Spring original, e as portas do host são variáveis (`POSTGRES_PORT`, `KEYCLOAK_PORT`).

```bash
docker compose up -d && ./mvnw package && java -jar target/quarkus-app/quarkus-run.jar
docker compose down -v         # reset total
curl -s localhost:8080/q/health | jq    # inclui "Axon eventprocessors", da extensão
```

`quarkus:dev` recarrega sozinho na próxima requisição depois de uma classe mudar.
**Cada recarga apaga o event store, que é em memória**: as linhas continuam no Postgres, os eventos não.
Um post criado antes da recarga segue respondendo em `post(id:)` e passa a dar `NOT_FOUND` no
`updatePost`, que reidrata o agregado do stream.

Não há plugin de lint/format configurado. O gate de qualidade que existe é o compilador: MapStruct roda
com `-Amapstruct.unmappedTargetPolicy=ERROR`, então um campo de destino sem origem **quebra o build**.

Token para testar à mão:

```bash
TOKEN=$(curl -s -X POST <issuer>/protocol/openid-connect/token \
  -d grant_type=password -d client_id=axon-posts-api \
  -d username=manuel@example.com -d password=segredo123 | jq -r .access_token)
```

Em dev o `<issuer>` é o Keycloak do Dev Services (porta aleatória; veja `/q/dev/`); com o compose é
`http://localhost:8081/realms/axon-posts`. **São dois Keycloak diferentes, e o token de um não vale no
outro**: token do 8081 mandado para o `quarkus:dev` dá `JWK with kid '…' is not available` seguido de
`introspect … 403 "Client not allowed."` no log, e `unauthorized` para o cliente. Não é configuração
faltando — é assinatura de um emissor que aquela aplicação não conhece.

O **mesmo** par de erros tem uma segunda causa: mandar o cookie `KEYCLOAK_IDENTITY` (o JWT que aparece ao
logar no console do Keycloak) em vez de um access token. Decodificar o payload separa os dois casos na
hora — access token é `alg: RS256`, `typ: Bearer`, com `azp`/`scope`/`realm_access.roles`; o cookie é
`alg: HS512`, `typ: Serialized-ID`, com `sid`/`state_checker` e nenhuma role. O `HS512` é a pista: a chave
HMAC do realm não vai para o JWKS, então o `kid` é mesmo desconhecido. Usuários semeados (senha `segredo123`):
`manuel@example.com` (role `author`), `leitor@example.com` (sem role), `promovido@example.com`
(role `author`, existe para exercitar a promoção `Reader` → `Author`).

## Arquitetura

POC de Axon Framework **5** (entidades anotadas + DCB, sem Axon Server) + SmallRye GraphQL com Mutiny e
subscriptions sobre WebSocket. Event store **em memória** (some no restart); Postgres guarda só o read
model; Keycloak é o provedor de identidade e a aplicação é apenas resource server.

É a conversão de um projeto Spring Boot — o README é o documento dessa conversão, decisão por decisão.
**Ao mudar uma decisão, atualizá-lo junto.**

### O fluxo de uma escrita

```
@Mutation (@Valid + @RolesAllowed)
  → PostInputMapper.toCommand(...)                  [MapStruct, compile time]
  → Uni.createFrom().completionStage(() -> commandGateway.send(CreatePost))
     → CreatePostCommand.handle(cmd, @InjectEntity Optional<Post>, EventAppender)
        → Post.create(...)          domínio valida, DISPARA o evento (DomainEventPublisher), devolve o Post
        → posts.save(post)          o command SALVA; o evento e a linha commitam na mesma transação JTA
     → processor "post-projection" (subscribing: mesma thread e transação)
        → PostCreatedEventHandler          emite onPostCreated (o post como nasceu: v1, sem tags)
        → AssignDefaultTagOnPostCreated    agenda em context.onAfterCommit(...) os commands da tag padrão
  → o resolver consulta o post pelo query bus e devolve — já com a tag, na versão 2
```

`onAfterCommit` não é detalhe de estilo: despachar `AssignTagToPost` direto falha com
`EntityNotFoundException`, porque durante o commit do `CreatePost` o `PostCreatedEvent` ainda não é legível
de volta do event store. E como o Axon espera o `CompletableFuture` do after-commit, é o que garante que a
mutation já responda com a tag atribuída — daí **todo post recém-criado nascer na versão 2**.

### Regras de camada (seguir ao adicionar código)

1. **Um arquivo por mensagem, e a classe leva o nome dela.** Não existe classe `…Handler` para
   command/query/subscription: `CreatePostCommand` **é** o command — traz o record da mensagem aninhado
   (`CreatePostCommand.CreatePost`) e o `@CommandHandler` que a trata. Quem despacha importa o tipo
   aninhado. Vale igual para `FindPostQuery.FindPost` e `OnPostUpdatedSubscription.OnPostUpdated`.
2. **Eventos são o inverso: uma classe por reação.** Os eventos de domínio vivem em `domain.*.event` e quem
   os dispara são as entidades, pela porta `DomainEventPublisher`. Quem reage vive em
   `application.post.event`, um arquivo por responsabilidade.
3. **O command decide e salva; o evento notifica e orquestra.** Event handlers não escrevem no banco — um
   emite para as subscriptions, o outro despacha os commands que dão sequência.
4. **A apresentação não alcança `domain` nem `infrastructure`.** Os `@GraphQLApi` falam com o gateway de
   command/query e com a porta `application.auth.AuthenticatedUser` (implementada por
   `infrastructure.security.CurrentUser`). Nada de repositório de domínio num resolver.
5. **Nada de pacote por papel na raiz.** Não existe mais `dto/`, `mapper/` nem `exceptions/` soltos: cada
   tipo mora na camada que o **possui**, e o pacote diz qual é.

### Onde cada coisa mora (e por quê)

```
application/<agregado>/view/     PostView, TagView, UserView…, PostPage, *ViewMapper
interfaces/graphql/api/          os @GraphQLApi — só resolver, nada mais
interfaces/graphql/dto/          CreatePostInput, UpdatePostInput
interfaces/graphql/mapper/       PostInputMapper (input → command)
interfaces/graphql/relay/        Connection/Edge/PageInfo/Connections/Cursors + Post/TagConnection/Edge
interfaces/graphql/error/        GraphQlErrors, @TranslatesErrors, as 4 exceções com @ErrorCode
interfaces/graphql/sse/          GraphQL over SSE: a rota, o handler e o formato do fio
docker/federation/               supergraph.yaml, router.yaml e o subgraph de exemplo (SDL só)
```

A regra que decide: **quem atravessa o bus é da aplicação; quem só existe no schema é da apresentação.**

- Os `*View` são o resultado das queries — atravessam o query bus, entram em `PostPage`, são emitidos
  pelas subscriptions. Por isso são de `application`, e os `*ViewMapper` (entidade → view) com eles. A
  apresentação os **consome**; a dependência aponta para dentro.
- Os `*Input` nunca saem da borda: o `PostInputMapper` os transforma em command antes de qualquer coisa.
  Por isso são de `interfaces`, e o mapper deles também.
- **O que ficou como dívida consciente**: os `*View` carregam anotações do MicroProfile GraphQL
  (`@Name("Post")`, `@Id`, `@Description`) e, desde a federação, o `@Key` — é o que dá o nome do tipo no
  schema e o papel dele na topologia, e é a aplicação sabendo de protocolo. Tirar isso seria duplicar cada view (uma da aplicação, uma do schema) e dobrar os mappers;
  não vale para o tamanho desta POC. Se um dia valer, a fronteira a mexer é o `*ViewMapper`.
- `DataIntegrityTranslator` fica em `error/` apesar de conhecer o Hibernate: ele é parte do mecanismo de
  **classificação**, e quem o chama é o `GraphQlErrors`. Movê-lo para `infrastructure` criaria a única
  dependência de apresentação → infraestrutura do projeto, que é justamente o que a regra 4 evita.

### Uma classe por entidade

`Post`, `Tag` e `User` são, cada uma, `@Entity` (JPA) + `@EventSourcedEntity` (Axon) + comportamento de
domínio na mesma classe. Não existe entidade de infraestrutura espelho; os value objects são
`@Embeddable` records que validam no construtor canônico. Consequências que importam ao editar:

- A entidade é **mutável** (o JPA exige construtor sem argumentos e campos não-finais), mas sem setters
  públicos: só eventos mudam estado, e só decisões produzem eventos.
- **`@EventSourcingHandler` tem de ser idempotente.** O mesmo evento chega por dois caminhos (o domínio o
  aplica ao decidir; o Axon o aplica ao apendar). Por isso todo campo do evento é **valor absoluto**,
  inclusive a versão resultante — nada de `version.next()` dentro do `on(...)`. Travado por
  `applyingTheSameEventTwiceLeavesTheSameState`.
- **Decidir termina chamando evoluir**: `Post.create(...)` termina no `@EntityCreator` e `update`/`assignTag`
  terminam em `on(evento)`, os mesmos caminhos do replay. Travado por
  `theStateReturnedByUpdateIsTheSameAsSourcingTheRaisedEvent`.
- **Eventos carregam primitivos** — são contrato, ficam gravados. A conversão para value object acontece nas
  fronteiras da entidade.
- O tipo do id da entidade **não** está numa anotação: ele é o primeiro argumento de
  `EventSourcedEntityModule.autodetected(PostId.class, Post.class)`, e quem o fornece é o mapa de
  `infrastructure/axon/EventSourcedEntities`. No starter do Spring era o `idType` do `@EventSourced`, que
  só existia para o scan ter onde lê-lo.

### Identidade e autorização

- **Nenhum usuário é cadastrado na aplicação.** `UserProvisioning` cria o perfil *just-in-time* na primeira
  requisição com um token novo, ou liga a conta a um usuário existente pelo e-mail (account linking).
- **`User` é agregado polimórfico**: `@EventSourcedEntity(concreteTypes = {Reader, Author})`, e o tipo
  concreto é função pura do histórico. O Axon fixa o tipo na criação, então **promover é encerrar um
  agregado e abrir outro** (`UserSupersededEvent` + novo `UserRegisteredEvent` com `supersedes` +
  `LinkAccount` por credencial). São três unidades de trabalho: a janela entre elas é detectável e o
  `UserProvisioning` a conserta no login seguinte (`resumeInterruptedPromotion`), sem saga nem job.
- **A autenticação não é proativa; quem autoriza é o método.** Um endpoint GraphQL é um caminho HTTP só, e
  `quarkus.http.auth.proactive=false` é o que deixa `post`/`posts` públicas no mesmo POST em que
  `me`/`createPost` exigem token. Toda mutation de escrita leva `@RolesAllowed(Role.AUTHOR_CLAIM)` — e uma
  mutation nova sem ela aparece porque `AuthorizationE2ETest` é parametrizado sobre a lista de operações.
  **Ao adicionar uma mutation, acrescentá-la lá.**
- **Sem prefixo de role.** O Quarkus põe `realm_access.roles` no `SecurityIdentity` como está, então o
  literal do `@RolesAllowed` é o que o realm emite (`author`). A constante `Role.AUTHOR_CLAIM` existe
  porque anotação exige literal em tempo de compilação — mantê-la ao lado do enum é o que torna a
  divergência visível.
- **Ser autor autoriza a escrever, não a escrever no alheio.** A checagem de "é o autor deste post" está no
  domínio (`Post.assertWrittenBy`), não no resolver: depende do estado do agregado, então é invariante.
- Exceções de domínio viram código de erro em `interfaces/graphql/error/GraphQlErrors` — um `if` por família, percorrendo
  a cadeia de causas. Quem o chama é o `ErrorTranslationInterceptor`, um interceptador CDI ligado por
  `@TranslatesErrors` **na classe** de cada `@GraphQLApi` — o SmallRye pede o resolver ao CDI e recebe o
  client proxy, então a cadeia de interceptadores vale ali como em qualquer bean. **Classe nova de resolver
  = a anotação na classe**, e não uma linha por método. Sem ela os erros saem como "System Error".
  Consequência de ter subido do corpo do método para a volta dele: o caminho síncrono passa a ser
  traduzido — um `@Valid` que estoura agora sai `BAD_REQUEST`, e não mais `ValidationError` sem `code`.
- **A prioridade do interceptador é `PLATFORM_BEFORE + 100`, e o número importa.** Os interceptadores de
  segurança do Quarkus são `@Priority(150)`; ficar em `APPLICATION` (2000) rodava por dentro deles e
  deixava as recusas saírem com `"message": null` (as exceções do Quarkus não têm mensagem) e `code` em
  minúsculas. Por fora, viram `UNAUTHORIZED`/`FORBIDDEN` com mensagem, e o log troca a pilha do Mutiny +
  `CompositeException` + `CIRCULAR REFERENCE` por uma linha. `AuthorizationE2ETest` trava as duas coisas —
  o código **e** a mensagem.
- Por isso as exceções `io.quarkus.security.*` **saíram** de `show-runtime-exception-message`: elas não
  chegam mais ao cliente. Só as quatro do projeto ficam lá.
- **Limite conhecido**: token com assinatura inválida é recusado pelo `HttpAuthenticator` antes do
  resolver — `HTTP 401` com corpo vazio, sem `errors`. Token *ausente* vira erro de GraphQL normal.

### Schema e persistência

- **O schema do banco vem do Flyway** (`src/main/resources/db/migration`), e o Hibernate roda em
  `schema-management.strategy=validate`: entidade nova sem migration **não sobe**. Os testes rodam as
  mesmas migrations.
- `baseline-on-migrate` é `false` de propósito. `clean-at-start` só em `%test` — em dev o banco pode ser o
  do compose, com dados que alguém quer manter.
- Índices que nenhuma anotação JPA expressa vivem só no SQL — o principal é `uk_users_email_active`:
  e-mail único **entre os ativos**, porque um leitor encerrado e o autor que o substituiu convivem com o
  mesmo e-mail.
- Exclusão lógica por `@SQLDelete` + `@SQLRestriction`. Efeito colateral com teste próprio: apagar a conta
  **esconde os posts do autor**, porque `Post.author` é `@ManyToOne(optional = false)` contra uma linha
  filtrada.
- **Dois arquivos por agregado na persistência**: o adapter da porta (`PanachePostRepository`) e o
  repositório Panache pacote-visível (`PostPanache`). A separação não é estilo: `PanacheRepositoryBase`
  declara `findById(Id)` devolvendo a entidade e a porta do domínio devolve `Optional` — mesma assinatura,
  retornos incompatíveis. É o mesmo par adapter + driver que o projeto Spring tinha por escolha.
- **`merge`, nunca `persist`.** A entidade vem reconstituída dos eventos pelo Axon: é sempre *detached*,
  exista a linha ou não.

### GraphQL

- **Schema code-first.** Não há `.graphqls`; o SDL é gerado e servido em `/graphql/schema.graphql`. O
  `src/main/resources/schema.graphql` da raiz é uma cópia versionada dele (nada o lê em runtime); atualizar com
  `curl -s http://localhost:8080/graphql/schema.graphql > schema.graphql` ao mexer no contrato. Ele traz
  `@link`/`@key`/`@shareable` porque `schema-include-directives` e `schema-include-schema-definition` estão
  ligados — as duas linhas existem para que esse arquivo **componha** sem a aplicação de pé, e tirar
  qualquer uma delas faz o `rover` ler o subgraph como Federação 1. `FederationSchemaTest` trava isso. Por
  isso os DTOs levam `@Name`/`@Input`: `PostView` → `Post`, `CreatePostInput` → `CreatePostInput` (sem a
  anotação viraria `CreatePostInputInput`).
- **Os acessores de `UserView` levam `@Name`, e isso não é redundância.** O `InterfaceCreator` do SmallRye
  só considera campo de interface o método que parece getter (`getX()`) *ou* que traz `@Name`. Estes
  acessores são estilo record. Sem a anotação a interface sai com zero campos, e uma interface sem campos é
  **descartada em silêncio** — o erro aparece na partida como `type User not found in schema`.
- **Cursor connections em `interfaces/graphql/relay`**, escritas uma vez: `Connection<N, E extends Edge<N>>` e
  `Edge<N>` genéricos, com uma subclasse concreta de **uma linha** por tipo paginado
  (`PostEdge extends Edge<PostView>`). A subclasse é o que dá ao schema o nome da convenção Relay —
  um genérico instanciado viraria `Edge_Post`. Campo `…Connection` novo = duas linhas, e o
  `RelaySchemaTest` confere o resultado no SDL.
- **Campos com argumentos usam `@Source List<T>`**, inclusive paginados: o `BatchDataFetcher` do SmallRye
  passa os argumentos do campo no contexto do lote. É o que permitiu `Post.tags` e `Author.posts` serem
  dois métodos em vez das duas classes de `BatchLoaderRegistry` do projeto Spring.
- Coleções resolvidas por lote são `LAZY` de propósito: com `EAGER` o N+1 aconteceria antes de o lote
  entrar em cena.
- **Subscriptions**: `subscriptionQuery(...)` do `QueryGateway` do núcleo devolve um `Publisher` de
  Reactive Streams, adaptado para `Multi` com `FlowAdapters.toFlowPublisher`. O `@QueryHandler` da
  subscription **precisa existir** (devolve `Optional.empty()`). O filtro por tópico é avaliado no `emit`:
  o payload da subscription carrega o próprio predicado.
- **Dois transportes no mesmo `/graphql`, escolhidos por cabeçalho.** `Upgrade: websocket` →
  `graphql-transport-ws`/`graphql-ws`, que vem do SmallRye. `Accept: text/event-stream` → GraphQL over
  SSE, que **não** vem: o SmallRye 2.18.5 e a extensão do Quarkus 3.39 não têm uma linha de
  `event-stream`, e `interfaces/graphql/sse` é a porta escrita aqui (modo *distinct connections* do
  `graphql-sse`). O handler herda de `SmallRyeGraphQLAbstractHandler` — a mesma classe do handler HTTP e
  do de WebSocket do Quarkus —, e é isso que faz contexto de requisição, `SecurityIdentity`,
  `@RolesAllowed` e tradução de erro valerem igual nas três portas. Dependência consciente de um pacote
  `runtime` de extensão, que não é API pública.
- **A GraphiQL do Quarkus fala WebSocket, sempre — não é bug do SSE.** O `render.js` do webjar traz
  `Accept: application/json` nos headers padrão e `subscriptionUrl: getWsUrl()` fixo, e o `updateUrl` do
  `SmallRyeGraphQLProcessor` só reescreve `const api`/`const logo`. Sem `subscriptionUrl` o
  `createGraphiQLFetcher` **lança** em subscription em vez de cair em SSE. Exercitar a porta de SSE é
  `curl -N` ou `new EventSource('/graphql?query=subscription{...}')` no console; quem a trava de verdade
  é o `SseSubscriptionE2ETest`. **Não** tratar "a UI usa ws" como sinal de que o SSE quebrou.
- **A ordem da rota de SSE é o que quebra, e o número não é chutável.** O Quarkus numera as rotas da
  aplicação em **sequência** (WebSocket `-99`, schema `2`, execução `4`), não em 10 000. Um `order` alto
  cai depois do handler de execução, que devolve `406 Not Acceptable` a quem pediu `text/event-stream` —
  a rota nova simplesmente não roda. Daí ser `-SecurityHandlerPriorities.AUTHORIZATION + 2`. E como o
  Vert.x Web **pausa** a requisição ao rotear e esta rota não tem `BodyHandler` (de propósito: ela devolve
  com `ctx.next()` o que não é SSE, e o corpo seria lido duas vezes), falta o `request.resume()` — sem
  ele o POST fica pendurado. `SseSubscriptionE2ETest` trava as duas coisas, inclusive que o POST JSON de
  sempre continua respondendo `application/graphql-response+json`.
- **`.onOverflow().buffer(...)` depois do `publisher(...)` é obrigatório, não afinamento.** O `Publisher`
  do `subscriptionQuery` **não honra demanda incremental**: com `request(Long.MAX_VALUE)` entrega tudo,
  com `request(1)` a cada item — que é o que o `SubscriptionSubscriber` do SmallRye faz — entrega o
  primeiro e para. O buffer separa as duas demandas (Mutiny pede ilimitado ao Axon e serve o assinante do
  próprio buffer). Falha em silêncio: handshake completa, o primeiro evento chega, a conexão fica aberta.
  **Subscription nova = o operador junto**, e `NewsletterSubscriptionE2ETest.theSameSubscriptionKeeps...`
  é o que pega a falta dele — junto com o irmão dele em `SseSubscriptionE2ETest`, porque o assinante de
  SSE pede um item de cada vez pelo mesmo motivo e cai na mesma armadilha.
- **Validação em duas alturas**: Bean Validation nos `*Input` é fail-fast de borda (`BAD_REQUEST` antes de
  existir command); os value objects continuam validando por conta própria, e é essa a validação que vale.
  Em update parcial usar `@Pattern`, não `@NotBlank` — constraints são ignoradas quando o valor é `null`.
- **Bloqueio fora do event-loop**: `SimpleCommandBus`/`SimpleQueryBus` executam na thread que despacha e lá
  dentro tem JPA bloqueante. Todo despacho de resolver é
  `Uni.createFrom().completionStage(() -> gateway...).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())`,
  escrito no próprio resolver. **O `Supplier` é obrigatório**: a sobrecarga que recebe o
  `CompletableFuture` pronto executa o gateway no event-loop, e o offload vira decorativo. **Não** chamar
  o gateway direto, sem o `runSubscriptionOn`. A explicação completa está no `package-info` de
  `interfaces/graphql`.
- `version` no `PostView` é `int` e não `long`: a especificação MicroProfile GraphQL mapeia `long` para o
  scalar `BigInteger`, e o campo é `Int!`.
- **Teto de profundidade**: `quarkus.smallrye-graphql.instrumentation-query-depth=20`. O default do
  SmallRye é **10**, e 10 quebra a introspecção da GraphiQL (profundidade 15) e a query Relay mais funda
  do schema (11). Falha de um jeito enganoso — a aplicação sobe, o SDL é servido, query rasa responde, e
  só a UI abre em branco. `SchemaIntrospectionTest` trava os dois casos; baixar o número derruba ele.

### Federação (Apollo Federation 2)

A aplicação é um **subgraph**. O SmallRye serve `_service { sdl }` e `_entities(representations:)`;
o que é daqui são as anotações nas views, os `*EntityApi` e duas linhas de `application.properties`
(`federation.enabled`, `federation.batch-resolving-enabled`). `docker/federation/` tem o
`supergraph.yaml`, o `router.yaml` e um subgraph vizinho escrito só como SDL.

Entidades e chaves: `Post`, `Tag`, `Author`, `Reader` e a **interface** `User`, todas por `id`.
`PageInfo` leva `@Shareable` — é o único tipo que outro subgraph também define.

**ENTIDADE NOVA = QUATRO COISAS, e faltar qualquer uma quebra em runtime, não na compilação:**

1. `@Key(fields = @FieldSet("id"))` na *view* (é ela que vira o `type` do schema);
2. um `<X>EntityApi` em `interfaces/graphql/api/` com um `@Resolver` em lote;
3. uma query `Find<X>sByIds` em `application/<agregado>/query/`, devolvendo **mapa** por id;
4. `findAllById` na porta do repositório + o método no `*Panache` + o duplo em memória de `support/`.

As quatro armadilhas do `@Resolver`, todas silenciosas:

- **o argumento precisa se chamar `id`** (ou o que estiver no `@Key`). O casamento é por *tipo de retorno
  + conjunto de nomes de argumento*, não por nome de método. `postId` compila e o `_entities` fica sem
  resolvedor;
- **o argumento em lote NÃO leva `@Id`.** O `ReferenceCreator` testa `@Id` antes de desembrulhar a
  coleção: com ele, o tipo esperado vira `ID` de `java.util.List` e cada id é lido como JSON. O que chega
  ao cliente é `NullPointerException: resultList is null`, sem menção a argumento;
- **o elemento da lista NÃO leva `@NonNull`.** Com `[Post!]` o casamento por tipo de retorno falha — o
  `FederationDataFetcher` espera um tipo *nomeado* depois de desembrulhar a lista — e o lote deixa de ser
  usado sem um log;
- **uma posição por representação, na ordem recebida**, com `null` onde não existe. Por isso o resolvedor
  projeta a lista de ids sobre o mapa da query, em vez de devolver o que veio do banco.

Tipo polimórfico precisa de **um `@Resolver` por tipo concreto mais um para a interface** (`UserEntityApi`
tem três): `List<UserView>` e `List<AuthorView>` são o mesmo apagamento em Java e três tipos GraphQL
diferentes, e é o tipo GraphQL que o casamento usa. Pedir o tipo errado responde `null`, nunca o outro
tipo.

O `@Link` mora sozinho em `FederatedSchemaApi`, e **só pode haver um** — repetir o `@link` da Federação
em outra classe `@GraphQLApi` derruba a aplicação na partida. Diretiva usada e não importada sai
prefixada (`@federation__key`): ao usar uma nova, acrescentar o `@Import` junto. A versão é literal de
propósito; não trocar por `Link.FEDERATION_SPEC_LATEST_URL`.

**Dois erros VERMELHOS no editor são falso positivo, e não se conserta no código.** O plugin Quarkus Tools
(Red Hat) acusa, via LSP4IJ:

```
Directive 'io.smallrye.graphql.api.federation.Key' is not allowed on element type 'INTERFACE'   UserView
Directive 'io.smallrye.graphql.api.federation.link.Link' is not allowed on element type 'SCHEMA' FederatedSchemaApi
```

As duas anotações declaram exatamente essas posições (`@Directive(on = {OBJECT, INTERFACE})` e
`on = {SCHEMA}`), e o SDL gerado prova que funcionam. O bug está no
`MicroProfileGraphQLASTValidator`, que lê os valores do `on` assim:

```java
name = init.getText().substring(init.getText().indexOf(".") + 1);   // indexOf, não lastIndexOf
```

Lido de um .class de biblioteca, o texto vem qualificado
(`io.smallrye.graphql.api.DirectiveLocation.INTERFACE`); o `indexOf(".")` para no ponto de `io.` e sobra
`smallrye.graphql.api.DirectiveLocation.INTERFACE`, que não casa com nada. Só aparece nessas duas porque
o validador **não checa `OBJECT`** — por isso `@Key` nos records (`PostView`, `TagView`…) passa calado.

Não há conserto pelo código: as duas posições são as únicas que o SmallRye aceita, e `@SuppressWarnings`
não pega (é diagnóstico de language server, não inspeção — nem `"ALL"` silencia). Quem incomodar,
desliga em **Settings → Languages & Frameworks → MicroProfile → Validation** (guardado em
`.idea/microProfileSettings.xml`, que não é versionado). **Não remover o `@Key` da interface nem mudar o
`@Link` de lugar para calar a IDE** — seria trocar uma capacidade real por um aviso errado.

`@Blocking`/`@NonBlocking`/`@RunOnVirtualThread` **não** podem ser combinadas com `@Resolver`. Não é
problema aqui: o offload é escrito no corpo do método (`runSubscriptionOn`), como em todo resolver.

**`_entities` é público e resolve qualquer entidade pela chave, sem token** — é a premissa da Federação
(o subgraph fica interno, o roteador é a fronteira). Consequência concreta: `Reader`, que não era
alcançável anonimamente, agora é. Não publicar esta aplicação direto na internet.

### Configuração de infraestrutura

**Quem configura o Axon é a extensão de Quarkus** `at.meks.quarkiverse.axonframework-extension`
(`quarkus-axon` + `quarkus-axon-transaction`, versão no `${quarkus-axon.version}` do pom). Ela descobre
entidades, command/query/event handlers em **build time** e publica gateways e buses como beans. O
`AxonProducer`, o `AxonHandlerLookup` e o `JtaTransactionManager` escritos à mão **não existem mais** —
539 linhas viraram 78. O README tem a avaliação da troca, inclusive o que piorou.

Sobrou em `infrastructure/axon` o que a extensão não tem como adivinhar, um arquivo por decisão:

- **`EventSourcedEntities`** — o tipo do id de cada entidade, implementando o
  `EventSourcedEntityConfigurer`. **Entidade nova = uma linha no mapa.** A alternativa que a extensão
  oferece é `@IdType(PostId.class)` na entidade, e ela está **descartada de propósito**: seria a primeira
  dependência de `domain` para uma biblioteca de plataforma.
- **`ApplicationClock`** — o `Clock` como bean, para poder ser fixo em teste.

Duas linhas de `application.properties` valem tanto quanto código, e as duas falham em silêncio:

1. `quarkus.axon.subscribingprocessor.namespaces` — a lista de pacotes que rodam **subscribing**. O valor
   é o nome do pacote porque a extensão lê `@Namespace` da *classe* e cai no pacote como default (por isso
   o `@Namespace` do `package-info.java` saiu: ali não tinha mais efeito).
   **Handler novo no pacote já listado: nada a fazer. Handler num PACOTE NOVO: mais um item na lista.**
   Quem fica de fora não dá erro — vai para um `PooledStreamingEventProcessor`, assíncrono, e a projeção
   daquele agregado vira eventualmente consistente sem ninguém pedir. É falha silenciosa, e quem a pega é
   `AxonWiringTest.everyPackageWithAnEventHandlerRunsInTheSubscribingProcessor`.
   **A ordem é handler primeiro, propriedade depois**: namespace listado sem nenhum handler faz a
   aplicação não subir, com `NullPointerException` na partida (`getEventhandlers` faz
   `map(mapa::get).flatMap(...)` sobre um `null`).
2. Nenhuma linha de event store — o **default da extensão é o em memória**, que é a escolha do projeto.
   Trocar por Postgres é acrescentar `quarkus-axon-jpa-eventstore`.

**As substituições funcionam por ausência**, e é isso que `AxonWiringTest` trava: a extensão declara
`@DefaultBean`s e cede a vez a quem existir. Apagar `EventSourcedEntities` ou tirar o
`quarkus-axon-transaction` do pom não quebra compilação — volta o `String` como id e o
`NoTransactionManager`, que não commita o evento junto com a linha.

Herdados da extensão, com efeito visível:

- exceção de domínio chega ao resolver dentro de uma `CommandExecutionException`
  (`quarkus.axon.exception-handling.wrap-on-command-handler`, `true` por default). Nada quebrou porque
  `GraphQlErrors` e `PostCommandFixtures.hasCause` percorrem a **cadeia**;
- `/q/health` traz `Axon eventprocessors` (daí o `quarkus-smallrye-health` no pom);
- `quarkus.axon.update-check.disabled=true` desliga a chamada de rede da AxonIQ na partida.

**Live reload é o ponto fraco.** Já foi observado, uma vez e sem reprodução depois, a projeção parar em
silêncio após um reload: post nasce na versão 1, sem tag, e o log não reclama. Se acontecer, reiniciar o
`quarkus:dev`. O botão que a extensão documenta para o sintoma vizinho ("no command handler available") é
`quarkus.axon.live-reload.shutdown.wait-duration.amount`; ele **não** é usado aqui porque não se provou
que resolve este caso.

## Testes

Surefire roda tudo em `./mvnw test`, inclusive os `*E2ETest` — **Docker precisa estar de pé**.

- **Domínio puro** (`PostTest`, `TagTest`, `SoftDeletableTest`, `AuthenticatableTest`): sem Axon, sem CDI,
  sem JPA. O único colaborador é `RecordingDomainEvents`, um duplo da porta do próprio domínio. Estes
  testes atravessaram a conversão **sem uma linha alterada**.
- **Command** (`*CommandTest`): `AxonTestFixture` given-when-then, um por command, com repositório em
  memória — que é como se prova que o command salvou, e o quê.
- **Relay** (`ConnectionsTest`): cursor ↔ offset, prefixo de tipo, teto de página e montagem da connection.
  No projeto Spring essa parte era do framework; aqui é código nosso, então aqui tem teste.
- **Schema** (`RelaySchemaTest`): lê o SDL gerado e falha se `Connection_`/`Edge_` voltarem a aparecer, ou
  se a `interface User` sumir. É o guarda do mecanismo dos genéricos, que nenhum compilador confere.
- **Wiring do Axon** (`AxonWiringTest`): três coisas que a extensão decide por default e que este projeto
  decide diferente — o `TransactionManager`, o tipo do id de cada entidade, e a cobertura de
  `subscribingprocessor.namespaces`. As duas primeiras valem por **ausência** de `@DefaultBean` (sumir não
  quebra compilação); a terceira varre o `BeanManager` atrás de `@EventHandler` e falha se algum pacote
  ficou de fora da propriedade.
- **Federação** (`FederationSchemaTest`, `FederationEntitiesE2ETest`): o primeiro lê o `_service { sdl }`
  — que é o que o `rover` leria, e onde `@key`/`@shareable` aparecem, coisa que a introspecção não mostra.
  O segundo chama o `_entities` de verdade: é o único lugar onde um argumento renomeado, um `@Id` a mais
  ou um `@NonNull` no elemento da lista falham. Inclui o custo, pela mesma propriedade do
  `BatchLoadingE2ETest`: N representações precisam custar os mesmos statements que 1.
- **Ponta a ponta** (`e2e/*`): Dev Services sobem Postgres e Keycloak; uma única aplicação é compartilhada
  por todas as classes. Cada método começa com `truncate ... cascade` (o event store em memória fica; todo
  id é UUID novo). Uma subclasse que acrescente `@TestProfile` ganha aplicação própria e a suíte paga
  outra subida.
- **`@QuarkusTest` vai em cada classe concreta**, não na base `AbstractGraphQlE2ETest`: é a anotação que
  registra a classe de teste como bean para os `@Inject` dela. Só na base, cada subclasse falha com
  "No bean found for required type".
- O realm de teste é **o mesmo arquivo** que o compose monta (`docker/keycloak/realm-*.json` entra no
  classpath pelo `<resources>` do pom, e o `quarkus.keycloak.devservices.realm-path` o aponta) — duas
  cópias divergiriam em silêncio.
- `BatchLoadingE2ETest` afere o lote pela estatística do Hibernate: a mesma query com 1 e com 5 posts
  precisa custar o **mesmo número de statements** — a propriedade, não um número mágico.
- Asserção sobre exceção do gateway percorre a **cadeia** (`PostCommandFixtures.hasCause`), não a raiz: o
  Quarkus entregava a exceção crua onde o Spring entregava embrulhada — e com a extensão ela volta
  embrulhada numa `CommandExecutionException`. Percorrer a cadeia é o que sobrevive às duas formas;
  `rootCause()` do AssertJ, não.

## Convenções

- Javadoc e comentários em **português**, e explicam *por quê*, não *o quê*. O README é o documento de
  decisões de arquitetura — ao mudar uma decisão, atualizá-lo junto.
- Mensagens de commit em inglês, estilo conventional commits (`feat:`, `chore:`).
- Construtores nomeados em vez de `new` público: `PostId.of(...)` / `PostId.newId()`,
  `PostVersion.initial()` / `next()`, `Post.create(...)`, `AppendingDomainEventPublisher.appendingTo(...)`.
- MapStruct é o mapper padrão de todas as camadas; mapeamento à mão só quando o MapStruct não resolve
  (`PostViewMapper` precisa de `expression = "java(...)"` porque a entidade tem acessores `title()`, não
  `getTitle()`; `UserViewMapper` é à mão porque o destino depende do tipo em runtime).
  Nenhum `@Mapper` declara `componentModel`: o `pom.xml` passa
  `-Amapstruct.defaultComponentModel=jakarta-cdi` para todos.
- **Injeção por construtor, sem `@Inject`**: o ArC usa o único construtor com parâmetros. É o que mantém as
  classes de aplicação idênticas às da versão Spring.
- O nome da mensagem no wire vem da anotação (`@Command(namespace, name, version)`), não da classe — mover
  ou renomear a classe Java não muda contrato; mexer na anotação, sim.

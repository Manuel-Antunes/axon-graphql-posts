# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Comandos

Infra primeiro (Postgres com dois bancos + Keycloak com o realm importado); o app não sobe sem ela:

```bash
docker compose up -d           # esperar o healthcheck do Keycloak (~30s na primeira vez)
./mvnw spring-boot:run         # http://localhost:8080/graphiql
docker compose down -v         # reset total (volume + init scripts do Postgres)
```

```bash
./mvnw package                 # build + testes
./mvnw test                    # suíte inteira — EXIGE Docker rodando (Testcontainers)
./mvnw test -Dtest=PostTest                                  # uma classe
./mvnw test -Dtest=PostLifecycleE2ETest#aNewPostArrivesAlreadyTaggedAtVersionTwo   # um método
./mvnw test -Dtest='*E2ETest'                                # só os ponta a ponta
bash scripts/poc-smoke.sh      # roteiro manual: builda, sobe, abre SSE, dispara mutations (.poc-logs/)
```

Não há plugin de lint/format configurado. O gate de qualidade que existe é o compilador: MapStruct roda
com `-Amapstruct.unmappedTargetPolicy=ERROR`, então um campo de destino sem origem **quebra o build**.

Token para testar à mão (o realm habilita `directAccessGrantsEnabled` só por isso):

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/axon-posts/protocol/openid-connect/token \
  -d grant_type=password -d client_id=axon-posts-api \
  -d username=manuel@example.com -d password=segredo123 | jq -r .access_token)
```

Usuários semeados (senha `segredo123`): `manuel@example.com` (role `author`), `leitor@example.com`
(sem role), `promovido@example.com` (role `author`, existe para exercitar a promoção `Reader` → `Author`).

## Arquitetura

POC de Axon Framework **5** (entidades anotadas + DCB, sem Axon Server) + extensão Reactor + Spring for
GraphQL com subscriptions sobre SSE. Event store **em memória** (some no restart); Postgres guarda só o
read model; Keycloak é o provedor de identidade e a aplicação é apenas resource server.

### O fluxo de uma escrita

```
@MutationMapping (@Valid + @PreAuthorize)
  → PostInputMapper.toCommand(...)                  [MapStruct, compile time]
  → ReactorCommandGateway.send(CreatePost)
     → CreatePostCommand.handle(cmd, @InjectEntity Optional<Post>, EventAppender)
        → Post.create(...)          domínio valida, DISPARA o evento (DomainEventPublisher), devolve o Post
        → posts.save(post)          o command SALVA; o evento e a linha commitam no mesmo ProcessingContext
     → processor "post-projection" (subscribing: mesma thread e transação)
        → PostCreatedEventHandler          emite onPostCreated (o post como nasceu: v1, sem tags)
        → AssignDefaultTagOnPostCreated    agenda em context.onAfterCommit(...) os commands da tag padrão
  → controller consulta o post pelo query bus e devolve — já com a tag, na versão 2
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
4. **A apresentação não alcança `domain` nem `infrastructure`.** Controllers falam com o gateway de
   command/query e com a porta `application.auth.AuthenticatedUser` (implementada por
   `infrastructure.security.CurrentUser`). Nada de repositório de domínio no controller.
5. `dto/`, `mapper/` e `exceptions/` na raiz são agrupados **por papel**, não por camada: um lugar só para
   procurar "todo input", "todo mapeamento", "toda tradução de erro".

### Uma classe por entidade

`Post`, `Tag` e `User` são, cada uma, `@Entity` (JPA) + `@EventSourced` (Axon) + comportamento de domínio
na mesma classe. Não existe entidade de infraestrutura espelho; os value objects são `@Embeddable` records
que validam no construtor canônico. Consequências que importam ao editar:

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
- Agregado referencia agregado por identidade quando o limite de consistência importa (o `PostUpdatedEvent`
  carrega um record próprio de tag, não o tipo de outro agregado).

### Identidade e autorização

- **Nenhum usuário é cadastrado na aplicação.** `UserProvisioning` cria o perfil *just-in-time* na primeira
  requisição com um token novo, ou liga a conta a um usuário existente pelo e-mail (account linking).
- **`User` é agregado polimórfico**: `@EventSourced(concreteTypes = {Reader, Author})`, e o tipo concreto é
  função pura do histórico. O Axon fixa o tipo na criação, então **promover é encerrar um agregado e abrir
  outro** (`UserSupersededEvent` + novo `UserRegisteredEvent` com `supersedes` + `LinkAccount` por
  credencial). São três unidades de trabalho: a janela entre elas é detectável e o `UserProvisioning` a
  conserta no login seguinte (`resumeInterruptedPromotion`), sem saga nem job.
- **A cadeia de segurança é `permitAll`; quem autoriza é o método.** Um endpoint GraphQL é um caminho HTTP
  só. Toda mutation de escrita leva `@PreAuthorize("hasRole('AUTHOR')")` — e uma mutation nova sem ela
  aparece porque `AuthorizationE2ETest` é parametrizado sobre a lista de operações. **Ao adicionar uma
  mutation, acrescentá-la lá.**
- **Ser autor autoriza a escrever, não a escrever no alheio.** A checagem de "é o autor deste post" está no
  domínio (`Post.assertWrittenBy`), não no controller: depende do estado do agregado, então é invariante.
- Exceções de domínio viram `ErrorType` do GraphQL em `exceptions/AppGraphQlExceptionHandler` — um `if` por
  família, percorrendo a cadeia de causas.

### Schema e persistência

- **O schema vem do Flyway** (`src/main/resources/db/migration`), e o Hibernate roda em `ddl-auto: validate`:
  entidade nova sem migration **não sobe**. Os testes de integração rodam as mesmas migrations.
- `baseline-on-migrate` é `false` de propósito. Banco de dev sujo → `docker compose down -v`.
- Índices que nenhuma anotação JPA expressa vivem só no SQL — o principal é
  `uk_users_email_active`: e-mail único **entre os ativos**, porque um leitor encerrado e o autor que o
  substituiu convivem com o mesmo e-mail.
- Exclusão lógica por `@SQLDelete` + `@SQLRestriction`. Efeito colateral com teste próprio: apagar a conta
  **esconde os posts do autor**, porque `Post.author` é `@ManyToOne(optional = false)` contra uma linha
  filtrada.

### GraphQL

- **Cursor connections montadas pelo Spring, não à mão**: `ScrollSubrange` como parâmetro, `Window<...>`
  como retorno, e o `ConnectionTypeDefinitionConfigurer` **gera** `PostConnection`/`PostEdge`/`PageInfo` a
  partir do sufixo `Connection` — por isso o `.graphqls` declara os campos mas não esses tipos.
  `interfaces/graphql/Connections` faz cursor ↔ offset e o recorte em memória, compartilhado por todas.
  Ordenação `createdAt, id`; só paginação para frente (`first`/`after`).
- **Campos com argumentos usam `@SchemaMapping` + DataLoader explícito**, não `@BatchMapping`: o
  `BatchLoaderHandlerMethod` não enxerga `@Argument` nem `ScrollSubrange`. É o caso de `Post.tags` e
  `Author.posts`. Os loaders despacham *queries* pelo bus (`FindTagsByPostIds`, `FindPostsByAuthorIds`,
  `FindUsersByIds`) — não vão ao repositório.
- Coleções resolvidas por loader são `LAZY` de propósito: com `EAGER` o N+1 aconteceria antes de o
  DataLoader entrar em cena.
- **Subscriptions**: `subscriptionQuery(...)` do `ReactorQueryGateway` alimenta o `@SubscriptionMapping`. No
  Axon 5 o `@QueryHandler` da subscription **precisa existir** (devolve `Optional.empty()`). O filtro por
  tópico é avaliado no `emit`: o payload da subscription carrega o próprio predicado.
- **Validação em duas alturas**: Bean Validation nos `*Input` é fail-fast de borda (`BAD_REQUEST` antes de
  existir command); os value objects continuam validando por conta própria, e é essa a validação que vale.
  Em update parcial usar `@Pattern`, não `@NotBlank` — constraints são ignoradas quando o valor é `null`.
- **Bloqueio fora do event-loop**: `SimpleCommandBus`/`SimpleQueryBus` executam na thread que despacha e lá
  dentro tem JPA bloqueante, então os controllers fazem `subscribeOn(Schedulers.boundedElastic())`.

### Configuração de infraestrutura

`infrastructure/axon/AxonConfig` concentra as escolhas de deploy: `InMemoryEventStorageEngine`,
`InMemoryTokenStore`, `EventProcessorDefinition.subscribingMatching("post-projection")` e o `Clock` (bean
para poder ser fixo em teste). Os autoconfigs JPA/JDBC do Axon estão **excluídos** no `application.yml` para
que nenhuma tabela do Axon apareça no Postgres — trocar o event store é mexer nesses dois lugares.

O `@Namespace("post-projection")` está no `package-info.java` de `application/post/event`, não em cada
classe: handler novo no pacote entra no processor sem tocar em configuração.

## Testes

Surefire roda tudo em `./mvnw test`, inclusive os `*E2ETest` — **Docker precisa estar de pé**.

- **Domínio puro** (`PostTest`, `TagTest`, `SoftDeletableTest`, `AuthenticatableTest`): sem Axon, sem Spring,
  sem JPA. O único colaborador é `RecordingDomainEvents`, um duplo da porta do próprio domínio.
- **Command** (`*CommandTest`): `AxonTestFixture` given-when-then, um por command, com repositório em
  memória — que é como se prova que o command salvou, e o quê.
- **Ponta a ponta** (`e2e/*`): um Postgres e um Keycloak estáticos para a JVM inteira (`support/Containers`,
  `Startables.deepStart` em paralelo) e **um único contexto Spring** compartilhado por todas as classes.
  Cada método começa com `truncate ... cascade` (o event store em memória fica; todo id é UUID novo).
  Uma subclasse que acrescente `@TestPropertySource` ganha contexto próprio e a suíte paga outra subida —
  propriedade nova vai em `Containers.registerProperties`.
- `HttpGraphQlTester` recusa subscriptions sobre HTTP; `support/SseSubscriptions` fala GraphQL-over-SSE
  direto para os testes de subscription.
- O realm de teste é **o mesmo arquivo** que o compose monta (`docker/keycloak/realm-*.json` entra no
  classpath de teste pelo `<testResources>` do pom) — duas cópias divergiriam em silêncio.
- `BatchLoadingE2ETest` afere DataLoader pela estatística do Hibernate: a mesma query com 1 e com 5 posts
  precisa custar o **mesmo número de statements** — a propriedade, não um número mágico.

## Convenções

- Javadoc e comentários em **português**, e explicam *por quê*, não *o quê*. O README é o documento de
  decisões de arquitetura do projeto — ao mudar uma decisão, atualizá-lo junto.
- Mensagens de commit em inglês, estilo conventional commits (`feat:`, `chore:`).
- Construtores nomeados em vez de `new` público: `PostId.of(...)` / `PostId.newId()`,
  `PostVersion.initial()` / `next()`, `Post.create(...)`, `AppendingDomainEventPublisher.appendingTo(...)`.
- MapStruct é o mapper padrão de todas as camadas; mapeamento à mão só quando o MapStruct não resolve
  (`PostViewMapper` precisa de `expression = "java(...)"` porque a entidade tem acessores `title()`, não
  `getTitle()`).
- **GraphQL inline carrega `//language=GraphQL`** logo acima do literal (dentro da lista de argumentos,
  nunca acima da statement — ali a injeção vaza para os outros literais da linha e o IDE pinta
  `.path("posts.edges")` de vermelho). Fragmentos que não são documento levam prefixo/sufixo junto:
  `//language=GraphQL prefix={posts{edges{node{ suffix=}}}}`. O
  Isso exige que `PostConnection`/`PostEdge`/`PageInfo` existam no SDL, e eles vivem num **bloco gerado
  no fim do `posts.graphqls`**, abaixo do marcador `# >>> GERADO`. Quem o escreve é
  `GeneratedConnectionTypesTest`, rodando o próprio `ConnectionTypeDefinitionConfigurer` do Spring sobre
  a parte escrita à mão do arquivo; quando diverge, o teste reescreve o bloco e falha para que alguém
  confira o diff. **Nada acima do marcador é tocado** — e campo `…Connection` novo = rodar a suíte e
  commitar. Declarar não muda runtime: o configurer só acrescenta o que falta
  (`registry.getType(name).isEmpty()`), e o segundo teste da classe compara os dois caminhos para provar.
  O `graphql.config.yml` na raiz é só do IDE: fixa o escopo do schema nesse arquivo (sem ele o plugin
  junta todo `.graphqls` do projeto, inclusive a cópia em `target/classes/`) e aponta o endpoint local,
  o que permite executar do editor a query onde está o cursor.
- O nome da mensagem no wire vem da anotação (`@Command(namespace, name, version)`), não da classe — mover
  ou renomear a classe Java não muda contrato; mexer na anotação, sim.

## Pontos em que o README está desatualizado

Ele descreve estados anteriores em alguns trechos — o código é a verdade:

- Menciona **SQLite** (event store, "SQLite em WAL", pacote `persistence/sqlite`); hoje é **Postgres** e o
  pacote é `infrastructure/persistence/jpa`.
- Descreve `Post.tags` como `@ElementCollection` de `TagRef`; hoje é `@ManyToMany` para `Tag` com a tabela
  de junção `post_tags`, e `TagRef` não existe mais.

# axon-graphql-posts

POC: **Axon Framework 5** (entidades anotadas + dynamic consistency boundary) + **extensão Reactor** (`ReactorCommandGateway` / `ReactorQueryGateway`) + **Spring for GraphQL** com subscriptions servidas por **Server-Sent Events**, numa API DDD de posts e tags. Estado em **SQLite** via Spring Data JPA; event store em memória.

A ideia central: o input é validado na borda e mapeado para a mensagem de um command → o command pede ao domínio que **decida** (e o domínio **dispara** o evento) e **salva** a entidade → a aplicação **ouve** o evento, reage a ele (inclusive despachando outros commands) e faz `emit` numa *subscription query* do Axon → o `Flux` devolvido por `ReactorQueryGateway.subscriptionQuery(...)` é o que o `@SubscriptionMapping` entrega ao cliente como stream SSE.

`Post` e `Tag` são, cada uma, **uma classe só**: entidade de domínio com comportamento, mapeamento JPA e entidade event-sourced do Axon. Não existe entidade de infraestrutura espelho — os value objects são `@Embeddable` de verdade e o `PostView` é apenas o DTO de saída do GraphQL.

`posts` e `Post.tags` são cursor connections; as tags são resolvidas por **DataLoader**, então N posts numa resposta viram uma consulta, não N.

```
mutation createPost(input) ──► @Valid  (Bean Validation: campo vazio, título > 200 → BAD_REQUEST aqui)
                              │
                              ▼
                  mapper.PostInputMapper.toCommand(PostId.newId(), input)   [MapStruct, compile time]
                              │
                              ▼
                  CreatePostCommand.CreatePost      [aplicação — a mensagem, aninhada no command]
                  CreatePostCommand                 [aplicação — um arquivo por command]
                     ├─► Post.create(...)           [domínio — valida, DISPARA o evento, devolve o Post]
                     │      events.raise(PostCreatedEvent) ──► DomainEventPublisher (porta do domínio)
                     │                                          └─ AppendingDomainEventPublisher ─► EventAppender
                     └─► PostRepository.save(post)  [a MESMA classe é a entidade JPA — sem DTO no meio]
                              │
                              │ commit do ProcessingContext (evento + linha no SQLite, juntos)
                              ▼
                  subscribing processor "post-projection"   (mesma thread e transação do command)
                     │
                     ├─► PostCreatedEventHandler ──► emit onPostCreated (o post como nasceu: v1, sem tags)
                     │
                     └─► AssignDefaultTagOnPostCreated ──► agenda no AFTER_COMMIT:
                              │   (antes do commit, o stream do post ainda não é legível de volta)
                              ├─► tag "Untagged" no banco?  não ──► CommandGateway.send(CreateTagCommand)
                              │                                        └─► TagCreatedEvent + linha em tags
                              └─► CommandGateway.send(AssignTagToPostCommand)
                                       └─► Post.assignTag(...) ──► PostUpdatedEvent (com a tag na lista)
                                                └─► PostUpdatedEventHandler ──► emit onPostUpdated
                              │
                              │ o Axon espera o CompletableFuture do AFTER_COMMIT antes de completar o send
                              ▼
                  PostMutationController: query do post ──► já vem com a tag Untagged (version 2)
                              │
                              ▼
                  @SubscriptionMapping ──► Spring GraphQL ──► SSE  (event:next / data:{...})
```


## Stack

| Peça | Versão |
|---|---|
| Java | 21 |
| Spring Boot (WebFlux + GraphQL + Data JPA) | 3.5.x |
| Axon Framework (`axon-spring-boot-starter`, sem Axon Server) | 5.3.x |
| Axon Reactor extension (`axon-reactor`) | 5.3.x |
| SQLite (`sqlite-jdbc`) + `hibernate-community-dialects` | gerenciados pelo Boot |
| Bean Validation (`spring-boot-starter-validation` / Hibernate Validator) | gerenciado pelo Boot |
| MapStruct (`mapstruct` + `mapstruct-processor`) | 1.6.3 |

As entidades `Post` e `Tag` são mapeadas por JPA e event-sourced pelo Axon ao mesmo tempo; os value objects são `@Embeddable`.

## Camadas

Três regras de camada, e três pacotes na raiz que cruzam todas elas:

1. **Uma classe por handler.** Cada command, evento, query e subscription tem a sua classe de handler, ao lado da mensagem que ela trata. Para saber tudo o que acontece quando um `PostCreated` chega, abrem-se os dois handlers dele — e nada mais.
2. **O domínio dispara, a aplicação ouve.** Os *eventos de domínio* vivem em `domain.*.event` e quem os dispara são as entidades, pela porta `DomainEventPublisher`. Os *event handlers* que reagem a eles vivem em `application.post.event`.
3. **O command decide e salva; o evento notifica e orquestra.** O command chama o domínio, recebe a entidade pronta e a grava. Os event handlers não gravam nada: um emite para as subscriptions, o outro despacha os commands que dão sequência à história.

Os três pacotes de raiz — `dto`, `mapper` e `exceptions` — são agrupados **por papel**, não por camada: um lugar só para procurar "todo input", "todo mapeamento" e "toda tradução de erro".

```
dev.manuelantunes.axonposts
├── dto/controller                              # a forma do dado NO PROTOCOLO
│   ├── CreatePostInput, UpdatePostInput        #   entrada: espelham os input do schema + Bean Validation
│   └── PostView, TagView                       #   saída: achatam os value objects para o schema
├── mapper                                      # TODO mapeamento do projeto, todo em MapStruct
│   ├── PostInputMapper                         #   input GraphQL → command   (protocolo → aplicação)
│   └── PostViewMapper                          #   Post → PostView           (domínio → protocolo)
├── exceptions
│   └── AppGraphQlExceptionHandler              #   validação/domínio/Axon → ErrorType do GraphQL
│
├── domain                                      # regras, invariantes E o mapeamento do próprio estado
│   ├── shared/DomainEvent, DomainEventPublisher    # marcador dos fatos + porta de saída de eventos
│   ├── post
│   │   ├── Post                                # @Entity + @EventSourced + comportamento, numa classe só
│   │   │                                       #   decidir: create()/update()/assignTag() disparam e devolvem o estado
│   │   │                                       #   evoluir: @EntityCreator + @EventSourcingHandler idempotente
│   │   ├── PostRepository                      #   porta do repositório (DDD: a interface é do domínio)
│   │   ├── vo/PostId, PostTitle, PostContent, Author, PostVersion, TagRef   # @Embeddable records
│   │   ├── event/PostCreatedEvent, PostUpdatedEvent   # @Event + @EventTag; payload primitivo (contrato)
│   │   └── exception/InvalidPostException, PostAlreadyExistsException
│   └── tag
│       ├── Tag                                 # @Entity + @EventSourced; agregado independente
│       ├── TagRepository, vo/TagId, TagName
│       ├── event/TagCreatedEvent
│       └── exception/InvalidTagException, TagAlreadyExistsException
├── application                                 # orquestra: carrega, carimba tempo, salva, pagina, encadeia
│   ├── shared/AppendingDomainEventPublisher    #   adapter DomainEventPublisher → EventAppender do Axon
│   ├── post
│   │   ├── PostPage                            #   uma fatia de posts (itens + offset + hasNext)
│   │   ├── command/CreatePostCommand, UpdatePostCommand, AssignTagToPostCommand
│   │   │                                       #   um arquivo por command; a classe leva o nome dele e
│   │   │                                       #   traz a mensagem aninhada: CreatePostCommand.CreatePost
│   │   ├── event/PostCreatedEventHandler       #   OUVE: emite onPostCreated
│   │   ├── event/PostUpdatedEventHandler       #   OUVE: emite onPostUpdated
│   │   ├── event/AssignDefaultTagOnPostCreated #   OUVE: garante a tag padrão despachando commands
│   │   ├── event/package-info                  #   @Namespace("post-projection") vale pro pacote inteiro
│   │   ├── query/FindPostQuery, FindAllPostsQuery        # idem: FindPostQuery.FindPost é a mensagem
│   │   └── subscription/OnPostCreatedSubscription, OnPostUpdatedSubscription   # idem: .OnPostCreated
│   └── tag/command/CreateTagCommand            #   com a mensagem CreateTag aninhada
├── infrastructure                              # escolhas de deploy; nenhuma regra de negócio
│   ├── persistence/sqlite/JpaPostRepository, JpaTagRepository        # adapters das portas
│   ├── persistence/sqlite/SpringDataPostRepository, SpringDataTagRepository
│   └── axon/AxonConfig                         # InMemoryEventStorageEngine, subscribingMatching(...), Clock
└── interfaces/graphql
    ├── PostQueryController, PostMutationController, PostSubscriptionController
    ├── PostTagsController                      #   campo Post.tags: DataLoader + cursor connection
    └── Connections                             #   cursor ↔ offset, compartilhado pelas duas connections
```

Schema em `src/main/resources/graphql/posts.graphqls`.

## O que mudou do Axon 4 pro 5 (e por quê está assim)

| Axon 4 | Axon 5 (este projeto) |
|---|---|
| `@Aggregate` + `@AggregateIdentifier` + construtor `@CommandHandler` | `@EventSourced(tagKey, idType)` na entidade; sem campo de identidade anotado |
| `AggregateLifecycle.apply(evento)` | o domínio chama `events.raise(evento)`; o adapter da aplicação traduz pro `EventAppender.append(evento)` injetado no handler |
| `@CommandHandler` dentro do agregado | `@CommandHandler` em classe própria **por command**, recebendo `@InjectEntity Post` (ou `Optional<Post>` na criação) |
| `@TargetAggregateIdentifier` | `@TargetEntityId` no command; `@EventTag` no evento liga os dois ao mesmo stream |
| `EventSourcingRepository` por aggregate type | stream por **tag** (`postId=<id>`), a consistency boundary é dinâmica (DCB) |
| Saga / `@SagaEventHandler` para encadear agregados | event handler comum despachando command no `ProcessingContext.onAfterCommit(...)` |
| `@EventSourcingHandler void on(...)` mutando | `@EventSourcingHandler void on(...)` mutando também — mas idempotente, porque a entidade é JPA e o evento chega por dois caminhos |
| `ReactorQueryGateway.queryUpdates(...)` sem handler | `subscriptionQuery(...)` = initial result **+** updates; o `@QueryHandler` da subscription existe e devolve `Optional.empty()` |
| `QueryUpdateEmitter` injetado no construtor | `QueryUpdateEmitter` chega como **parâmetro** do `@EventHandler`, já ligado ao `ProcessingContext` |
| `@ProcessingGroup` + `axon.eventhandling.processors.*.mode` | `@Namespace` (aqui, no `package-info`) + `EventProcessorDefinition.subscribingMatching(...)` bean (ou a mesma propriedade) |
| XStream/Jackson serializer | `Converter` (Jackson 3 por padrão: `axon.converter.general=jackson`) |

## Rodando

```bash
./mvnw spring-boot:run
# ou
./mvnw package && java -jar target/axon-graphql-posts-0.2.0-SNAPSHOT.jar
```

Endpoints (tudo em `/graphql`, transporte escolhido pelo `Accept` / upgrade):

- `POST /graphql` + `Accept: application/json` → queries e mutations
- `POST /graphql` + `Accept: text/event-stream` → **subscriptions via SSE**
- WebSocket em `/graphql` (graphql-ws) → só pra GraphiQL: http://localhost:8080/graphiql

## Testando na mão

Terminal 1 — abre a subscription global (fica pendurado):

```bash
curl -N -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"query":"subscription { onPostCreated { id title author version } }"}'
```

Terminal 2 — dispara o command:

```bash
curl -s -X POST http://localhost:8080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"mutation { createPost(input:{title:\"Axon 5 + GraphQL\", content:\"oi\", author:\"manuel\"}) { id title version tags(first: 5) { edges { node { id name } } } } }"}'
```

O terminal 1 recebe:

```
event:next
data:{"data":{"onPostCreated":{"id":"…","title":"Axon 5 + GraphQL","author":"manuel","version":1}}}
```

E a mutation responde com a tag padrão já atribuída — `version: 2`, porque criar e etiquetar são dois eventos:

```json
{"data":{"createPost":{"id":"…","title":"Axon 5 + GraphQL","version":2,
   "tags":{"edges":[{"cursor":"T18w","node":{"id":"…","name":"Untagged"}}],
           "pageInfo":{"hasNextPage":false}}}}}
```

Subscription filtrada por tópico (só updates daquele post):

```bash
curl -N -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"query":"subscription { onPostUpdated(postId: \"<ID>\") { id title content version } }"}'
```

```bash
curl -s -X POST http://localhost:8080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"mutation { updatePost(input:{id: \"<ID>\", title: \"editado\"}) { id title version } }"}'

curl -s -X POST http://localhost:8080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ post(id: \"<ID>\") { id title content updatedAt version } }"}'

# cursor connection: primeira página, depois `after` = endCursor da anterior
curl -s -X POST http://localhost:8080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ posts(first: 1) { edges { cursor node { id title } } pageInfo { hasNextPage endCursor } } }"}'

curl -s -X POST http://localhost:8080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ posts(first: 1, after: \"T18w\") { edges { cursor node { id title } } pageInfo { hasNextPage } } }"}'
```

Smoke test automatizado (builda, sobe, abre 3 subscriptions SSE, dispara mutations, confere contagens, derruba — logs em `.poc-logs/`):

```bash
bash scripts/poc-smoke.sh
```

Testes unitários (`./mvnw test`):

- `PostTest` / `TagTest` — domínio puro. O único colaborador é o `RecordingDomainEvents`, um duplo da porta do próprio domínio: dá para afirmar exatamente o que foi disparado sem Axon, sem Spring e sem JPA — mesmo com as entidades mapeadas.
- `CreatePostCommandTest`, `UpdatePostCommandTest`, `AssignTagToPostCommandTest`, `CreateTagCommandTest` — given-when-then com o `AxonTestFixture` do Axon 5, um por command. Cada um monta só o command que testa, então uma dependência acidental entre dois deles quebra o teste. Como salvar virou responsabilidade do command, os repositórios em memória provam que ele salvou — e o quê.
- `FindAllPostsQueryTest` — a mecânica do `limit + 1`: a linha extra nunca vaza para o resultado e o `hasNext` bate.
- `ConnectionsTest` — a tradução cursor ↔ offset e o recorte em memória, que é a parte da cursor connection que é lógica nossa e não do Spring. É o mesmo código por trás de `posts` e de `Post.tags`.

A orquestração da tag padrão (dois commands encadeados no `AFTER_COMMIT`) e o lote do DataLoader são cobertos pelo smoke test, e não por teste unitário: o que os dois têm de interessante — a ordem entre commit, dispatch e resposta da mutation; quantas vezes a função de lote é chamada — só existe com o Axon e o graphql-java de verdade rodando.

## Decisões que valem comentar

**Uma classe por entidade, não duas.** `Post` é `@Entity` + `@EventSourced` + comportamento ao mesmo tempo, e `Tag` idem. O JPA é agnóstico de banco e o mapeamento é por anotação, então não há razão para manter uma entidade de domínio e uma cópia anêmica de infraestrutura em sincronia. Os value objects são `@Embeddable` de verdade — e o schema gerado mostra que o resultado não é o embeddable aninhado de sempre:

```
posts:      id | title | content | author | created_at | updated_at | version
post_tags:  post_id | tag_id | name
tags:       id | name | created_at
```

`PostTitle` vira a coluna `title`, não `title_value` (é o `@AttributeOverride` na entidade), e as tags são um `@ElementCollection` gravado direto. O que sobrou de "infraestrutura" é o adapter do repositório, hoje uma classe fina de três métodos.

O preço: a entidade precisa ser mutável — o JPA exige construtor sem argumentos e campos não-finais — então o estilo imutável de antes (`@EventSourcingHandler` devolvendo cópia) deu lugar a mutação. Ela continua sem setters públicos: só eventos mudam estado, e só decisões produzem eventos.

**O `@EventSourcingHandler` é idempotente, e tem de ser.** Numa entidade mutável o mesmo `PostUpdatedEvent` chega por dois caminhos: o domínio o aplica ao decidir (para devolver o Post pronto para salvar) e o Axon o aplica ao apendar. Com `version.next()` dentro do `on(...)`, a versão contava duas vezes — foi exatamente o bug que apareceu ao trocar para o estilo mutável. A correção foi levar a versão resultante **dentro do evento**: todo campo do `on(...)` passou a ser valor absoluto, e aplicar duas vezes dá no mesmo. O teste `applyingTheSameEventTwiceLeavesTheSameState` trava isso.

**Decidir termina chamando evoluir.** `Post.create(...)` termina no construtor `@EntityCreator`, e `update`/`assignTag` terminam em `on(evento)` — os mesmos caminhos que o Axon usa para reconstituir a entidade do stream. Existe um único lugar que define como um evento vira estado, então "o que o command acabou de salvar" e "o que sai de um replay" não podem divergir. É o que o teste `theStateReturnedByUpdateIsTheSameAsSourcingTheRaisedEvent` trava.

**A tag padrão: orquestração pelo Axon, e o `AFTER_COMMIT` que a torna possível.** Todo post criado sem tags recebe a tag `Untagged`. Quem faz isso é um event handler (`AssignDefaultTagOnPostCreated`) que não escreve no banco nem monta eventos — ele só **despacha commands** e deixa o framework carregar o agregado certo, aplicar as regras dele e apendar o evento. `CreatePostCommand` não sabe que existem tags; o domínio de `Tag` não sabe que existem posts.

O detalhe que custou uma iteração: despachar o `AssignTagToPost` **direto** falha com `EntityNotFoundException`. O handler roda durante o commit do `CreatePost`, e nesse ponto o `PostCreatedEvent` ainda não é legível de volta do event store — o Axon tenta reidratar um Post cujo stream não enxerga. Passar o `ProcessingContext` do evento para o gateway também não resolve. O que resolve é registrar o trabalho em `context.onAfterCommit(...)`, que roda depois de o stream estar gravado.

E é o mesmo `onAfterCommit` que faz a mutation devolver o post **já com a tag**: ele recebe uma função que devolve um `CompletableFuture`, e o Axon espera por ele antes de concluir o processamento. Logo, `commandGateway.send(CreatePost)` só completa depois de a tag estar atribuída, e o controller consulta o post depois disso. Nenhum polling, nenhuma espera artificial — só a ordem que o processor *subscribing* garante. Por isso um post recém-criado nasce na **versão 2**: um evento de criação, um de atribuição da tag.

**Agregado referencia agregado por identidade.** `Post` não tem `@ManyToMany Tag`: guarda um `TagRef` (id + nome copiados) num `@ElementCollection`. Uma associação JPA entre os dois criaria fronteira transacional compartilhada, cascatas e lazy loading atravessando o limite de consistência — e tornaria impossível reconstruir o `Post` só a partir dos seus eventos. O nome vem junto porque exibir um post não deveria obrigar a carregar o agregado `Tag`.

**O command decide e salva; o evento notifica.** O command chama o domínio, recebe a entidade pronta e a grava — tudo dentro do `ProcessingContext`, então o append do evento e a escrita no SQLite commitam juntos.

> **O preço disso:** o estado gravado deixa de ser *derivado* do stream. Um replay dos eventos não o reconstrói, porque os event handlers não escrevem nada. Voltar a projetar no event handler (e tirar o `save` do command) é o que devolve essa propriedade.

**Cursor connection montada pelo Spring, não à mão.** O campo `posts` é uma Relay connection e não há um único tipo de connection escrito neste projeto. Três peças que o Boot autoconfigura por causa do Spring Data no classpath fazem o trabalho: `ScrollSubrange` como parâmetro do controller (decodifica `first`/`after` num `ScrollPosition`), `Window<PostView>` como retorno (o `WindowConnectionAdapter` vira `edges` + `cursor` + `pageInfo`), e o `ConnectionTypeDefinitionConfigurer`, que **gera** `PostConnection`, `PostEdge` e `PageInfo` a partir do sufixo `Connection` — por isso o `.graphqls` declara o campo mas não os tipos.

A tradução acontece em degraus, cada um no seu lugar: o controller converte cursor ↔ `offset`/`limit` (o cursor `T18w` é o base64 de `O_0`, um `OffsetScrollPosition`); a mensagem `FindAllPosts` carrega só os dois números, porque mensagem não carrega tipo de framework; a query decide o `hasNext` pedindo **uma linha a mais** e descartando-a; e o adapter volta para `ScrollPosition`/`Window`, o suporte a scrolling nativo do Spring Data. A ordenação é `createdAt, id` — `createdAt` sozinho não é único, e dois posts do mesmo instante fariam a paginação por offset pular ou repetir linhas. Paginação só para frente (`first`/`after`), declarada assim em vez de aceitar `last`/`before` e ignorá-los.

**`Post.tags`: connection paginada, servida por DataLoader.** Uma query `posts(first: 20) { edges { node { tags { … } } } }` dispararia 21 consultas — uma para os posts e uma para as tags de cada um. Com o DataLoader, o graphql-java junta os 20 pedidos do mesmo nível de execução e chama a função de lote **uma vez**, com os 20 ids; `findTagsByPostIds` resolve tudo num `join fetch` só. O smoke test prova isso lendo o log: duas queries que trazem dois posts produzem **duas** chamadas de lote de 2 chaves, não quatro de 1.

Para o lote ter o que evitar, `Post.tags` virou `@ElementCollection(fetch = LAZY)` e o `PostView` **perdeu** o campo `tags`: com `EAGER` o Hibernate faria um SELECT por post e o N+1 aconteceria antes de o DataLoader entrar em cena. Efeito colateral bem-vindo: quem não pede `tags` na query não paga por elas.

**Por que `@SchemaMapping` + DataLoader, e não `@BatchMapping`.** `@BatchMapping` é açúcar para exatamente o que o `PostTagsController` faz à mão — o javadoc dele diz isso: registrar a função no `BatchLoaderRegistry` e expor um `DataFetcher` que consulta o `DataLoader`. O que ele **não** faz é enxergar argumentos de campo: o `BatchLoaderHandlerMethod` só resolve a coleção de chaves, `@ContextValue`, `GraphQLContext` e `BatchLoaderEnvironment` — não há `@Argument` nem `ScrollSubrange`.

Como `tags` é paginado (`first`/`after`), a forma anotada não dá conta. A escolha era entre um campo sem paginação e a forma explícita; ficou a explícita — mesmo DataLoader, mesmo lote, com o argumento na mão. Se `tags` deixar de ser paginado um dia, o método vira um `@BatchMapping` de três linhas.

O lote traz todas as tags de cada post e a paginação recorta **em memória** (`Connections.slice`). É o certo para uma coleção filha pequena: ela já veio inteira no lote, e paginar no banco por post desfaria o lote. Para uma coleção grande, o caminho seria uma consulta com janela por chave dentro da própria função de lote — a fronteira do controller não mudaria.

**Validação em duas alturas, de propósito.** As constraints em `CreatePostInput`/`UpdatePostInput` são fail-fast de borda: o `@Valid` no `@Argument` faz o Spring GraphQL validar antes de existir command, e o cliente recebe `BAD_REQUEST` com a mensagem do campo. Os value objects continuam validando por conta própria, e é essa a validação que **vale** — ela protege a invariante venha o command de onde vier (outro adapter, um teste, uma migração). A borda existe para dar erro melhor e mais cedo, não para substituir o domínio. No `UpdatePostInput` o "nulo pode, vazio não" é `@Pattern` e não `@NotBlank`: constraints são ignoradas quando o valor é `null`, que é justamente a semântica de update parcial.

**MapStruct é o mapper padrão de todas as camadas.** Os dois saltos que o dado dá têm um mapper cada, ambos em `mapper/` e gerados em tempo de compilação (`target/generated-sources/annotations`, Java comum, zero reflexão): `PostInputMapper` (input GraphQL → command) e `PostViewMapper` (domínio → DTO de saída). O terceiro salto — read model ↔ entidade JPA — simplesmente deixou de existir quando `Post` virou a própria entidade mapeada. O ganho não é escrever menos linhas: é `-Amapstruct.unmappedTargetPolicy=ERROR` no `pom.xml`, que faz um campo novo no destino sem origem **quebrar o build** em vez de chegar `null` do outro lado.

O `PostInputMapper` o MapStruct resolve sozinho (record → record), assim como o `toTagViews` que o `PostTagsController` toma emprestado (`TagRef` → `TagView`). O `PostViewMapper` precisa de `expression = "java(post.title().value())"` por campo, porque o MapStruct descobre propriedades por acessor JavaBean (`getTitle()`) ou componente de `record`, e `Post` não é nem um nem outro — é entidade de domínio com acessores `title()` devolvendo value objects. Escrever a origem à mão não custa a garantia que importa: o **destino** continua conferido pelo compilador.

**`dto`, `mapper` e `exceptions` na raiz: agrupados por papel.** As camadas continuam valendo para o que tem regra (`domain`, `application`, `infrastructure`, `interfaces`), mas os artefatos puramente técnicos ficam agrupados pelo que são — um lugar só para procurar "todo input", "todo mapeamento", "toda tradução de erro". O preço é que esses pacotes cruzam fronteiras. As **exceções** em si não se mudaram: `InvalidPostException` e `InvalidTagException` seguem nos seus domínios, porque são vocabulário deles; o que foi para `exceptions/` é quem as *ouve* na borda.

**O domínio dispara; a aplicação ouve.** `Post.create(...)` e `post.update(...)` chamam `events.raise(...)` na porta `DomainEventPublisher`, que é do domínio — a regra de negócio dispara o fato sem conhecer o Axon. Quem implementa a porta é `AppendingDomainEventPublisher`, um invólucro de vida curta sobre o `EventAppender` que o `@CommandHandler` recebe por parâmetro (por isso o append é transacional). Do outro lado, *ouvir* o fato é da aplicação.

**Um arquivo por command, query e subscription.** A classe leva o nome da mensagem (`UpdatePostCommand`) e traz o record dela aninhado (`UpdatePostCommand.UpdatePost`), junto do método que a trata. O ganho é de localidade: tudo o que um `UpdatePost` provoca está num arquivo só. Os dois handlers de `PostCreatedEvent` são um exemplo do porquê — um notifica, o outro orquestra a tag, e são responsabilidades diferentes em arquivos diferentes.

Como a ordem entre handlers do mesmo processor **não** é garantida, o `PostCreatedEventHandler` monta a view que emite a partir do **payload do evento**, não do banco: assim `onPostCreated` publica sempre o post como ele nasceu (v1, sem tags), tenha a tag sido atribuída antes ou depois. A tag chega logo em seguida pelo `onPostUpdated` — que é a ordem em que os fatos de fato aconteceram.

**`@Namespace` no `package-info`, não em cada classe.** O Axon procura a anotação no tipo, nas classes envolventes, no pacote e no módulo. Declarada uma vez em `application/post/event/package-info.java`, ela casa todos os handlers do pacote com o `EventProcessorDefinition.subscribingMatching("post-projection")` do `AxonConfig` — handler novo no pacote entra no processor sem tocar em configuração.

**Value objects donos das invariantes.** `PostId`, `PostTitle`, `PostContent`, `Author`, `PostVersion` e `TagRef` (mais `TagId`/`TagName` do outro agregado) validam e normalizam no construtor canônico, então um `PostTitle` que existe é sempre válido e a entidade não checa nada ao guardá-lo. Como são `@Embeddable` em `record`, o Hibernate os instancia pelo construtor canônico — a invariante roda também quando a linha volta do banco. `PostVersion` não é `@Version` do JPA de propósito: aquele é lock otimista do ORM, este é o contador da reconstituição por eventos.

Os **eventos**, porém, carregam primitivos: evento é contrato, atravessa processo e fica gravado para sempre. A conversão acontece nas fronteiras da entidade.

**Construtores nomeados.** `PostId.of(...)` vs `PostId.newId()` dizem no call site qual dos dois casos é; `PostVersion.initial()` / `next()` são as duas únicas formas de obter uma versão; `AppendingDomainEventPublisher.appendingTo(appender)` lê como a frase que descreve o que faz. `Post.create(...)` e `Tag.create(...)` são os construtores nomeados das entidades: validam, disparam o evento e devolvem a instância pronta para salvar.

**Entidade nasce do primeiro evento.** Sem eventos para o id, o Axon não constrói nada: `@InjectEntity Post` lança `EntityNotFoundException` (vira `NOT_FOUND` no GraphQL) e `@InjectEntity Optional<Post>` vem vazio — é assim que `createPost` rejeita id duplicado. Carregar a entidade na criação ainda coloca o stream `postId=<id>` na consistency boundary do append, então duas criações concorrentes com o mesmo id conflitam no event store.

**`subscriptionQuery` no Axon 5 exige o `@QueryHandler`.** Diferente do 4.x (onde `queryUpdates` nunca despachava a query), o `SimpleQueryBus` do 5 executa o initial result na hora e concatena os updates. Para a semântica de subscription GraphQL o handler devolve `Optional.empty()`; se quiser snapshot + updates, devolva o `PostView` atual ali.

**Filtro por tópico é avaliado no emit.** `OnPostUpdatedSubscription(postId)` é o payload da subscription query e carrega o próprio predicado (`matches`); o event handler chama `emitter.emit(OnPostUpdatedSubscription.class, sub -> sub.matches(view.id()), view)`. Cada subscriber ativo recebe (ou não) conforme o próprio filtro — sem `filter()` no Flux.

**Emit sai depois do commit.** O `QueryUpdateEmitter` do Axon 5 é ligado ao `ProcessingContext` e adia o emit para o after-commit. Como os event handlers rodam em modo *subscribing*, o `save` do command já aconteceu quando o handler roda — é por isso que o `PostUpdatedEventHandler` consegue ler o post para emitir — e o SQLite já está commitado quando o SSE recebe o `PostView`.

**Bloqueio fora do event-loop.** `SimpleCommandBus`/`SimpleQueryBus` executam o handler na thread que despacha, e lá dentro tem JPA bloqueante. Os controllers fazem `subscribeOn(Schedulers.boundedElastic())` para não segurar o Netty.

**SQLite em WAL com `busy_timeout`.** Leituras não bloqueiam a escrita e um writer espera o outro em vez de estourar `SQLITE_BUSY`.

## Próximos passos possíveis

- Trocar o `InMemoryEventStorageEngine` pelo event store JPA do Axon (mesmo SQLite) ou Axon Server — só o bean em `AxonConfig` e a exclusão no `application.yml` mudam.
- Mutations de tag de verdade (`createTag`, `assignTag`, `removeTag`) — o command e o agregado já existem; falta só o `@MutationMapping`.
- Voltar a projetar o read model nos event handlers (tirando o `save` do command) pra recuperar o read model derivado do stream — e, aí sim, poder rodar o processor em modo pooled streaming (o default do Axon 5) e ver consistência eventual de verdade. Enquanto o command salva, trocar pra pooled só atrasa o `emit`: a escrita no SQLite continua síncrona.
- Explorar o DCB de verdade: um segundo `@EventSourced` (ex.: `AuthorQuota` com `tagKey = "author"`) carregado no mesmo command via `@InjectEntity(idProperty = "author")`, e a consistency boundary passa a cobrir os dois streams.
- `DateTime` scalar (graphql-java-extended-scalars) no lugar de `String` pros timestamps.
- Paginação por keyset (`KeysetScrollPosition`) no lugar de offset em `posts`: cursor estável mesmo com inserção concorrente, e sem o custo de `OFFSET n` em tabela grande. O `ScrollSubrange` já entrega os dois — só a query do Axon e o adapter mudariam.

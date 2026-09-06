# axon-graphql-posts

POC: **Axon Framework 5** (entidades anotadas + dynamic consistency boundary) + **extensão Reactor** (`ReactorCommandGateway` / `ReactorQueryGateway`) + **Spring for GraphQL** com subscriptions servidas por **Server-Sent Events**, numa API DDD de posts. Read model em **SQLite** via Spring Data JPA; event store em memória.

A ideia central: o input é validado na borda e mapeado para um command → o command handler pede ao domínio que **decida** (e o domínio **dispara** o evento) e **salva** o resultado no read model → a aplicação **ouve** o evento e faz `emit` numa *subscription query* do Axon → o `Flux` devolvido por `ReactorQueryGateway.subscriptionQuery(...)` é exatamente o que o `@SubscriptionMapping` do GraphQL entrega ao cliente como stream SSE.

```
mutation createPost(input) ──► @Valid  (Bean Validation: campo vazio, título > 200 → BAD_REQUEST aqui)
                              │
                              ▼
                  mapper.PostInputMapper.toCommand(PostId.newId(), input)   [MapStruct, gerado em compile time]
                              │
                              ▼
                  ReactorCommandGateway.send(CreatePostCommand, PostId.class)
                              │
                              ▼
                  CreatePostCommandHandler          [aplicação — uma classe por command]
                     @InjectEntity Optional<Post>   ◄── Axon reidrata Post pelos eventos com tag postId=<id>
                     │
                     ├─► Post.create(...)           [domínio — valida, DISPARA o evento, devolve o Post]
                     │      events.raise(PostCreatedEvent) ──► DomainEventPublisher (porta do domínio)
                     │                                          └─ AppendingDomainEventPublisher ─► EventAppender
                     └─► PostReadRepository.save(PostViewMapper.toView(post))       [MapStruct]
                              └─► SqlitePostReadRepository ─► PostEntityMapper.toEntity(view)  [MapStruct → JPA]
                              │
                              │ commit do ProcessingContext (evento + escrita no SQLite, juntos)
                              ▼
                  EventStore (InMemoryEventStorageEngine) ── publica ──► subscribing processor "post-projection"
                              │                                              (mesma thread e transação do command)
                              ▼
                  PostCreatedEventHandler           [aplicação — uma classe por evento: só NOTIFICA]
                     └─► QueryUpdateEmitter.emit(OnPostCreatedSubscription.class, filtro, PostView)
                                   │ (adiado pro after-commit)
                                   ▼
                  OnPostCreatedSubscriptionHandler.subscribe() → Flux<PostView>
                                   │
                                   ▼
                  @SubscriptionMapping onPostCreated ──► Spring GraphQL ──► SSE  (event:next / data:{...})
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

## Camadas

Três regras de camada, e três pacotes na raiz que cruzam todas elas:

1. **`dto`, `mapper` e `exceptions` na raiz: agrupados por papel.** As camadas continuam valendo para o que tem regra (`domain`, `application`, `infrastructure`, `interfaces`), mas os três artefatos puramente técnicos ficam agrupados pelo que são — existe um lugar só para procurar "todo input", "todo mapeamento", "toda tradução de erro". O preço é que esses pacotes cruzam fronteiras: `mapper` enxerga desde o input do GraphQL até a entidade JPA, que de outra forma ficaria fechada em `infrastructure.persistence.sqlite` (foi preciso torná-la pública, com getters e setters — que o JPA exige de qualquer forma).

As **exceções** em si não se mudaram: `InvalidPostException` e `PostAlreadyExistsException` seguem em `domain.post.exception`, porque são vocabulário do domínio. O que foi para `exceptions/` é quem as *ouve* na borda e decide como elas aparecem no protocolo.

**Uma classe por handler.** Cada command, evento, query e subscription tem a sua classe de handler, ao lado da mensagem que ela trata. Para saber tudo o que acontece quando um `PostUpdated` chega, abre-se `PostUpdatedEventHandler` — e só ele.
2. **O domínio dispara, a aplicação ouve.** Os *eventos de domínio* vivem em `domain.post.event` e quem os dispara é a entidade `Post`, pela porta `DomainEventPublisher`. Os *event handlers* que reagem a eles vivem em `application.post.event`.
3. **Cursor connection montada pelo Spring, não à mão.** O campo `posts` é uma Relay connection e não há um único tipo de connection escrito neste projeto. Três peças que o Boot autoconfigura por causa do Spring Data no classpath fazem o trabalho: `ScrollSubrange` como parâmetro do controller (decodifica `first`/`after` num `ScrollPosition`), `Window<PostView>` como retorno (o `WindowConnectionAdapter` vira `edges` + `cursor` + `pageInfo`), e o `ConnectionTypeDefinitionConfigurer`, que **gera** `PostConnection`, `PostEdge` e `PageInfo` no schema a partir do sufixo `Connection` — por isso o `.graphqls` declara o campo mas não os tipos.

A tradução acontece em degraus, cada um no seu lugar: o controller converte cursor ↔ `offset`/`limit` (o cursor `T18w` é o base64 de `O_0`, um `OffsetScrollPosition`); a query do Axon carrega só os dois números, porque mensagem não carrega tipo de framework; o query handler decide o `hasNext` pedindo **uma linha a mais** e descartando-a; e o adapter SQLite volta para `ScrollPosition`/`Window`, que é o suporte a scrolling nativo do Spring Data. A ordenação é `createdAt, id` — `createdAt` sozinho não é único, e dois posts do mesmo instante fariam a paginação por offset pular ou repetir linhas.

Paginação só para frente (`first`/`after`), declarada assim no schema em vez de aceitar `last`/`before` e ignorá-los.

**O command decide e salva; o evento notifica.** O command handler chama o domínio, recebe o `Post` pronto e o grava no read model — tudo na mesma transação. O event handler correspondente não projeta nada: quando ele roda, a view já existe, e o trabalho dele é só o `emit` para as subscriptions.

Os três pacotes de raiz — `dto`, `mapper` e `exceptions` — são agrupados **por papel**, não por camada: existe um lugar só onde procurar "todo input", "todo mapeamento" e "toda tradução de erro". A troca consciente: esses pacotes enxergam mais de uma camada (o `mapper` chega até a entidade JPA, que de outra forma ficaria fechada dentro de `infrastructure.persistence.sqlite`).

```
dev.manuelantunes.axonposts
├── dto/controller                              # DTOs de entrada: a forma do dado NO PROTOCOLO
│   └── CreatePostInput, UpdatePostInput        #   espelham os input do schema + constraints de Bean Validation
├── mapper                                      # TODO mapeamento do projeto, todo em MapStruct
│   ├── PostInputMapper                         #   input GraphQL → command      (protocolo → aplicação)
│   ├── PostViewMapper                          #   Post → PostView              (domínio → aplicação)
│   └── PostEntityMapper                        #   PostView ↔ PostEntity        (aplicação ↔ infraestrutura)
├── exceptions                                  # como cada falha aparece no protocolo
│   └── AppGraphQlExceptionHandler              #   validação/domínio/Axon → ErrorType do GraphQL
│
├── domain                                      # regras e invariantes; não conhece Spring, JPA nem GraphQL
│   ├── shared/DomainEvent, DomainEventPublisher    # marcador dos fatos + porta de saída de eventos
│   └── post
│       ├── Post                                # @EventSourced(tagKey="postId", idType=PostId) — entidade imutável
│       │                                       #   decidir: create()/update() validam, disparam e DEVOLVEM o estado
│       │                                       #   evoluir: @EntityCreator createdFrom(evento) + @EventSourcingHandler on(evento)
│       ├── vo/PostId, PostTitle, PostContent, Author, PostVersion   # value objects, donos das invariantes
│       ├── event/PostCreatedEvent, PostUpdatedEvent # @Event + @EventTag PostId; payload primitivo (contrato)
│       └── exception/InvalidPostException, PostAlreadyExistsException
├── application                                 # orquestra: carrega estado, carimba tempo, salva, pagina
│   ├── shared/AppendingDomainEventPublisher    #   adapter DomainEventPublisher → EventAppender do Axon
│   └── post
│       ├── PostView, PostPage                  #   read model e uma fatia dele (itens + offset + hasNext)
│       ├── port/PostReadRepository             #   porta do read model, paginada por offset/limit
│       ├── command/CreatePostCommand + CreatePostCommandHandler   # decide + SALVA
│       ├── command/UpdatePostCommand + UpdatePostCommandHandler   # decide + SALVA
│       ├── event/PostCreatedEventHandler, PostUpdatedEventHandler # OUVEM o domínio: só emitem
│       ├── event/package-info                  #   @Namespace("post-projection") vale pro pacote inteiro
│       ├── query/FindPostQuery + FindPostQueryHandler
│       ├── query/FindAllPostsQuery + FindAllPostsQueryHandler     # pagina: pede limit+1 pra saber o hasNext
│       └── subscription/OnPostCreated… + OnPostUpdated… (+ handlers)
├── infrastructure                              # escolhas de deploy; nenhuma regra de negócio
│   ├── persistence/sqlite/PostEntity           #   JavaBean JPA (é o que o MapStruct e o JPA pedem)
│   ├── persistence/sqlite/SpringDataPostRepository, SqlitePostReadRepository
│   └── axon/AxonConfig                         # InMemoryEventStorageEngine, subscribingMatching(...), Clock
└── interfaces/graphql                          # adaptador de protocolo
    └── PostQueryController, PostMutationController, PostSubscriptionController
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
| `@EventSourcingHandler void on(...)` mutando | `@EventSourcingHandler Post on(...)` devolvendo nova instância (entidade imutável) |
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
  -d '{"query":"mutation { createPost(input:{title:\"Axon 5 + GraphQL\", content:\"oi\", author:\"manuel\"}) { id title version } }"}'
```

O terminal 1 recebe:

```
event:next
data:{"data":{"onPostCreated":{"id":"…","title":"Axon 5 + GraphQL","author":"manuel","version":1}}}
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

- `PostTest` — domínio puro. O único colaborador é o `RecordingDomainEvents`, um duplo da porta do próprio domínio: dá pra afirmar exatamente o que foi disparado sem Axon, sem Spring e sem event store.
- `CreatePostCommandHandlerTest` / `UpdatePostCommandHandlerTest` — given-when-then com o `AxonTestFixture` do Axon 5, um por handler. Cada um monta só o handler que testa, então uma dependência acidental entre os dois quebra o teste. Como salvar virou responsabilidade do command, o `InMemoryPostReadRepository` é quem prova que ele salvou — e o quê.

## Decisões que valem comentar

**O command decide e salva; o evento notifica.** O `CreatePostCommandHandler` chama `Post.create(...)`, recebe o Post pronto e grava o `PostView` — tudo dentro do `ProcessingContext`, então o append do evento e a escrita no SQLite commitam juntos. Quando o `PostCreatedEventHandler` roda, a view já está lá; ele só a lê e emite pras subscriptions.

> **O preço disso:** o read model deixa de ser *derivado* do stream. Um replay dos eventos não o reconstrói mais, porque os event handlers não escrevem nada — e se um dia o `save` do command falhar sem derrubar a transação, o read model diverge sem ninguém para consertá-lo. Voltar a projetar no event handler (e tirar o `save` do command) é o que devolve essa propriedade.

**Decidir devolve o estado passando pelo evoluir.** `Post.create(...)` termina em `createdFrom(evento)` e `post.update(...)` termina em `on(evento)` — os mesmos métodos que o Axon usa para reconstituir a entidade do stream. Existe um único lugar que define como um evento vira estado, então "o que o command acabou de salvar" e "o que sai de um replay" não podem divergir. É o que o teste `theStateReturnedByUpdateIsTheSameAsSourcingTheRaisedEvent` trava.

**Validação em duas alturas, de propósito.** As constraints em `CreatePostInput`/`UpdatePostInput` são fail-fast de borda: o `@Valid` no `@Argument` faz o Spring GraphQL validar antes de existir command, e o cliente recebe `BAD_REQUEST` com a mensagem do campo. Os value objects (`PostTitle`, `PostContent`, `Author`) continuam validando por conta própria, e é essa a validação que **vale** — ela protege a invariante venha o command de onde vier (outro adapter, um teste, uma migração). A borda existe para dar erro melhor e mais cedo, não para substituir o domínio. No `UpdatePostInput` o "nulo pode, vazio não" é `@Pattern` e não `@NotBlank`: constraints são ignoradas quando o valor é `null`, que é justamente a semântica de update parcial.

**MapStruct é o mapper padrão de todas as camadas.** Os três saltos que o dado dá têm um mapper cada, todos em `mapper/` e todos gerados em tempo de compilação (`target/generated-sources/annotations`, Java comum, zero reflexão): `PostInputMapper` (protocolo → aplicação), `PostViewMapper` (domínio → aplicação) e `PostEntityMapper` (aplicação ↔ infraestrutura). O ganho não é escrever menos linhas — é `-Amapstruct.unmappedTargetPolicy=ERROR` no `pom.xml`: um campo novo no destino que ninguém preencheu **quebra o build** em vez de chegar `null` do outro lado.

Dois deles o MapStruct resolve sozinho, porque origem e destino são records ou JavaBeans de campos homônimos. O terceiro, `PostViewMapper`, precisa de `expression = "java(post.title().value())"` por campo: o MapStruct descobre propriedades por acessor JavaBean (`getTitle()`) ou por componente de `record`, e `Post` não é nem um nem outro — é uma classe de domínio com acessores `title()` devolvendo value objects. Escrever a origem à mão não custa a garantia que importa: o **destino** continua conferido pelo compilador, que é a razão de o mapeamento morar no MapStruct e não num método solto.

**O domínio dispara; a aplicação ouve.** `Post.create(...)` e `post.update(...)` chamam `events.raise(...)` na porta `DomainEventPublisher`, que é do domínio — então a regra de negócio dispara o fato sem conhecer o Axon. Quem implementa a porta é `AppendingDomainEventPublisher`, um invólucro de vida curta sobre o `EventAppender` que o `@CommandHandler` recebe por parâmetro (por isso o append é transacional). Do outro lado, *ouvir* o fato é responsabilidade da aplicação: os `@EventHandler` de `application.post.event`. O domínio não sabe que existe read model, subscription ou SQLite.

**Uma classe por handler.** Cada command, evento, query e subscription tem a sua. O ganho é de localidade: tudo o que um `UpdatePostCommand` provoca está em `UpdatePostCommandHandler`, e tudo o que um `PostUpdated` provoca está em `PostUpdatedEventHandler` — nada mais está. Não existe uma classe "projection" que trate cinco eventos e obrigue a ler o arquivo inteiro pra saber o que um deles causa. O Spring registra cada `@Component` com métodos anotados sem configuração extra.

**`@Namespace` no `package-info`, não em cada classe.** O Axon procura a anotação no tipo, nas classes envolventes, no pacote e no módulo. Declarada uma vez em `application/post/event/package-info.java`, ela casa todos os handlers do pacote com o `EventProcessorDefinition.subscribingMatching("post-projection")` do `AxonConfig` — handler novo no pacote entra no processor sem tocar em configuração.

**Value objects donos das invariantes, todos em `domain/post/vo`.** `PostId`, `PostTitle`, `PostContent`, `Author` e `PostVersion` validam e normalizam no construtor canônico, então um `PostTitle` que existe é sempre válido e a entidade não precisa checar nada ao guardá-lo. `PostVersion` é estado de domínio de verdade, não enfeite do read model: é o contador da reconstituição (`initial()` no primeiro evento, `next()` a cada `@EventSourcingHandler`), e é ele que o command grava. Os **eventos**, porém, carregam primitivos: evento é contrato, atravessa processo e fica gravado pra sempre. A conversão acontece nas fronteiras — `Post.createdFrom(evento)` na entrada, `PostViewMapper` na saída.

**Construtores nomeados.** `PostId.of(...)` vs `PostId.newId()` dizem no call site qual dos dois casos é; `PostVersion.initial()` / `next()` são as duas únicas formas de obter uma versão; `AppendingDomainEventPublisher.appendingTo(appender)` lê como a frase que descreve o que ele faz. `Post.create(...)` é o construtor nomeado da entidade: valida, dispara o `PostCreatedEvent` e devolve o Post pronto pra salvar. `Post.createdFrom(evento)` é o `@EntityCreator` (o Axon aceita factory method estático, não só construtor) e é para onde o `create` delega — o mesmo caminho que o framework usa no replay.

**Entidade nasce do primeiro evento.** Sem eventos pro id, o Axon não constrói nada: `@InjectEntity Post` lança `EntityNotFoundException` (vira `NOT_FOUND` no GraphQL) e `@InjectEntity Optional<Post>` vem vazio — é assim que o `createPost` rejeita id duplicado. Carregar a entidade na criação ainda coloca o stream `postId=<id>` na consistency boundary do append, então duas criações concorrentes com o mesmo id conflitam no event store.

**Entidade imutável.** `@EventSourcingHandler` devolve uma nova instância e o Axon 5 usa o retorno como estado evoluído. Todos os campos são `final`.

**`subscriptionQuery` no Axon 5 exige o `@QueryHandler`.** Diferente do 4.x (onde `queryUpdates` nunca despachava a query), o `SimpleQueryBus` do 5 executa o initial result na hora e concatena os updates. Pra semântica de subscription GraphQL o handler devolve `Optional.empty()`; se quiser snapshot + updates, devolva o `PostView` atual ali.

**Filtro por tópico é avaliado no emit.** `OnPostUpdatedSubscription(postId)` é o payload da subscription query e carrega o próprio predicado (`matches`); o event handler chama `emitter.emit(OnPostUpdatedSubscription.class, sub -> sub.matches(view.id()), view)`. Cada subscriber ativo recebe (ou não) conforme o próprio filtro — sem `filter()` no Flux.

**Emit sai depois do commit.** O `QueryUpdateEmitter` do Axon 5 é ligado ao `ProcessingContext` e adia o emit pro after-commit. Como os event handlers rodam em modo *subscribing* (mesmo contexto e transação Spring do command), o `save` do command já aconteceu quando o handler roda — é por isso que ele consegue ler a view para emitir — e o SQLite já está commitado quando o SSE recebe o `PostView`. Também é por isso que `createPost` devolve o Post com uma query logo depois do command.

**Bloqueio fora do event-loop.** `SimpleCommandBus`/`SimpleQueryBus` executam o handler na thread que despacha, e lá dentro tem JPA bloqueante. Os controllers fazem `subscribeOn(Schedulers.boundedElastic())` pra não segurar o Netty.

**SQLite em WAL com `busy_timeout`.** Leituras não bloqueiam a escrita e um writer espera o outro em vez de estourar `SQLITE_BUSY`.

## Próximos passos possíveis

- Trocar o `InMemoryEventStorageEngine` pelo event store JPA do Axon (mesmo SQLite) ou Axon Server — só o bean em `AxonConfig` e a exclusão no `application.yml` mudam.
- Voltar a projetar o read model nos event handlers (tirando o `save` do command) pra recuperar o read model derivado do stream — e, aí sim, poder rodar o processor em modo pooled streaming (o default do Axon 5) e ver consistência eventual de verdade. Enquanto o command salva, trocar pra pooled só atrasa o `emit`: a escrita no SQLite continua síncrona.
- Explorar o DCB de verdade: um segundo `@EventSourced` (ex.: `AuthorQuota` com `tagKey = "author"`) carregado no mesmo command handler via `@InjectEntity(idProperty = "author")`, e a consistency boundary passa a cobrir os dois streams.
- `DateTime` scalar (graphql-java-extended-scalars) no lugar de `String` pros timestamps.
- Paginação por keyset (`KeysetScrollPosition`) no lugar de offset: cursor estável mesmo com inserção concorrente, e sem o custo de `OFFSET n` em tabela grande. O `ScrollSubrange` já entrega os dois — só a query do Axon e o adapter mudariam.

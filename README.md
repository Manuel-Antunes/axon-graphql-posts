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
./mvnw test            # 144 testes, incluindo ponta a ponta com Postgres e Keycloak de verdade
```

---

## O que mudou, peça por peça

| Papel | Spring Boot | Quarkus |
|---|---|---|
| GraphQL | Spring for GraphQL, schema-first (`posts.graphqls`) | SmallRye GraphQL, **code-first** (o schema sai das classes) |
| Assíncrono | Reactor (`Mono`/`Flux`) + extensão `axon-reactor` | **Mutiny** (`Uni`/`Multi`) sobre os gateways do núcleo do Axon |
| Cursor connections | `ScrollSubrange` + `Window<T>` + `ConnectionTypeDefinitionConfigurer` | `interfaces/graphql/relay`: `Connection<N, E>` e `Edge<N>` genéricos |
| Config do Axon | `axon-spring-boot-starter` | extensão de Quarkus de terceiros (`at.meks`), descoberta em build time |
| Persistência | Spring Data JPA (interfaces geradas) | Hibernate ORM + **Panache** |
| Transações | `PlatformTransactionManager` | **JTA/Narayana**, via `quarkus-axon-transaction` |
| Segurança | `SecurityWebFilterChain` + `@PreAuthorize` + conversor de roles | **quarkus-oidc** + `@RolesAllowed`, sem conversor |
| Senha | `BCryptPasswordEncoder` | `BcryptUtil` |
| Mappers | `@Mapper(componentModel = "spring")` | `-Amapstruct.defaultComponentModel=jakarta-cdi` |
| Infra de teste | Testcontainers à mão (3 classes) | **Dev Services** (zero classes) |
| Subscriptions | GraphQL over SSE | WebSocket (`graphql-transport-ws`, de fábrica) **+ SSE** (`interfaces/graphql/sse`, escrito aqui) |
| Federação | — | **subgraph Apollo Federation 2** (`@key`/`@shareable`/`@Resolver` do SmallRye) |

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
interfaces/graphql/sse/          a porta de Server-Sent Events — transporte puro, o SmallRye não a tem
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

## Axon 5: a configuração que deixou de existir

Este projeto começou sem starter. `axon-spring-boot-starter` monta a configuração a partir do
`ApplicationContext`, não há equivalente para Quarkus, e o `EventSourcingConfigurer` é API **do núcleo** —
então um produtor CDI de 284 linhas fazia explicitamente o que o starter faz por dentro: descobrir
handlers varrendo o `BeanManager`, registrar entidades, publicar os gateways como beans, montar os
processors. Mais 164 linhas de `AxonHandlerLookup` e 91 de `JtaTransactionManager`.

**Alguém já tinha escrito esse starter.**
[`meks77/quarkus-axonframework-extension`](https://github.com/meks77/quarkus-axonframework-extension)
(`at.meks.quarkiverse.axonframework-extension`, Apache-2.0) é uma extensão de Quarkus de verdade —
descoberta em *build time*, não em runtime. O projeto foi migrado para ela:

| | antes | depois |
|---|---|---|
| `AxonProducer` | 284 linhas | — |
| `AxonHandlerLookup` | 164 linhas | — |
| `JtaTransactionManager` | 91 linhas | — (`quarkus-axon-transaction`) |
| `EventSourcedEntities` | — | 53 linhas |
| `ApplicationClock` | — | 25 linhas |
| **total em `infrastructure/axon`** | **539** | **78** |

O schema GraphQL saiu byte a byte idêntico e os 131 testes passam. O domínio e a aplicação não souberam
de nada: a troca inteira ficou dentro de `infrastructure`.

### O que a extensão descobre sozinha

Entidades (`@EventSourcedEntity` na classe), command handlers, query handlers e event handlers, todos por
índice Jandex em build time. O log da partida lista cada um. Os gateways e buses viram beans injetáveis
comuns, e com eles vêm o `Repository<ID, T>` tipado, interceptadores de dispatch e de handler, o
`quarkus.axon.command-gateway.retry.scheduling`, uma carta no Dev UI e um health check dos event
processors em `/q/health` (`Axon eventprocessors: UP`).

Além do core, a linha do Axon 5 tem publicados `quarkus-axon-transaction`, `quarkus-axon-server`,
`quarkus-axon-jpa-eventstore`, `quarkus-axon-tokenstore-jpa` e `quarkus-axon-metrics`. **O event store em
memória é o default** — que é justamente a escolha deste projeto, então trocar por Postgres é acrescentar
uma dependência e nada mais.

### O que ela não descobre, e por isso ainda está escrito

**O tipo do id de cada entidade.** No Axon 5 o par (tipo do id, entidade) é argumento de
`EventSourcedEntityModule.autodetected(...)`, não um atributo de anotação. A extensão resolve com uma
anotação própria, `@IdType(PostId.class)`, caindo em `String` quando ela não está lá — e é aí que este
projeto discorda: pôr uma anotação de uma extensão de Quarkus dentro de `domain` seria a primeira
dependência do domínio para uma biblioteca de plataforma, exatamente o que a conversão provou ser
desnecessário. Implementar o `EventSourcedEntityConfigurer` custa um mapa de três linhas e mantém
`domain` como estava.

**Qual processor roda cada pacote de event handler**, em linhas de `application.properties`:

```properties
quarkus.axon.subscribingprocessor.namespaces=dev.manuelantunes.axonposts.application.post.projection
quarkus.axon.pooledprocessor.post-subscriptions.namespaces=dev.manuelantunes.axonposts.application.post.event
```

São dois porque as duas reações ao mesmo evento precisam de entregas opostas: a projeção grava uma vez,
na transação do append; os `*EventHandler` avisam os assinantes em TODO container, lendo o event store.
O código dos dois lados é o mesmo `@EventHandler` de sempre — a diferença inteira está nestas linhas.

O valor é o **nome do pacote**. A extensão agrupa event handlers por `@Namespace` lido *da classe*, e cai
no pacote quando não há anotação — então o `@Namespace` que ficava no `package-info.java` deixou de ter
efeito e saiu, junto com a constante que existia só para casar os dois lados.

A regra que importava continua valendo — **handler novo neste pacote entra sem tocar em configuração** —,
mas ela agora tem uma borda: vale *dentro* dos pacotes listados. Um event handler de outro agregado, num
pacote novo, precisa do pacote na lista, e esquecer disso **não dá erro**:

```
INFO  registering pooled event processor for namespaces …application.tag.event
INFO  Starting PooledStreamingEventProcessor […]. Initializing (16) segments
```

A aplicação sobe, o handler roda, e a projeção daquele agregado vira **eventualmente consistente sem
ninguém ter pedido** — a mutation passa a responder antes da projeção. É exatamente o tipo de coisa que
este projeto prefere transformar em teste vermelho, então
`AxonWiringTest.everyPackageWithAnEventHandlerRunsInTheSubscribingProcessor` varre o `BeanManager` atrás
de `@EventHandler` e falha se algum pacote ficou de fora da propriedade. Verificado nos dois sentidos:
com um handler plantado num pacote novo, ele fica vermelho.

O erro simétrico é barulhento e vale saber por causa da **ordem**: um namespace listado sem nenhum handler
faz a aplicação **não subir**, com `NullPointerException` na partida — `getEventhandlers` faz
`map(mapa::get).flatMap(Collection::stream)` sobre um `null`. Ou seja: escreva o primeiro handler do
pacote, *depois* acrescente o pacote à propriedade. Não dá para declarar antes.

As duas substituições funcionam por **ausência**: a extensão declara beans `@DefaultBean` e cede a vez.
Apagar qualquer uma delas não quebra compilação nenhuma — a extensão volta ao padrão dela, que é
`NoTransactionManager` (o evento e a linha param de commitar juntos) e `String` como id. `AxonWiringTest`
existe só para isso: afirma que o `TransactionManager` da configuração é o do Quarkus e que cada entidade
responde pelo seu próprio tipo de id.

### As três armadilhas que sumiram junto

O produtor à mão tinha três linhas que, se esquecidas, faziam a aplicação **subir, listar os handlers no
log e não processar evento nenhum**: o `ClientProxy.unwrap` em cada componente, o
`.customized(… eventSource(EventStore.class))` no processor subscribing e o `.build()` no módulo. Foi o
`PostLifecycleE2ETest` que as encontrou, uma a uma. Nenhuma das três é mais nossa.

A do `ClientProxy` tem um detalhe medido: a extensão registra os beans **com o proxy do ArC**, sem
desembrulhar, e funciona — todos os commands e todas as projeções rodam. O que era armadilha aqui não é
armadilha lá.

### O que piorou, honestamente

**O live reload ficou menos confiável.** Numa sessão, depois de um `touch` numa classe de command, a
projeção parou de rodar em silêncio: o `createPost` passou a responder versão 1, sem a tag padrão, e nada
apareceu no log — que mostrou `shutdown axon` → `starting axon` → `Live reload total time: 1.4s`, limpo.
Não reproduziu depois, nem com a espera de shutdown aumentada (`quarkus.axon.live-reload.shutdown.wait-duration.amount`,
o botão que a própria extensão documenta para um sintoma vizinho), então **a causa continua desconhecida**
e a propriedade não entrou no projeto para não virar superstição. Ao ver um post nascer na versão 1 em
dev, reiniciar o `quarkus:dev` resolve.

**As exceções voltam embrulhadas.** `quarkus.axon.exception-handling.wrap-on-command-handler` é `true` por
default, então uma exceção de domínio chega ao resolver dentro de uma `CommandExecutionException` — como
era no Spring, e não como era aqui antes. Não quebrou nada porque tanto o `GraphQlErrors` quanto o
`PostCommandFixtures.hasCause` já percorriam a cadeia de causas em vez da raiz.

**E uma dependência a mais para vigiar.** `2.0.0-alpha6` (ago/2026) está declarada como compatível com
Quarkus 3.38.1 + Axon 5.3.0; aqui roda em 3.39.2 + 5.3.1, com a versão do Axon fixada pelo BOM **daqui**,
e a suíte inteira passa. É alpha, de um mantenedor, e a documentação está atrás do código em pelo menos um
ponto (o aviso de que só ids `String` funcionam, que a `@IdType` desmente). Snapshots e upcasters estão
marcados como quebrados desde a subida para o AF 5 — este projeto não usa nenhum dos dois.

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

### A terceira porta: GraphQL over SSE

A tabela de conversão lá em cima dizia que o projeto Spring servia subscriptions por SSE e este serve por
WebSocket. A primeira metade era uma escolha do Spring for GraphQL; a segunda era uma **limitação**: o
SmallRye GraphQL 2.18.5 e a extensão do Quarkus 3.39 não têm uma linha de `text/event-stream`. Procurar
por `event-stream` nos jars não acha nada, e `SmallRyeGraphQLConfig` só conhece `websocketSubprotocols`.

`interfaces/graphql/sse` é a porta que faltava, em três classes e ~250 linhas, no modo *distinct
connections* do protocolo [`graphql-sse`](https://github.com/enisdenjo/graphql-sse/blob/master/PROTOCOL.md):
uma requisição por operação, cada resultado vira `event: next`, o fim vira `event: complete`.

```bash
curl -N -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"query":"subscription { onPostCreated { title } }"}'

event: next
data: {"data":{"onPostCreated":{"title":"primeira","version":1}}}
```

**Por que valeu escrever.** Não é performance — é infraestrutura. SSE é uma resposta HTTP comum que nunca
termina: atravessa proxy e balanceador que não sabem fazer `Upgrade`; o token vai no `Authorization` da
própria requisição, em vez de viajar no `connection_init` ou na query string; o navegador reconecta
sozinho; e `curl -N` depura. O que se perde é o canal de volta, que numa subscription não custa nada.
Como o `GET` também é aceito e o navegador manda `Accept: text/event-stream` sozinho, um
`new EventSource('/graphql?query=subscription{onPostCreated{title}}')` funciona sem biblioteca nenhuma.

**O que NÃO foi reescrito.** O handler herda de `SmallRyeGraphQLAbstractHandler`, a mesma classe de que
descendem o handler HTTP e o de WebSocket do Quarkus. É ela que ativa o contexto de requisição do ArC,
publica a `SecurityIdentity` e — o mais fácil de esquecer — carrega o estado do contexto no `metaData`,
que é como os data fetchers assíncronos o reativam na thread de worker. Herdar é o que faz as três portas
se comportarem **igual**: mesmo schema, mesmo `@RolesAllowed`, mesmo `ErrorTranslationInterceptor`, mesmo
teto de profundidade. Medido: `createPost` sem token pela porta de SSE devolve o mesmo
`extensions.code: UNAUTHORIZED` com a mesma mensagem que pela porta HTTP. O preço é a dependência de uma
classe do pacote `runtime` de uma extensão, que não é API pública — anotado onde ela é usada.

E o `onOverflow().buffer(...)` da seção anterior conserta as duas portas de uma vez: o assinante de SSE
também pede um item de cada vez, então sem ele a subscription por SSE falharia exatamente do mesmo jeito.
`SseSubscriptionE2ETest` é o irmão do teste de WebSocket, e prova isso.

**A GraphiQL não usa esta porta, e não é sintoma de nada.** A UI em `/q/graphql-ui/` continua abrindo
WebSocket porque o `render.js` que o Quarkus serve manda, literalmente:

```js
var defaultHeaders = { Accept: 'application/json', 'Content-Type': 'application/json' };
const fetcher = createGraphiQLFetcher({
    url: getUrl(),
    subscriptionUrl: getWsUrl(),   // ws://localhost:8080/graphql
    headers: mergedHeaders,
});
```

As duas linhas explicam tudo: o `Accept: application/json` faz a rota de SSE devolver a requisição com
`ctx.next()` — que é o comportamento correto e o que o
`theSamePathStillAnswersJsonToWhoDidNotAskForAStream` trava —, e o `subscriptionUrl` em `ws://` faz o
`createGraphiQLFetcher` montar um cliente `graphql-ws`. Sem `subscriptionUrl` ele não cai em SSE: **lança**
("not properly configured for websocket subscriptions"). Não há configuração do Quarkus que mude isso: o
`updateUrl` do `SmallRyeGraphQLProcessor` só reescreve as linhas `const api` e `const logo` do
`render.js`; a do `subscriptionUrl` vem fixa do webjar.

Para exercitar a porta de SSE à mão, `curl -N` (o exemplo lá em cima) ou, no console do navegador:

```js
new EventSource('/graphql?query=subscription{onPostCreated{title}}')
    .onmessage = e => console.log(e.data);
```

Uma GraphiQL que falasse SSE exigiria servir uma página própria com um fetcher de `graphql-sse` — dá,
mas é uma UI a manter em paralelo à do Quarkus, e não é o que uma POC precisa provar.

**A armadilha, que é a ordem das rotas.** A porta de SSE é a *mesma* rota `/graphql`, decidida pelo
`Accept` — o que exige registrá-la entre os handlers de segurança e o de execução do GraphQL. O reflexo é
escolher um número alto e seguro para o `order`; e é errado. O Quarkus numera as rotas da aplicação **em
sequência**, com um dígito:

```
order=-99  SmallRyeGraphQLOverWebSocketHandler   /graphql
order=  2  SmallRyeGraphQLSchemaHandler          /graphql/schema.graphql
order=  4  SmallRyeGraphQLExecutionHandler       /graphql
```

Com `order=1000` a rota de SSE cai *depois* da de execução, que responde `406 Not Acceptable` a quem
pediu `text/event-stream` — e o handler novo nunca roda. O número certo é ancorado na mesma constante que
o Quarkus usa (`-SecurityHandlerPriorities.AUTHORIZATION + 2`), uma casa depois do WebSocket. Um segundo
detalhe do Vert.x na mesma linha: o Vert.x Web **pausa** a requisição ao começar a rotear, e quem a solta é
o `BodyHandler`. Como esta rota não tem um — de propósito, para não ler o corpo duas vezes nas requisições
que ela devolve com `ctx.next()` —, falta um `request.resume()`, e sem ele o POST fica pendurado até o
cliente desistir.

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

## Federação: este schema como subgraph do Apollo

O schema deixou de ser um grafo inteiro e passou a ser **um subgraph de um supergraph**. Nada do que
existia mudou de comportamento — `posts`, `me`, `createPost`, as subscriptions e a porta de SSE
continuam idênticas para quem fala direto com esta aplicação. O que se acrescentou foi o contrato que o
roteador lê, e os dois campos que ele chama.

```graphql
schema @link(import: ["@key", "@shareable"], url: "https://specs.apollo.dev/federation/v2.7")

type  Post   @key(fields: "id")                    { id: ID! ... }
type  Tag    @key(fields: "id")                    { id: ID! ... }
interface User @key(fields: "id")                  { id: ID! ... }
type  Author implements User @key(fields: "id")    { id: ID! ... }
type  Reader implements User @key(fields: "id")    { id: ID! ... }
type  PageInfo @shareable                          { ... }
```

Ligar isso foi **uma linha de configuração e um punhado de anotações** — o SmallRye já traz a Federação 2
inteira, incluindo `_service`, `_entities` e as diretivas até a 2.7. O trabalho real não foi habilitar; foi
decidir o que este serviço **é dono de** e escrever os resolvedores que pagam essa promessa.

### Chave não é id: é id mais um jeito de resolvê-lo sozinho

Um `@key` diz ao roteador "pode me mandar de volta `{__typename, id}` que eu reconstruo o objeto". Isso é
uma promessa que a **composição aceita sem nunca testar** — se não houver quem a cumpra, o supergraph
compõe, sobe, e quebra na primeira query que pular de subgraph.

Quem a cumpre são os `*EntityApi` em `interfaces/graphql/api/`, com `@Resolver`. O caso da `Tag` é o que
deixa a diferença visível: dentro deste schema ela **nunca teve** consulta por id — só se chega a uma tag
a partir de um post, por `Post.tags`. Isso bastava enquanto o schema era um só. Um vizinho que guarde
estatísticas por tag referencia `Tag` pela chave sem nunca ter visto um post, e o roteador volta aqui
pedindo `_entities` — não `posts`. Ter id não fazia dela uma entidade; ter como resolvê-la isolada, faz.

O `Post` é o oposto instrutivo: `Query.post(id:)` já existia e o SmallRye o encontraria sozinho — ele
procura o resolvedor primeiro entre os `@Resolver` e **depois entre as queries**, casando por tipo de
retorno e nome de argumento. O `@Resolver` do `Post` existe pela outra razão, a de lote.

### `@Resolver` não é `@Query`, e o casamento é por assinatura

Um `@Resolver` **não aparece no schema**: ele entra num tipo sintético que o SmallRye monta à parte e
serve só ao `_entities`. É o que evita publicar uma query por entidade só para o protocolo funcionar —
`Query.post(id:)` continua sendo o que é, uma operação de cliente.

A escolha do método não é pelo nome. Cada representação vira o par *(tipo, conjunto de nomes de
argumento)* — `("Post", {"id"})` — e o SmallRye procura um campo que devolva `Post` e cujos argumentos
sejam **exatamente** esse conjunto. Trocar `id` por `postId` compila, passa no `FederationSchemaTest` e
só quebra quando alguém consulta `_entities`.

É também por isso que o agregado de usuário tem **três** resolvedores para uma consulta só. O casamento é
pelo tipo de retorno, e `List<UserView>`, `List<AuthorView>` e `List<ReaderView>` são o mesmo apagamento
em Java e três tipos GraphQL diferentes — que é justamente o que o casamento usa.

### `@Id` no argumento em lote é a armadilha cara

O argumento de um resolvedor em lote é `List<String>` **sem `@Id`**, e a ausência custou uma sessão de
depuração. O `ReferenceCreator` do SmallRye testa `@Id` **antes** de desembrulhar a coleção: com a
anotação ele pede um scalar `ID` para `java.util.List`, o tipo esperado do argumento deixa de ser
`String`, e cada id vira "um String onde se esperava um objeto" — que o SmallRye tenta ler como JSON.

O que chega ao cliente não diz nada disso. O erro de transformação vira um `DataFetcherResult` sem dados,
que o `FederationDataFetcher` descarta, e a resposta é um `NullPointerException: resultList is null` sem
menção a argumento nenhum. O tipo do argumento no schema é indiferente — o tipo `Resolver` não é
publicado e o `_entities` entrega valores crus, sem coerção. Só o `FederationEntitiesE2ETest` pega isso.

### Lote: o mesmo N+1, agora atravessando o roteador

```properties
quarkus.smallrye-graphql.federation.batch-resolving-enabled=true
```

**Desligado por padrão**, e é a linha que separa uma chamada de `_entities` com N chaves de N idas ao
banco. Ligada, o SmallRye procura primeiro um `@Resolver` que devolva **lista** do tipo e entrega o lote
inteiro de uma vez; desligada, chama um método por representação. É o irmão, através do roteador, do que
o `@Source List<T>` já fazia dentro do schema — e `FederationEntitiesE2ETest` o afere do mesmo jeito que
o `BatchLoadingE2ETest`: cinco representações precisam custar o mesmo número de statements que uma.

O contrato do lote é rígido e conferido em runtime: **uma posição por representação, na ordem em que
chegaram**. Um id que não existe mais vira `null` *naquela* posição — devolver uma lista menor deslocaria
tudo o que o roteador anexa depois. Por isso os resolvedores projetam a lista de ids pedida sobre um mapa,
em vez de devolver o que veio do banco.

E o elemento da lista **não** pode levar `@NonNull`: com `[Post!]` o casamento por tipo de retorno falha —
o `FederationDataFetcher` desembrulha a lista e espera um tipo *nomeado*, não um `NonNull` — e o lote
deixa de ser usado sem uma linha de log.

### `interface User @key`: por que a interface também é entidade

`User` é interface porque a hierarquia do domínio é polimórfica, e isso não mudou. O que o `@key` na
interface acrescenta é a **interface de entidade** da Federação 2.3: um subgraph vizinho declara

```graphql
type User @key(fields: "id") @interfaceObject {
  id: ID!
  commentCount: Int!
}
```

e ganha `commentCount` em **toda** implementação — hoje `Author` e `Reader`, amanhã o que houver — sem
saber que elas existem. Ele enxerga um tipo só. Sem isso, um campo novo para todo usuário exigiria
declarar cada tipo concreto lá, e de novo a cada tipo novo aqui.

O `docker/federation/comments-example.graphql` é esse subgraph, escrito só como SDL, e
`supergraph-example.yaml` o compõe junto: é a prova, feita por composição e não por prosa, de que as
chaves publicadas aqui bastam para alguém estender o grafo.

Pedir o tipo errado responde `null`, e não o outro tipo: o id de um leitor pedido como `Author` não vira
um `Author`. Responder o `Reader` seria pior do que não responder — o roteador anexaria campos de
`Author` a um objeto que não é um.

### `PageInfo` é o único tipo compartilhado

Na Federação 2 um campo pertence a **um** subgraph e a composição recusa dois donos. `PageInfo` é a
exceção estrutural: não é entidade, não tem dono, é a forma de uma página — e todo subgraph que pagina
escreve a sua. `@shareable` é o que diz que essas definições são a mesma coisa.

`PostConnection`, `PostEdge` e as irmãs **não** levam a anotação, e a omissão é a decisão: elas carregam
`Post` e `Tag`, que são entidades daqui. Se outro subgraph as definisse, seria conflito de verdade — e
recusar é o certo.

### O `@link`, e o que acontece sem os `import`

A versão da especificação está **fixada literalmente** em `FederatedSchemaApi`, e não vem da constante
`Link.FEDERATION_SPEC_LATEST_URL` que a biblioteca oferece. A versão do `@link` determina quais diretivas
o roteador aceita deste subgraph: é contrato, e contrato não muda por efeito colateral de um bump de
dependência.

Os `import` não são decoração. Sem nenhum `@Link`, o SmallRye emite as diretivas com nome curto. Com um
`@Link` que **não** importe a diretiva usada, ela sai prefixada — `@federation__key`. As duas formas
compõem; só a segunda obriga quem lê o SDL a saber o que é. É por isso que o `FederationSchemaTest` afirma
as duas coisas: que `@key` sai curto, e que uma não-importada (`@federation__external`) continua
prefixada — a segunda asserção é o que prova que a primeira não é coincidência.

O `@Link` mora sozinho numa classe `@GraphQLApi` sem operação nenhuma. É diretiva de `SCHEMA`, e o
SmallRye só as recolhe em classes de API; ou ela mora numa das APIs existentes, sem relação com os
resolvers ao lado, ou mora sozinha. E **só pode haver uma**: repetir o `@link` da Federação em outra
classe derruba a aplicação na partida.

### Os dois SDL, e qual deles compõe

| | `/graphql/schema.graphql` | `{ _service { sdl } }` |
|---|---|---|
| quem serve | o Quarkus, como arquivo | o campo da especificação de subgraph |
| quem consome | gerador de cliente, IDE, o diff da revisão | o `rover`, o roteador |
| contém | o schema + `@link` + `@key` | o mesmo, mais `_entities`/`_service`/`_Any` |

São o mesmo contrato por duas portas, e o `schema.graphql` da raiz continua sendo a cópia versionada do
primeiro. Para que ele continuasse **compondo**, duas linhas precisaram entrar:

```properties
quarkus.smallrye-graphql.schema-include-directives=true
quarkus.smallrye-graphql.schema-include-schema-definition=true
```

A primeira porque `@key` e `@shareable` deixaram de ser enfeite e passaram a ser o contrato. A segunda
porque o bloco `schema { ... }` é o que carrega o `@link` — e **um subgraph sem `@link` é lido como
Federação 1 na composição**. O preço é o arquivo dobrar de tamanho com definições de diretiva que nunca
mudam; o que se compra é um SDL que o `rover` aceita direto do repositório, sem subir nada. O
`FederationSchemaTest` confere que as duas linhas continuam lá.

### Rodando federado

```bash
# 1. o subgraph. O 0.0.0.0 NÃO é detalhe: em dev o Quarkus escuta só em 127.0.0.1, e o roteador roda
#    em container — sem isto ele não alcança a aplicação.
./mvnw quarkus:dev -Dquarkus.http.host=0.0.0.0

# 2. compor. O rover faz a introspecção de federação (`{ _service { sdl } }`) na aplicação de pé
rover supergraph compose --config docker/federation/supergraph.yaml > docker/federation/supergraph.graphql

# 3. o supergraph
docker compose --profile federation up -d router     # http://localhost:4000
```

```bash
# a mutation atravessa o roteador com o token propagado, e o post nasce na versão 2 como sempre
curl -s localhost:4000/ -H 'content-type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { createPost(input:{title:\"Via supergraph\",content:\"c\"}) { id version } }"}'
```

Três coisas que custam tempo se ninguém as escrever:

- **o roteador não repassa cabeçalho nenhum por padrão.** Sem o bloco `headers` do `router.yaml`, `me` e
  `createPost` respondem `UNAUTHORIZED` atrás do supergraph e funcionam direto — quem autoriza continua
  sendo o `@RolesAllowed`, o que muda é que agora existe um salto que pode comer o token em silêncio;
- **a versão do roteador não é o `federation_version`.** Uma é o binário, a outra é o algoritmo de
  composição. `v2.9.3` existe como composição e não existe como imagem: `manifest unknown` no pull;
- **`subgraph_url` vai aninhado em `schema:`** no `supergraph.yaml`. Solto um nível acima, o rover não
  reclama — descarta o subgraph e falha com `No subgraphs were found in the supergraph config`.

### Subscriptions atrás do roteador: licenciadas

`subscription.enabled: true` é feature do GraphOS. Sem `APOLLO_KEY`/`APOLLO_GRAPH_REF` o roteador não sobe
degradado — ele **recusa a partida**:

```
license violation, the router is using features not available for your license: ["Federated subscriptions"]
```

Por isso o bloco está comentado no `router.yaml`: deixá-lo ligado tornaria
`docker compose --profile federation up` quebrado para quem só quer ver a POC federada.

Com licença, o roteador falaria `graphql-transport-ws` com este subgraph, no mesmo `/graphql` que o
SmallRye já serve — e vale registrar o que isso implica do outro lado: o cliente do supergraph **não abre
WebSocket**; ele recebe a subscription por HTTP multipart, e o WebSocket existe só entre roteador e
subgraph. A porta de SSE escrita em `interfaces/graphql/sse` continua sendo o que sempre foi, a forma de
assinar **falando direto** com este serviço. O roteador não a usa, e isso não é defeito de nenhum dos dois.

### O subgraph não é a fronteira

`_entities` é público e resolve **qualquer** entidade pela chave, sem token. Isso não é descuido do
SmallRye nem deste projeto: é a premissa de operação da Federação — o subgraph fica na rede interna e
quem fica exposto é o roteador. Vale dizer o que isso muda aqui, porque muda:

- `Post`, `Tag` e `Author` já eram alcançáveis anonimamente por `post`/`posts` e `Post.author`;
- **`Reader` não era.** Agora `_entities` devolve o e-mail e as contas de um leitor a quem souber o id.

Publicar esta aplicação direto na internet, portanto, expõe mais do que antes. Atrás do roteador, não —
e a autorização de campo continua valendo igual nas três portas, porque o `@RolesAllowed` roda no método.

O caminho oficial para autorização no próprio roteador são as diretivas `@authenticated`,
`@requiresScopes` e `@policy`, que o SmallRye também expõe como anotações. Elas **não** foram usadas aqui
por dois motivos: são avaliadas por features licenciadas do GraphOS, e duplicariam no schema uma decisão
que já está no método — que é onde este projeto insiste em mantê-la.

### O que não foi feito, e por quê

`@external`, `@requires`, `@provides` e `@override` existem no SmallRye e **não** aparecem em lugar
nenhum. Elas servem a um subgraph que estende tipo alheio; este não estende nenhum — ele é dono de tudo
o que declara. Anotação de federação escrita "para demonstrar" viraria ficção no SDL que o roteador lê.
Quando houver um vizinho de verdade para estender, o `comments-example.graphql` mostra a forma.

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

131 testes, nas mesmas três alturas do projeto original:

| | o que exercita |
|---|---|
| `domain/*` | domínio puro: sem Axon, sem CDI, sem JPA. O único colaborador é `RecordingDomainEvents` |
| `application/*` | `AxonTestFixture` given-when-then, um por command, com repositório em memória |
| `interfaces/graphql/relay/ConnectionsTest` | cursor ↔ offset, teto de página, montagem da connection |
| `interfaces/graphql/relay/RelaySchemaTest` | o SDL gerado carrega `PostConnection`/`PostEdge` e a `interface User` |
| `interfaces/graphql/SchemaIntrospectionTest` | a GraphiQL consegue introspectar, e a query Relay mais funda passa |
| `e2e/SseSubscriptionE2ETest` | a mesma newsletter pela porta de SSE, e o guarda de que o POST JSON não mudou |
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

Trocar por um event store persistente é acrescentar a dependência `quarkus-axon-jpa-eventstore`.

---

## AWS Lambda: o mesmo sistema, outro alvo de implantação

Um segundo alvo, e ele é **aditivo**: nenhum arquivo que existia antes dele foi alterado. As mesmas
duas aplicações, empacotadas por perfil Maven, viram quatro funções; o RabbitMQ vira um topic SNS FIFO
com três filas SQS FIFO assinando por filter policy.

O que isto prova sobre as decisões tomadas até aqui é mais interessante do que a migração em si.

**O `ChannelAddressing` pagou o que prometia.** O Javadoc dele dizia, desde antes de existir uma linha
de AWS: *"Protocolo novo = uma `ChannelAddressing` a mais"*. Foi literalmente isso — `SnsAddressing` e
`SqsAddressing`, uma classe cada, mais uma linha de `.properties` por canal. O `@AxonOutbox` das duas
aplicações não mudou, porque o que um serviço publica é contrato dele e não muda por ambiente.

**A regra de camadas pagou também.** Os `@Incoming` continuam sendo a porta de entrada: o canal passa
a `smallrye-in-memory` e o handler do Lambda empurra o registro para dentro dele, então
`PostPreCreatedListener` e os irmãos rodam sem uma linha alterada — com o `@Blocking(ordered = false)`
e a unidade de trabalho do Axon que já estavam medidos ali. O handler do Lambda não é a porta; é o
transporte, o lugar equivalente ao conector do RabbitMQ.

**E a chave de ordenação encontrou a razão de existir.** O `EventAddress.orderingKey()` era o terceiro
segmento da routing key, e o próprio `application.properties` admitia por escrito que ele não
desempatava nada. Numa fila FIFO ele é o `MessageGroupId` — ou seja, é o que faz a ordem existir. E
ela não é opcional: `apps/tagging` escreve no stream do `Post`, e num event store em *aggregate mode*
um evento fora de ordem faz o append seguinte cair em
`duplicate key ... uk_aggregateevententry_aggregate`.

**O que regrediu, e é honesto dizer:** subscriptions não funcionam em Lambda — nem por WebSocket nem
por SSE, e o transporte é a metade menor do problema, porque `SimpleQueryBus.emitUpdate` é em processo
e quem apenda o `PostCreated` é outra função. O trace único também regride, porque na entrada não há
conector para instrumentar e o conector de SNS não tem tracing. As duas coisas, com a medição que as
sustenta e os caminhos que as resolveriam, estão em **[`infra/aws/README.md`](infra/aws/README.md)** —
o documento dessa migração, decisão por decisão.

```bash
npx sst deploy --stage dev                # o deploy CONSTRÓI os quatro zips: cada função declara
                                          # em `code` o alvo do Nx que a constrói
npx nx run "dev.manuelantunes:axonposts-tagging:lambda"   # ou um artefato só, à mão
```

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

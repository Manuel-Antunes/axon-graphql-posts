# quarkus-axon-graphql-posts

A **Quarkus** conversion of the [axon-graphql-posts](https://github.com/Manuel-Antunes/axon-graphql-posts)
proof of concept, originally written in Spring Boot.

The goal was not "make it compile on Quarkus": it was to keep **the same architecture decisions** —
Axon Framework 5 with annotated entities and DCB, DDD with `@Embeddable` value objects, Keycloak as the
single identity provider, MapStruct on every boundary, Relay cursor connections — and to swap each piece
of Spring infrastructure for the equivalent Quarkus piece, using what each one does best instead of
imitating the other.

```bash
./mvnw quarkus:dev     # http://localhost:8080/q/graphql-ui/  — only needs Docker running
./mvnw test            # 144 tests, including end-to-end with a real Postgres and Keycloak
```

---

## What changed, piece by piece

| Role | Spring Boot | Quarkus |
|---|---|---|
| GraphQL | Spring for GraphQL, schema-first (`posts.graphqls`) | SmallRye GraphQL, **code-first** (the schema comes out of the classes) |
| Async | Reactor (`Mono`/`Flux`) + the `axon-reactor` extension | **Mutiny** (`Uni`/`Multi`) over Axon's core gateways |
| Cursor connections | `ScrollSubrange` + `Window<T>` + `ConnectionTypeDefinitionConfigurer` | `interfaces/graphql/relay`: generic `Connection<N, E>` and `Edge<N>` |
| Axon config | `axon-spring-boot-starter` | a third-party Quarkus extension (`at.meks`), discovered at build time |
| Persistence | Spring Data JPA (generated interfaces) | Hibernate ORM + **Panache** |
| Transactions | `PlatformTransactionManager` | **JTA/Narayana**, via `quarkus-axon-transaction` |
| Security | `SecurityWebFilterChain` + `@PreAuthorize` + a role converter | **quarkus-oidc** + `@RolesAllowed`, no converter |
| Password | `BCryptPasswordEncoder` | `BcryptUtil` |
| Mappers | `@Mapper(componentModel = "spring")` | `-Amapstruct.defaultComponentModel=jakarta-cdi` |
| Test infrastructure | hand-written Testcontainers (3 classes) | **Dev Services** (zero classes) |
| Subscriptions | GraphQL over SSE | WebSocket (`graphql-transport-ws`, out of the box) **+ SSE** (`interfaces/graphql/sse`, written here) |
| Federation | — | **Apollo Federation 2 subgraph** (SmallRye's `@key`/`@shareable`/`@Resolver`) |

The domain (`domain/`) crossed over **almost untouched**: one annotation changed (`@EventSourced` from
the Spring module became `@EventSourcedEntity` from Axon's core) and `Role`, which lost the `ROLE_`
prefix because Quarkus uses none. That is the result hexagonal architecture promises and that you rarely
get the chance to measure: the ports (`PostRepository`, `DomainEventPublisher`, `AuthenticatedUser`,
`PasswordVerifier`) absorbed an entire platform swap.

---

## The packages, and the arrow they draw

The conversion started with three root packages grouped **by role** — `dto/`, `mapper/`, `exceptions/` —
justified as "one place to look for every input, every mapping, every error translation". It worked for
finding files and hid the one thing a package should show: **the layer**. A root mappers package sees
domain and protocol in the same file, and nothing in the project said whether that was allowed or merely
tolerated.

Today every type lives in the layer that owns it:

```
application/<aggregate>/view/    PostView, TagView, UserView…, PostPage, *ViewMapper
interfaces/graphql/api/          the @GraphQLApi classes — resolvers only
interfaces/graphql/dto/          CreatePostInput, UpdatePostInput
interfaces/graphql/mapper/       PostInputMapper (input → command)
interfaces/graphql/relay/        Connection/Edge/PageInfo/Connections/Cursors + Post/TagConnection/Edge
interfaces/graphql/error/        GraphQlErrors, @TranslatesErrors, the 4 exceptions with @ErrorCode
interfaces/graphql/sse/          the Server-Sent Events endpoint — pure transport, SmallRye does not have it
```

### The rule that decided every case

**What crosses the bus belongs to the application; what only exists in the schema belongs to presentation.**

The `*View` types are not "controller output DTOs": they are the **result of the queries**. `FindPost`
returns `PostView`, `FindAllPosts` returns `PostPage`, the subscriptions emit `PostView` — all of that
happens before a resolver exists. Leaving them in `interfaces` would make the application import
presentation, which is the arrow backwards. The `*ViewMapper` classes (entity → view) went with them,
because the query handlers are what call them.

The `*Input` types are the opposite: they never leave the edge. `PostInputMapper` turns them into a
command before anything else, and after that they no longer exist. They stayed in presentation, and so
did their mapper.

### The two debts, annotated where they live

- **The `*View` types carry MicroProfile GraphQL annotations** (`@Name("Post")`, `@Id`,
  `@Description`). That is the application knowing about protocol. Separating it means two views per
  aggregate — one for the application, one for the schema — and one more mapper between them; at the
  size of this proof of concept, that is ceremony with no new decision. If it ever pays off, the
  boundary to move is `*ViewMapper`, and nothing beyond it.
- **`DataIntegrityTranslator` knows Hibernate and lives in `error/`.** It translates a constraint
  violation into a domain exception, and the one who needs that is `GraphQlErrors`, one line above. In
  `infrastructure` it would create the project's only presentation → infrastructure dependency, and gain
  nothing.

`domain/` was not touched — again. That is the second package rearrangement it has crossed without a
line changed.

---

## Generic Connection and Edge

This was the most concrete request: *"I wanted connection and edge to be generic types, so I could pass
the class I want to work with and it would simply work"*.

```java
// written once, in interfaces/graphql/relay
public abstract class Edge<N>                              { N node; String cursor; }
public abstract class Connection<N, E extends Edge<N>>     { List<E> edges; PageInfo pageInfo; }

// one line per paginated type
public final class PostEdge       extends Edge<PostView> { }
public final class PostConnection extends Connection<PostView, PostEdge> { }
```

```java
// in the resolver
ConnectionArgs args = ConnectionArgs.of("post", first, after);
return Connections.page(page.items(), page.hasNext(), args, PostEdge::new, PostConnection::new);
```

### Why the one-line subclass, and not `Connection<PostView, PostEdge>` directly

Because SmallRye names an instantiated generic after the type **plus a suffix per argument**:
`Connection<PostView>` becomes `Connection_Post` in the schema. That is legal, it is stable, and it looks
like no Relay client in the world.

A class **with no type parameters of its own** is, to the schema builder, an ordinary type — and an
ordinary type takes the class's name. What it does not lose is generic resolution: SmallRye walks up the
hierarchy, finds `Edge<PostView>` and resolves `N` to `PostView` when building the `node` field.

The second parameter (`E extends Edge<N>`) is not redundancy: it is what makes the schema come out with
`edges: [PostEdge]!` instead of `[Edge_Post]!`. Declared as `List<Edge<N>>`, the schema builder would see
an instantiated generic in there and undo what the subclass achieved.

Two one-line declarations per paginated type, and no duplicated pagination logic. It is as close as you
can get to Spring's `ConnectionTypeDefinitionConfigurer` — with the difference that the types exist in
Java, the compiler checks them and the IDE navigates to them. `RelaySchemaTest` guards the mechanism: it
reads the generated SDL and fails if `Connection_` comes back.

**What we got for free**, and Spring did not have:

- **a page ceiling** (`ConnectionArgs.MAX_LIMIT`): `posts(first: 100000)` is refused, instead of going
  all the way down to Spring Data's `Limit`;
- **a type-prefixed cursor**: a `tags` cursor is refused in `posts`. Spring's `CursorStrategy` encoded
  `O_<offset>` with no prefix, and one cursor worked in any connection.

---

## Batching: where Quarkus got simpler

In the Spring project, `Post.tags` and `Author.posts` **could not** use `@BatchMapping`: it does not see
`@Argument` or `ScrollSubrange`, and both fields are paginated. The way out was two fifty-line classes —
registering the function in `BatchLoaderRegistry` through the constructor, naming the loader in a
constant, fetching the `DataLoader` out of `DataFetchingEnvironment` inside a `@SchemaMapping`.

SmallRye's `BatchDataFetcher` passes the field's arguments along with the keys in the batch context, so a
batched `@Source` **can** have arguments:

```java
@Name("tags")
public Uni<List<TagConnection>> tags(@Source List<PostView> posts,
                                     @Name("first") Integer first,
                                     @Name("after") String after) { … }
```

Two classes became two methods. `BatchLoadingE2ETest` measures that the simplification did not cost the
batch: a response with 5 posts spends **the same number of statements** as one with 1.

---

## Axon 5: the configuration that ceased to exist

This project started without a starter. `axon-spring-boot-starter` builds the configuration from the
`ApplicationContext`, there is no Quarkus equivalent, and `EventSourcingConfigurer` is **core** API — so a
284-line CDI producer explicitly did what the starter does internally: discover handlers by scanning the
`BeanManager`, register entities, publish the gateways as beans, assemble the processors. Plus 164 lines of
`AxonHandlerLookup` and 91 of `JtaTransactionManager`.

**Somebody had already written that starter.**
[`meks77/quarkus-axonframework-extension`](https://github.com/meks77/quarkus-axonframework-extension)
(`at.meks.quarkiverse.axonframework-extension`, Apache-2.0) is a real Quarkus extension — discovered at
*build time*, not at runtime. The project was migrated to it:

| | before | after |
|---|---|---|
| `AxonProducer` | 284 lines | — |
| `AxonHandlerLookup` | 164 lines | — |
| `JtaTransactionManager` | 91 lines | — (`quarkus-axon-transaction`) |
| `EventSourcedEntities` | — | 53 lines |
| `ApplicationClock` | — | 25 lines |
| **total in `infrastructure/axon`** | **539** | **78** |

Those two rows are the measurement taken at the swap. `EventSourcedEntities` has since become 14 lines
plus a 138-line `EntityIdType`, and that is the one place where the count went **up**: the id type used to
be declared entity by entity, and it is now derived from `@EventTag` and `tagKey`. What was bought is the
declaration itself — a new entity states nothing anywhere, and an entity nobody remembered to declare can
no longer become `String` in silence. `CLAUDE.md` has the rule and what it refuses.

The GraphQL schema came out byte for byte identical and the 131 tests pass. The domain and the
application never found out: the whole swap stayed inside `infrastructure`.

### What the extension discovers on its own

Entities (`@EventSourcedEntity` on the class), command handlers, query handlers and event handlers, all
through the Jandex index at build time. The startup log lists each one. The gateways and buses become
ordinary injectable beans, and with them come the typed `Repository<ID, T>`, dispatch and handler
interceptors, `quarkus.axon.command-gateway.retry.scheduling`, a Dev UI card and an event processor
health check at `/q/health` (`Axon eventprocessors: UP`).

Besides the core, the Axon 5 line has `quarkus-axon-transaction`, `quarkus-axon-server`,
`quarkus-axon-jpa-eventstore`, `quarkus-axon-tokenstore-jpa` and `quarkus-axon-metrics` published. **The
in-memory event store is the default** — which is exactly this project's choice, so switching to Postgres
means adding a dependency and nothing else.

### What it does not discover, and so is still written down

**Each entity's id type.** In Axon 5 the (id type, entity) pair is an argument to
`EventSourcedEntityModule.autodetected(...)`, not an annotation attribute. The extension solves it with an
annotation of its own, `@IdType(PostId.class)`, falling back to `String` when it is absent — and that is
where this project disagrees: putting a Quarkus extension's annotation inside `domain` would be the
domain's first dependency on a platform library, exactly what the conversion proved unnecessary.
Implementing `EventSourcedEntityConfigurer` costs a three-line map and keeps `domain` as it was.

**Which processor runs each event-handler package**, in lines of `application.properties`:

```properties
quarkus.axon.subscribingprocessor.namespaces=dev.manuelantunes.axonposts.application.post.projection
quarkus.axon.pooledprocessor.post-subscriptions.namespaces=dev.manuelantunes.axonposts.application.post.event
```

There are two because the two reactions to the same event need opposite deliveries: the projection writes
once, in the append's transaction; the `*EventHandler` classes notify subscribers in EVERY container,
reading the event store. The code on both sides is the same old `@EventHandler` — the entire difference is
in these lines.

The value is the **package name**. The extension groups event handlers by `@Namespace` read *from the
class*, and falls back to the package when there is no annotation — so the `@Namespace` that used to sit
in `package-info.java` stopped having any effect and was removed, along with the constant that existed
only to match the two sides.

The rule that mattered still holds — **a new handler in this package goes in without touching
configuration** — but it now has an edge: it holds *inside* the listed packages. An event handler of
another aggregate, in a new package, needs the package on the list, and forgetting that **does not
error**:

```
INFO  registering pooled event processor for namespaces …application.tag.event
INFO  Starting PooledStreamingEventProcessor […]. Initializing (16) segments
```

The application comes up, the handler runs, and that aggregate's projection becomes **eventually
consistent with nobody having asked for it** — the mutation starts answering before the projection. That
is exactly the kind of thing this project prefers to turn into a red test, so
`AxonWiringTest.everyPackageWithAnEventHandlerRunsInTheSubscribingProcessor` sweeps the `BeanManager` for
`@EventHandler` and fails if any package was left out of the property. Verified in both directions: with a
handler planted in a new package, it goes red.

The symmetric error is loud and worth knowing because of the **order**: a namespace listed with no handler
at all makes the application **fail to start**, with a `NullPointerException` at startup —
`getEventhandlers` does `map(map::get).flatMap(Collection::stream)` over a `null`. In other words: write
the package's first handler, *then* add the package to the property. You cannot declare it upfront.

Both substitutions work by **absence**: the extension declares `@DefaultBean` beans and steps aside.
Deleting either one breaks no compilation — the extension falls back to its default, which is
`NoTransactionManager` (the event and the row stop committing together) and `String` as the id.
`AxonWiringTest` exists only for this: it asserts that the configuration's `TransactionManager` is the
Quarkus one and that each entity answers with its own id type.

### The three traps that disappeared along with it

The hand-written producer had three lines that, if forgotten, made the application **start, list the
handlers in the log and process no events at all**: the `ClientProxy.unwrap` on each component, the
`.customized(… eventSource(EventStore.class))` on the subscribing processor and the `.build()` on the
module. It was `PostLifecycleE2ETest` that found them, one at a time. None of the three is ours any more.

The `ClientProxy` one has a measured detail: the extension registers the beans **with the ArC proxy**,
without unwrapping, and it works — every command and every projection runs. What was a trap here is not a
trap there.

### What got worse, honestly

**Live reload became less reliable.** In one session, after a `touch` on a command class, the projection
silently stopped running: `createPost` started answering version 1, with no default tag, and nothing showed
up in the log — which showed `shutdown axon` → `starting axon` → `Live reload total time: 1.4s`, clean. It
did not reproduce afterwards, not even with a longer shutdown wait
(`quarkus.axon.live-reload.shutdown.wait-duration.amount`, the knob the extension itself documents for a
neighbouring symptom), so **the cause remains unknown** and the property did not enter the project so as
not to become superstition. When you see a post born at version 1 in dev, restarting `quarkus:dev` fixes
it.

**Exceptions come back wrapped.** `quarkus.axon.exception-handling.wrap-on-command-handler` is `true` by
default, so a domain exception reaches the resolver inside a `CommandExecutionException` — as it was in
Spring, and not as it was here before. Nothing broke because both `GraphQlErrors` and
`PostCommandFixtures.hasCause` already walked the cause chain instead of the root.

**And one more dependency to watch.** `2.0.0-alpha6` (Aug 2026) is declared compatible with Quarkus
3.38.1 + Axon 5.3.0; here it runs on 3.39.2 + 5.3.1, with the Axon version pinned by **this** project's
BOM, and the whole suite passes. It is alpha, from a single maintainer, and the documentation is behind
the code in at least one place (the warning that only `String` ids work, which `@IdType` contradicts).
Snapshots and upcasters are marked broken since the move to AF 5 — this project uses neither.

## Mutiny, and where the blocking work happens

`SimpleCommandBus` and `SimpleQueryBus` execute the handler **on the dispatching thread**, and inside it
there is blocking JPA. A resolver returning `Uni` runs on the Vert.x event loop, and blocking an event
loop stalls every request it serves. It is the same trap the Spring project solves with
`subscribeOn(boundedElastic())`, and the same answer, written in the resolver:

```java
return Uni.createFrom()
        .completionStage(() -> queryGateway.query(new FindPost(id), PostView.class))   // Supplier
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
```

The `Supplier` is the detail that decides: the overload taking a **ready** `CompletableFuture` would
require it to already exist — meaning `gateway.send(...)` would have run on the event loop. It is the
difference between real offloading and apparent offloading.

This was once a utility (`interfaces/graphql/support/Dispatch`, a two-line method). It went away: these
are two Mutiny calls, and hiding them behind a name of their own cost a class and an indirection to save
nothing — in WebFlux we called it directly. The *why* of the `Supplier`, which is the only non-obvious
thing there, stayed in the layer's `package-info`, where it applies to every resolver instead of one.

**The `axon-reactor` extension was not used.** Axon 5's core `QueryGateway` already returns a
`CompletableFuture` and, for subscriptions, a Reactive Streams `Publisher` — which
`Multi.createFrom().publisher(FlowAdapters.toFlowPublisher(…))` consumes directly. One less dependency,
and the contract is the JVM standard.

### The subscription that delivered exactly one event

The `subscriptionQuery` `Publisher` **does not honour incremental demand**. Measured on both ends, with
the same Axon and the same three posts:

```java
publisher.subscribe(...)                  // request(Long.MAX_VALUE)  → [um, dois, tres]
publisher.subscribe(oneAtATime)           // request(1) per onNext    → [um]
```

And `request(1)` per item is **exactly** what SmallRye's `SubscriptionSubscriber` does. Result: the
handshake completes, the first event arrives, the connection stays open and silent forever, and nothing in
the log complains. The four existing subscription tests all passed, because each one asserted about **one**
event.

```java
return Multi.createFrom()
        .publisher(FlowAdapters.toFlowPublisher(queryGateway.subscriptionQuery(…)))
        .onOverflow().buffer(UPDATE_BUFFER);   // ← separates the two demands
```

The operator makes Mutiny request unbounded from Axon and serve the downstream subscriber out of its own
buffer. The ceiling exists so the failure is loud if a subscriber stalls for good — better a
`BackPressureFailure` than memory growing silently. `theSameSubscriptionKeepsReceivingEventAfterEvent` is
the test that would have caught this, and now does.

### The third endpoint: GraphQL over SSE

The conversion table above said the Spring project served subscriptions over SSE and this one serves them
over WebSocket. The first half was a Spring for GraphQL choice; the second was a **limitation**: SmallRye
GraphQL 2.18.5 and the Quarkus 3.39 extension have not one line of `text/event-stream`. Searching the jars
for `event-stream` finds nothing, and `SmallRyeGraphQLConfig` only knows `websocketSubprotocols`.

`interfaces/graphql/sse` is the missing endpoint, in three classes and ~250 lines, in the *distinct
connections* mode of the
[`graphql-sse`](https://github.com/enisdenjo/graphql-sse/blob/master/PROTOCOL.md) protocol: one request
per operation, each result becomes `event: next`, the end becomes `event: complete`.

```bash
curl -N -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"query":"subscription { onPostCreated { title } }"}'

event: next
data: {"data":{"onPostCreated":{"title":"primeira","version":1}}}
```

**Why it was worth writing.** It is not performance — it is infrastructure. SSE is an ordinary HTTP
response that never ends: it crosses proxies and load balancers that cannot do an `Upgrade`; the token
goes in the request's own `Authorization` header instead of travelling in `connection_init` or the query
string; the browser reconnects by itself; and `curl -N` debugs it. What you lose is the back channel,
which in a subscription costs nothing. Since `GET` is also accepted and the browser sends
`Accept: text/event-stream` on its own, a
`new EventSource('/graphql?query=subscription{onPostCreated{title}}')` works with no library at all.

**What was NOT rewritten.** The handler inherits from `SmallRyeGraphQLAbstractHandler`, the same class
Quarkus's HTTP handler and WebSocket handler descend from. It is what activates ArC's request context,
publishes the `SecurityIdentity` and — the easiest one to forget — carries the context state in
`metaData`, which is how asynchronous data fetchers reactivate it on the worker thread. Inheriting is what
makes the three endpoints behave **the same**: same schema, same `@RolesAllowed`, same
`ErrorTranslationInterceptor`, same depth ceiling. Measured: `createPost` without a token through the SSE
endpoint returns the same `extensions.code: UNAUTHORIZED` with the same message as through the HTTP one.
The price is a dependency on a class from an extension's `runtime` package, which is not public API —
noted where it is used.

And the `onOverflow().buffer(...)` from the previous section fixes both endpoints at once: the SSE
subscriber also requests one item at a time, so without it the SSE subscription would fail in exactly the
same way. `SseSubscriptionE2ETest` is the WebSocket test's sibling, and proves it.

**GraphiQL does not use this endpoint, and that is not a symptom of anything.** The UI at
`/q/graphql-ui/` still opens a WebSocket because the `render.js` Quarkus serves says, literally:

```js
var defaultHeaders = { Accept: 'application/json', 'Content-Type': 'application/json' };
const fetcher = createGraphiQLFetcher({
    url: getUrl(),
    subscriptionUrl: getWsUrl(),   // ws://localhost:8080/graphql
    headers: mergedHeaders,
});
```

Those two lines explain everything: the `Accept: application/json` makes the SSE route hand the request
back with `ctx.next()` — which is the correct behaviour and what
`theSamePathStillAnswersJsonToWhoDidNotAskForAStream` locks down — and the `ws://` `subscriptionUrl` makes
`createGraphiQLFetcher` build a `graphql-ws` client. Without `subscriptionUrl` it does not fall back to
SSE: it **throws** ("not properly configured for websocket subscriptions"). No Quarkus configuration
changes this: `SmallRyeGraphQLProcessor`'s `updateUrl` only rewrites the `const api` and `const logo` lines
of `render.js`; the `subscriptionUrl` one comes hard-coded from the webjar.

To exercise the SSE endpoint by hand, use `curl -N` (the example above) or, in the browser console:

```js
new EventSource('/graphql?query=subscription{onPostCreated{title}}')
    .onmessage = e => console.log(e.data);
```

A GraphiQL that spoke SSE would require serving a page of our own with a `graphql-sse` fetcher — doable,
but that is a UI to maintain alongside Quarkus's, and it is not what a proof of concept needs to prove.

**The trap, which is route ordering.** The SSE endpoint is the *same* `/graphql` route, chosen by
`Accept` — which requires registering it between the security handlers and the GraphQL execution handler.
The reflex is to pick a high, safe number for `order`; and it is wrong. Quarkus numbers the application's
routes **sequentially**, with a single digit:

```
order=-99  SmallRyeGraphQLOverWebSocketHandler   /graphql
order=  2  SmallRyeGraphQLSchemaHandler          /graphql/schema.graphql
order=  4  SmallRyeGraphQLExecutionHandler       /graphql
```

With `order=1000` the SSE route lands *after* the execution one, which answers `406 Not Acceptable` to
whoever asked for `text/event-stream` — and the new handler never runs. The right number is anchored to
the same constant Quarkus uses (`-SecurityHandlerPriorities.AUTHORIZATION + 2`), one notch after the
WebSocket. A second Vert.x detail on the same line: Vert.x Web **pauses** the request when it starts
routing, and what resumes it is the `BodyHandler`. Since this route has none — on purpose, so as not to
read the body twice on the requests it hands back with `ctx.next()` — a `request.resume()` is missing, and
without it the POST hangs until the client gives up.

---

## Security: what disappeared

The Spring project's `SecurityConfig` had a filter chain, a `JwtAuthenticationConverter` and a
seventy-line `KeycloakRealmRolesConverter` — the last one only because Spring's stock converter does not
read a nested claim and because Spring requires the `ROLE_` prefix.

None of that has an equivalent here. `quarkus-oidc` discovers the JWKS from `auth-server-url`, validates
signature/`exp`/`iss` and puts the `realm_access.roles` roles into the `SecurityIdentity` **as they are**.
What is left is a one-line producer (`PasswordVerifier` over `BcryptUtil`).

The split is still the same, and it is the one a GraphQL endpoint demands: the application does **not**
authenticate every request (`quarkus.http.auth.proactive=false`), because `post`/`posts` are public and
`me`/`createPost` are not, and both arrive through the same `POST /graphql`. The method is what
authorizes.

One real behavioural difference, and for the better: Quarkus distinguishes **`unauthorized`** (no token)
from **`forbidden`** (token, no role). In Spring both arrived as `FORBIDDEN`.

---

## Dev Services: three test classes became three lines of configuration

The Spring project needed `Containers` (the `static` block that brought up Postgres and Keycloak in
parallel), `KeycloakContainerConfig` (the container with the realm) and `KeycloakTokens` (the `password`
grant). Plus `docker compose up -d` before `spring-boot:run`.

```properties
quarkus.keycloak.devservices.realm-path=realm-axon-posts.json
quarkus.keycloak.devservices.realm-name=axon-posts
quarkus.keycloak.devservices.create-realm=false
```

Quarkus brings up both containers in dev and in test, imports **the same realm file** that
`docker-compose.yml` mounts (it lands on the classpath through the `pom.xml` `<resources>` block, with no
copy), and injects the URLs. `./mvnw quarkus:dev` and `./mvnw test` only need Docker running.

`docker-compose.yml` remains, for two cases: running the packaged JAR, and having a Keycloak admin console
to edit the realm by hand.

---

## Code-first schema, and what it solved

The Spring project kept, at the end of `posts.graphqls`, a block of SDL **generated by a test** with the
types `ConnectionTypeDefinitionConfigurer` would create — because the schema was a file and what Spring
assembled in memory did not exist for the IDE or for client generators.

Here the schema **is** generated, and Quarkus serves it at `/graphql/schema.graphql`. There is no second
definition to keep in sync. What was a test that rewrote a file became a test that reads the only
definition there is.

The root `schema.graphql` is a **copy** of that SDL, for client generators and so the review diff shows
what a change did to the contract. Nothing reads it at runtime; to update it:

```bash
curl -s http://localhost:8080/graphql/schema.graphql > schema.graphql
```

### The depth ceiling, which comes on and is too low

SmallRye turns on a `MaxQueryDepthInstrumentation` **by default, at 10**, and there is nothing to
configure to find that out — only to fix it. Ten is less than this schema needs in two places:

- the introspection query has depth **15**, so GraphiQL opens blank with a single
  `"maximum query depth exceeded 15 > 10"` — and no client generator works;
- `posts { edges { node { author { posts { edges { node { tags { edges { node { name` is **11**, which
  means a legitimate Relay client is already refused.

The symptom misleads because almost everything stays up: the application starts, the SDL at
`/graphql/schema.graphql` is served normally (it is HTTP, not a query) and every shallow query answers.

```properties
quarkus.smallrye-graphql.instrumentation-query-depth=20
```

Twenty keeps the defence against pathological nesting — which is why the ceiling exists — with room for
the deepest thing the schema offers. It is `ConnectionArgs.MAX_LIMIT`'s sibling: an explicit, annotated
limit instead of an inherited default. `SchemaIntrospectionTest` guards both cases.

Two things code-first charges for, and both are annotated on the classes:

- **the names**: `PostView` needs `@Name("Post")`, and `CreatePostInput` needs
  `@Input("CreatePostInput")` (otherwise SmallRye would call it `CreatePostInputInput`). It is the same
  problem `ClassNameTypeResolver` solved in a `@Bean` — now the answer is on the class itself;
- **the polymorphic interface**: `UserView`'s accessors carry `@Name` because SmallRye's
  `InterfaceCreator` only counts as a field a method that looks like a getter *or* that carries `@Name`.
  An interface with no fields is silently discarded, and the error shows up at startup as
  `type User not found in schema`. `RelaySchemaTest` guards that too.

Contract gains that came for free: `createdAt` is `DateTime!` instead of `String!`, and `provider` is the
`AuthProvider!` enum — in the hand-written SDL the field was `AuthProvider!` while the DTO carried a
`String`, and nobody was obliged to notice.

---

## Federation: this schema as an Apollo subgraph

The schema stopped being a whole graph and became **a subgraph of a supergraph**. Nothing that existed
changed behaviour — `posts`, `me`, `createPost`, the subscriptions and the SSE endpoint are still
identical for anyone talking straight to this application. What was added is the contract the router
reads, and the two fields it calls.

```graphql
schema @link(import: ["@key", "@shareable"], url: "https://specs.apollo.dev/federation/v2.7")

type  Post   @key(fields: "id")                    { id: ID! ... }
type  Tag    @key(fields: "id")                    { id: ID! ... }
interface User @key(fields: "id")                  { id: ID! ... }
type  Author implements User @key(fields: "id")    { id: ID! ... }
type  Reader implements User @key(fields: "id")    { id: ID! ... }
type  PageInfo @shareable                          { ... }
```

Wiring this up was **one configuration line and a handful of annotations** — SmallRye already ships the
whole of Federation 2, including `_service`, `_entities` and the directives up to 2.7. The real work was
not enabling it; it was deciding what this service **owns** and writing the resolvers that pay for that
promise.

### A key is not an id: it is an id plus a way to resolve it on your own

A `@key` tells the router "send me back `{__typename, id}` and I will rebuild the object". That is a
promise **composition accepts without ever testing it** — if nobody fulfils it, the supergraph composes,
starts, and breaks on the first query that hops between subgraphs.

The ones that fulfil it are the `*EntityApi` classes in `interfaces/graphql/api/`, with `@Resolver`. The
`Tag` case is what makes the difference visible: inside this schema it **never had** a query by id — you
only reach a tag from a post, through `Post.tags`. That was enough while the schema was a single one. A
neighbour keeping statistics per tag references `Tag` by key without ever having seen a post, and the
router comes back here asking for `_entities` — not `posts`. Having an id did not make it an entity;
having a way to resolve it in isolation does.

`Post` is the instructive opposite: `Query.post(id:)` already existed and SmallRye would find it on its
own — it looks for the resolver first among the `@Resolver` methods and **then among the queries**,
matching by return type and argument name. `Post`'s `@Resolver` exists for the other reason, batching.

### `@Resolver` is not `@Query`, and the match is by signature

A `@Resolver` **does not appear in the schema**: it goes into a synthetic type SmallRye assembles on the
side and serves only `_entities`. That is what avoids publishing one query per entity just to make the
protocol work — `Query.post(id:)` stays what it is, a client operation.

Picking the method is not by name. Each representation becomes the pair *(type, set of argument names)* —
`("Post", {"id"})` — and SmallRye looks for a field returning `Post` whose arguments are **exactly** that
set. Renaming `id` to `postId` compiles, passes `FederationSchemaTest` and only breaks when someone
queries `_entities`.

It is also why the user aggregate has **three** resolvers for a single query. The match is by return
type, and `List<UserView>`, `List<AuthorView>` and `List<ReaderView>` are the same erasure in Java and
three different GraphQL types — which is exactly what the match uses.

### `@Id` on the batched argument is the expensive trap

A batched resolver's argument is `List<String>` **without `@Id`**, and the absence cost a debugging
session. SmallRye's `ReferenceCreator` tests for `@Id` **before** unwrapping the collection: with the
annotation it asks for an `ID` scalar for `java.util.List`, the argument's expected type stops being
`String`, and each id becomes "a String where an object was expected" — which SmallRye tries to read as
JSON.

What reaches the client says none of this. The transformation error becomes a `DataFetcherResult` with no
data, which `FederationDataFetcher` discards, and the response is a
`NullPointerException: resultList is null` with no mention of any argument. The argument's type in the
schema is irrelevant — the `Resolver` type is not published and `_entities` hands over raw values, with no
coercion. Only `FederationEntitiesE2ETest` catches it.

### Batching: the same N+1, now across the router

```properties
quarkus.smallrye-graphql.federation.batch-resolving-enabled=true
```

**Off by default**, and it is the line that separates one `_entities` call with N keys from N trips to the
database. On, SmallRye first looks for a `@Resolver` returning a **list** of the type and hands over the
whole batch at once; off, it calls one method per representation. It is the sibling, across the router, of
what `@Source List<T>` already did inside the schema — and `FederationEntitiesE2ETest` measures it the
same way `BatchLoadingE2ETest` does: five representations must cost the same number of statements as one.

The batch contract is strict and checked at runtime: **one position per representation, in the order they
arrived**. An id that no longer exists becomes `null` at *that* position — returning a shorter list would
shift everything the router appends afterwards. That is why the resolvers project the requested id list
over a map, instead of returning what came out of the database.

And the list element **cannot** carry `@NonNull`: with `[Post!]` the return-type match fails —
`FederationDataFetcher` unwraps the list and expects a *named* type, not a `NonNull` — and the batch stops
being used without a line of logging.

### `interface User @key`: why the interface is an entity too

`User` is an interface because the domain hierarchy is polymorphic, and that did not change. What `@key`
on the interface adds is Federation 2.3's **entity interface**: a neighbouring subgraph declares

```graphql
type User @key(fields: "id") @interfaceObject {
  id: ID!
  commentCount: Int!
}
```

and gains `commentCount` on **every** implementation — today `Author` and `Reader`, tomorrow whatever
there is — without knowing they exist. It sees a single type. Without this, a new field for every user
would require declaring each concrete type there, and again for every new type here.

`docker/federation/comments-example.graphql` is that subgraph, written as SDL only, and
`supergraph-example.yaml` composes it alongside: it is the proof, by composition and not by prose, that
the keys published here are enough for someone to extend the graph.

Asking for the wrong type answers `null`, not the other type: a reader's id asked for as `Author` does not
become an `Author`. Answering with the `Reader` would be worse than not answering — the router would
append `Author` fields to an object that is not one.

### `PageInfo` is the only shared type

In Federation 2 a field belongs to **one** subgraph and composition refuses two owners. `PageInfo` is the
structural exception: it is not an entity, it has no owner, it is the shape of a page — and every subgraph
that paginates writes its own. `@shareable` is what says those definitions are the same thing.

`PostConnection`, `PostEdge` and their siblings do **not** carry the annotation, and the omission is the
decision: they carry `Post` and `Tag`, which are entities from here. If another subgraph defined them, it
would be a real conflict — and refusing is right.

### The `@link`, and what happens without the `import`s

The specification version is **pinned literally** in `FederatedSchemaApi`, and does not come from the
`Link.FEDERATION_SPEC_LATEST_URL` constant the library offers. The `@link` version determines which
directives the router accepts from this subgraph: it is contract, and contract does not change as a side
effect of a dependency bump.

The `import`s are not decoration. With no `@Link` at all, SmallRye emits the directives with short names.
With a `@Link` that does **not** import the directive in use, it comes out prefixed — `@federation__key`.
Both forms compose; only the second forces whoever reads the SDL to know what it is. That is why
`FederationSchemaTest` asserts both things: that `@key` comes out short, and that a non-imported one
(`@federation__external`) stays prefixed — the second assertion is what proves the first is not a
coincidence.

The `@Link` lives alone in a `@GraphQLApi` class with no operations. It is a `SCHEMA` directive, and
SmallRye only collects those on API classes; either it lives on one of the existing APIs, unrelated to the
resolvers beside it, or it lives alone. And **there can only be one**: repeating the Federation `@link` in
another class brings the application down at startup.

### The two SDLs, and which one composes

| | `/graphql/schema.graphql` | `{ _service { sdl } }` |
|---|---|---|
| who serves it | Quarkus, as a file | the subgraph specification's field |
| who consumes it | client generator, IDE, the review diff | `rover`, the router |
| contains | the schema + `@link` + `@key` | the same, plus `_entities`/`_service`/`_Any` |

They are the same contract through two doors, and the root `schema.graphql` is still the committed copy of
the first. For it to keep **composing**, two lines had to go in:

```properties
quarkus.smallrye-graphql.schema-include-directives=true
quarkus.smallrye-graphql.schema-include-schema-definition=true
```

The first because `@key` and `@shareable` stopped being decoration and became the contract. The second
because the `schema { ... }` block is what carries the `@link` — and **a subgraph without a `@link` is
read as Federation 1 during composition**. The price is the file doubling in size with directive
definitions that never change; what it buys is an SDL `rover` accepts straight from the repository,
without starting anything. `FederationSchemaTest` checks that both lines are still there.

### Running federated

```bash
# 1. the subgraph. The 0.0.0.0 is NOT a detail: in dev Quarkus listens only on 127.0.0.1, and the router
#    runs in a container — without this it cannot reach the application.
./mvnw quarkus:dev -Dquarkus.http.host=0.0.0.0

# 2. compose. rover runs the federation introspection (`{ _service { sdl } }`) against the running app
rover supergraph compose --config docker/federation/supergraph.yaml > docker/federation/supergraph.graphql

# 3. the supergraph
docker compose --profile federation up -d router     # http://localhost:4000
```

```bash
# the mutation crosses the router with the token propagated, and the post is born at version 2 as always
curl -s localhost:4000/ -H 'content-type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { createPost(input:{title:\"Via supergraph\",content:\"c\"}) { id version } }"}'
```

Three things that cost time if nobody writes them down:

- **the router forwards no headers by default.** Without the `headers` block in `router.yaml`, `me` and
  `createPost` answer `UNAUTHORIZED` behind the supergraph and work directly — the one authorizing is
  still `@RolesAllowed`; what changes is that there is now a hop that can silently eat the token;
- **the router version is not the `federation_version`.** One is the binary, the other is the composition
  algorithm. `v2.9.3` exists as a composition and does not exist as an image: `manifest unknown` on pull;
- **`subgraph_url` goes nested under `schema:`** in `supergraph.yaml`. One level up, rover does not
  complain — it discards the subgraph and fails with `No subgraphs were found in the supergraph config`.

### Subscriptions behind the router: licensed

`subscription.enabled: true` is a GraphOS feature. Without `APOLLO_KEY`/`APOLLO_GRAPH_REF` the router does
not start degraded — it **refuses to start**:

```
license violation, the router is using features not available for your license: ["Federated subscriptions"]
```

That is why the block is commented out in `router.yaml`: leaving it on would make
`docker compose --profile federation up` broken for anyone who just wants to see the federated proof of
concept.

With a licence, the router would speak `graphql-transport-ws` to this subgraph, on the same `/graphql`
SmallRye already serves — and it is worth recording what that implies on the other side: the supergraph's
client **does not open a WebSocket**; it receives the subscription over HTTP multipart, and the WebSocket
exists only between router and subgraph. The SSE endpoint written in `interfaces/graphql/sse` is still
what it always was, the way to subscribe **talking directly** to this service. The router does not use it,
and that is no defect of either.

### The subgraph is not the boundary

`_entities` is public and resolves **any** entity by key, without a token. That is neither SmallRye's nor
this project's oversight: it is Federation's operating premise — the subgraph sits on the internal network
and the router is what is exposed. It is worth saying what that changes here, because it does change
things:

- `Post`, `Tag` and `Author` were already reachable anonymously through `post`/`posts` and `Post.author`;
- **`Reader` was not.** Now `_entities` returns a reader's e-mail and accounts to anyone who knows the id.

Publishing this application straight to the internet, therefore, exposes more than before. Behind the
router, it does not — and field authorization still holds the same across all three endpoints, because
`@RolesAllowed` runs on the method.

The official path for authorization in the router itself is the `@authenticated`, `@requiresScopes` and
`@policy` directives, which SmallRye also exposes as annotations. They were **not** used here for two
reasons: they are evaluated by licensed GraphOS features, and they would duplicate in the schema a
decision that is already on the method — which is where this project insists on keeping it.

### What was not done, and why

`@external`, `@requires`, `@provides` and `@override` exist in SmallRye and appear **nowhere**. They serve
a subgraph that extends somebody else's type; this one extends none — it owns everything it declares. A
federation annotation written "to demonstrate" would become fiction in the SDL the router reads. When
there is a real neighbour to extend, `comments-example.graphql` shows the shape.

---

## Error translation

Spring GraphQL has `@GraphQlExceptionHandler`: one method, in a bean, and every exception from every
controller goes through it. SmallRye has no equivalent — its `EventingService` *observes* the error, but
does not replace it.

What Quarkus has is **CDI**, and that is enough. SmallRye does not instantiate the `@GraphQLApi`: it asks
`LookupService` for it, which here is CDI, and what comes back is ArC's *client proxy* — so the resolver
call enters the interceptor chain like any other. It is the same reason `@RolesAllowed` and `@Valid`
already worked there.

```java
@GraphQLApi
@ApplicationScoped
@TranslatesErrors           // one annotation per class; the resolvers do not know it exists
public class PostQueryApi { … }
```

`ErrorTranslationInterceptor` calls the same `GraphQlErrors` as before — the classification is still an
`if` per family walking the **cause chain**, because an exception from inside a command arrives wrapped by
the gateway's `CompletableFuture` and by the `ProcessingContext` commit.

**What the interceptor fixes**, and a call in the resolver body could not: the **synchronous** path. A
`@Valid` that blows up happens *before* the method runs, so it never reached `translating(…)` — an invalid
input came out as a `ValidationError` **with no `extensions.code`**, despite the documented decision being
`BAD_REQUEST`. Now both paths converge in the same place:

```
createPost(input: {title: "", content: "y"})
  before: { classification: ValidationError, violations: [...] }          ← no code
  now:    { code: BAD_REQUEST, message: "title must not be blank" }
```

The trade-off is that SmallRye's detailed `violations[]` gave way to the messages joined by
`GraphQlErrors.describe` — the same message a value object violation would produce, which is the point of
validation at two heights.

Spring's `extensions.classification` became `extensions.code`, fed by four exceptions with SmallRye's
`@ErrorCode`: `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`.

### Security came along with it, and that is what fixed the output

Quarkus's security interceptors are `@Priority(150)`; leaving ours at `APPLICATION` (2000) meant running
**inside** them, and the refusals escaped untranslated. The result was bad in three ways at once:

```jsonc
// no token, before
{"message": null,                      // ← io.quarkus.security.UnauthorizedException has no message,
 "extensions": {"code": "unauthorized"}}  //   and listing it in show-runtime-exception-message published the null

// no token, now
{"message": "credenciais inválidas ou ausentes",
 "extensions": {"code": "UNAUTHORIZED"}}
```

1. **`"message": null`** — worse than "System Error", because it looks like an application bug;
2. **a lowercase `code`**, derived from the Quarkus class name, living next to the project's
   `BAD_REQUEST`/`FORBIDDEN`/`NOT_FOUND`;
3. and in the log, the raw `AuthenticationFailedException`: Mutiny's entire stack, a `CompositeException`
   and a `Caused by: [CIRCULAR REFERENCE: ...]` — fifty lines to say "invalid token".

`@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)` puts the translation outside everything and solves
all three at once: the refusal becomes a classified exception, with a message and a code, and the log
starts showing **one line** (`SRGQL012000: ... ForbiddenException: sem permissão para esta operação`)
because the translated exception is shallow and has no cause. What is **not** expected still comes up
whole, with cause and stack — `GraphQlErrors.translate` returns the original when nothing matches.

The distinction Quarkus gives for free still holds: no token is `UNAUTHORIZED`, token without the role is
`FORBIDDEN`. Only the names became consistent with the rest.

**What cannot be fixed from here**: a token that is present but has an invalid signature is refused by
Quarkus's `HttpAuthenticator` *before* any resolver, and the response is **HTTP 401 with an empty body** —
no `errors`. An absent token goes through `@Authenticated` and becomes a GraphQL error; a rotten token
never gets there. Changing that would require an `HttpAuthenticationMechanism` of our own, which is a lot
of machinery for what it buys.

---

## Tests

131 tests, at the same three heights as the original project:

| | what it exercises |
|---|---|
| `domain/*` | pure domain: no Axon, no CDI, no JPA. The only collaborator is `RecordingDomainEvents` |
| `application/*` | `AxonTestFixture` given-when-then, one per command, with an in-memory repository |
| `interfaces/graphql/relay/ConnectionsTest` | cursor ↔ offset, page ceiling, connection assembly |
| `interfaces/graphql/relay/RelaySchemaTest` | the generated SDL carries `PostConnection`/`PostEdge` and the `interface User` |
| `interfaces/graphql/SchemaIntrospectionTest` | GraphiQL can introspect, and the deepest Relay query passes |
| `e2e/SseSubscriptionE2ETest` | the same newsletter through the SSE endpoint, and the guard that the JSON POST did not change |
| `e2e/*` | HTTP → realm token → `@RolesAllowed` → Axon → domain → JPA → projection → JSON |

The domain and command tests crossed over **without a line changed** — they never knew Spring existed.

---

## In-memory event store

As in the original project: `InMemoryEventStorageEngine`, Postgres only for the read model, no Axon
tables. **Every restart wipes the events while the rows stay in Postgres.** A post created before a live
reload still answers `post(id:)` and starts returning `NOT_FOUND` on `updatePost`, which rehydrates the
aggregate from the stream. It is not a bug; it is the proof of concept — and Quarkus's live reload makes
this more frequent than Spring's DevTools did.

Switching to a persistent event store means adding the `quarkus-axon-jpa-eventstore` dependency.

---

## AWS Lambda: the same system, another deployment target

A second target, and it is **additive**: no file that existed before it was changed. The same two
applications, packaged by Maven profile, become four functions; RabbitMQ becomes an SNS FIFO topic with
three SQS FIFO queues subscribing by filter policy.

What this proves about the decisions taken so far is more interesting than the migration itself.

**`ChannelAddressing` paid off what it promised.** Its Javadoc said, from before a single line of AWS
existed: *"A new protocol = one more `ChannelAddressing`"*. It was literally that — `SnsAddressing` and
`SqsAddressing`, one class each, plus one `.properties` line per channel. The `@AxonOutbox` of both
applications did not change, because what a service publishes is its own contract and does not change by
environment.

**The layering rule paid off too.** The `@Incoming` methods are still the entry point: the channel becomes
`smallrye-in-memory` and the Lambda handler pushes the record into it, so `PostPreCreatedListener` and its
siblings run without a line changed — with the `@Blocking(ordered = false)` and the Axon unit of work
already measured there. The Lambda handler is not the entry point; it is the transport, the place
equivalent to the RabbitMQ connector.

**And the ordering key found its reason to exist.** `EventAddress.orderingKey()` was the third segment of
the routing key, and `application.properties` itself admitted in writing that it broke no ties. On a FIFO
queue it is the `MessageGroupId` — that is, it is what makes ordering exist. And it is not optional:
`apps/tagging` writes to the `Post` stream, and in an event store in *aggregate mode* an out-of-order
event makes the next append land on `duplicate key ... uk_aggregateevententry_aggregate`.

**What regressed, and it is honest to say so:** subscriptions do not work on Lambda — neither over
WebSocket nor over SSE, and the transport is the smaller half of the problem, because
`SimpleQueryBus.emitUpdate` is in-process and the one appending the `PostCreated` is another function. The
single trace regresses too, because on the inbound side there is no connector to instrument and the SNS
connector has no tracing. Both, with the measurements backing them and the paths that would solve them,
are in **[`infra/aws/README.md`](infra/aws/README.md)** — the document of that migration, decision by
decision.

```bash
npx sst deploy --stage dev                # the deploy BUILDS the four zips: each function declares
                                          # in `code` the Nx target that builds it
npx nx run "dev.manuelantunes:axonposts-tagging:lambda"   # or one artifact by hand
```

## Running

```bash
# dev: Dev Services bring up Postgres + Keycloak; only Docker is needed
./mvnw quarkus:dev
#   GraphiQL   http://localhost:8080/q/graphql-ui/
#   SDL        http://localhost:8080/graphql/schema.graphql
#   Dev UI     http://localhost:8080/q/dev/   (the Keycloak URL shows up there)

# tests (same thing: only Docker)
./mvnw test
./mvnw test -Dtest=PostTest                      # one class
./mvnw test -Dtest='*E2ETest'                    # end-to-end only

# packaged JAR, against docker-compose
docker compose up -d                             # POSTGRES_PORT=5433 if 5432 is taken
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
docker compose down -v                           # full reset
```

### The token has to come from the Keycloak the application is validating against

This is the easiest trap to fall into, because **there are two Keycloaks**: the `docker-compose` one (port
8081, fixed) and the Dev Services one (random port, new on every `quarkus:dev`). In dev mode the
application uses the Dev Services one — `quarkus.oidc.auth-server-url` is only configured in the `prod`
profile.

Taking the token from 8081 and sending it to `quarkus:dev` gives you this in the log, and `unauthorized`
for the client:

```
JWK with kid '…' is not available
Request http://localhost:<random-port>/…/token/introspect has failed: 403 "Client not allowed."
```

It is not a configuration error: it is a signature that application's issuer does not know. Quarkus tries
introspection as a plan B, and the realm does not allow it (it is a public client, with no secret).

So take the issuer from the application itself:

```bash
ISS=$(curl -s localhost:8080/q/dev-v1/io.quarkus.quarkus-oidc/provider | grep -o 'http[^"]*realms/axon-posts')
# or simply: the URL shows up in /q/dev/ and in the startup log

TOKEN=$(curl -s -X POST $ISS/protocol/openid-connect/token \
  -d grant_type=password -d client_id=axon-posts-api \
  -d username=manuel@example.com -d password=segredo123 | jq -r .access_token)
```

With the packaged JAR against compose, the issuer is fixed: `http://localhost:8081/realms/axon-posts`.

### And it has to be an *access token*, not the Keycloak session cookie

The same pair of errors (`JWK ... is not available` + `introspect ... 403`) shows up for a second reason,
and that one is harder to see: whoever logs into the Keycloak console and copies the JWT sitting there
copies the **`KEYCLOAK_IDENTITY` cookie**, which is a legitimate JWT, from the right issuer, and is useless
here.

You can tell them apart without guessing — decode the payload:

| | session cookie | access token |
|---|---|---|
| `alg` | `HS512` (symmetric, the realm's **internal** key) | `RS256` (the key published in the JWKS) |
| `typ` | `Serialized-ID` | `Bearer` |
| claims | `sid`, `state_checker` | `azp`, `scope`, `realm_access.roles`, `preferred_username` |
| lifetime | 10 hours | 30 minutes |

The `HS512` is the explanation for the log: the realm's HMAC key **never** goes into the JWKS, so the `kid`
is genuinely unknown. Quarkus then tries introspection, and `axon-posts-api` is a public client — 403. A
token with no `realm_access.roles` would never get past `@RolesAllowed("author")` either.

The access token comes out of the `grant_type=password` above, and only from there.

Seeded users (password `segredo123`): `manuel@example.com` (role `author`), `leitor@example.com` (no
role), `promovido@example.com` (role `author`, exists to exercise the `Reader` → `Author` promotion). The
realm enables `directAccessGrantsEnabled` only for that `password` grant.

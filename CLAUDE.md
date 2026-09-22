# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Comments: do not write them

> **Write NO comments in code. Not Javadoc, not `//`, not `/* */`, not `#`, not `<!-- -->` — in any
> language, in any file.** The only exception is when the user explicitly asks for a comment.

This repository was deliberately stripped of every comment. Do not reintroduce them.

**Say it in the code instead.** Everything a comment would have said has a home that the compiler,
the test runner or the reader reaches anyway:

- a name — extract a method, a constant or a local variable whose name is the sentence you were
  about to write;
- a test — a case named after the rule is an executable comment that cannot go stale;
- this document — the *why* of a decision, the measurement behind it, the trap somebody already
  paid for. That is what `CLAUDE.md` and the four READMEs are for, and they are the place to write
  it.

**What is NOT a comment, and must be preserved.** These look like comments and are instructions to a
tool; removing them breaks the build:

- `/// <reference path="..." />` — how SST brings its generated types into scope;
- `// eslint-disable-next-line ...`, `// @ts-expect-error`, `// prettier-ignore`;
- `#!/usr/bin/env bash` and any other shebang;
- the Apache licence header in the vendored `mvnw`.

**When something really is inexplicable without prose**, the answer is not a comment: write it in
this file, in the section that owns the subject, and let the code be read on its own.

## Repository shape

A monorepo of two apps over shared modules. The rule that decides where everything lives:

> **`libs/` holds domain and infrastructure. `apps/` holds application and presentation.**

The line is not between services, it is between **layers** — and that is what makes a module
reusable. Domain is rule and infrastructure is how the rule persists: both belong to the module
(`posts`, `users`), and more than one app imports them. Application is flow — which command exists,
which query answers what — and flow belongs to whoever executes it.

```
libs/platform          domain/shared + infrastructure/{axon,time}
libs/users             domain/user/**        + infrastructure/persistence/user
libs/posts             domain/{post,tag}/**  + infrastructure/persistence/post
libs/axon-channels     the Axon ↔ channels integration (outbox + ingestion)
libs/axon-aws          outbound addressing on SNS and SQS (one ChannelAddressing per connector)
libs/axon-lambda       the entry point when the transport is Lambda's event source mapping
libs/axon-native-support  the build extension for GraalVM native

apps/posts-api         application/** + interfaces/{graphql,messaging}/** + infrastructure/security
apps/tagging           the tagging service: application/** + interfaces/messaging/**
apps/web               the CLIENT: Next.js + Apollo, the monorepo's only JavaScript module
```

`apps/web` is not Maven — it is a pnpm workspace package, and that is why it does not appear in the
pom's `<modules>`. It implements no rule at all: it consumes the API through the GraphQL edge, like
any client would. The layering rule above does not apply to it; its own is in `apps/web/README.md`.

What this solves: `apps/tagging` imports `libs/posts` and gets `Post`, the events, the rules and the
repositories. It does **not** get the other app's GraphQL, projection or command handlers — which
Axon discovers at build time and would wire against tables it does not have.

`apps/posts-api` is the only one with `infrastructure/`: `CurrentUser`/`SecurityProducer` are OIDC
and HTTP, which only it has — and `CurrentUser` depends on `UserProvisioning`, which is application.
In the lib, that would be a cycle.

## Commands

In dev and in test **nothing has to be started by hand**: Quarkus Dev Services brings up Postgres and
Keycloak (with the realm imported) on its own. Docker running is all it takes.

**`-pl <module>` DOES NOT work for BUILD goals**, with or without `-am`: `axon-native-support`'s
`quarkus-extension-maven-plugin` validates that the deployment artifact is in the reactor, and a
partial build leaves it out — `Deployment artifact ... is missing the following dependencies`. To
compile, package or test, run from the root and filter with `-Dtest=`.

**For `quarkus:dev` the `-pl` works, and it is the right way.** The goal does not build the
extension — it resolves it from `~/.m2` and discovers the libs through the reactor's workspace — so
the validation never runs. Without `-pl` Maven would walk all eight modules serially to bring up two
applications. That is what `pnpm dev` uses.

```bash
pnpm dev                       # BOTH backends AND the web client, in parallel — see the section below
pnpm --filter @axonposts/web dev   # the client only, at http://localhost:3000
pnpm --filter @axonposts/web exec vitest   # the client's unit tests, in WATCH mode
./infra/scripts/package.sh     # the FOUR Lambda zips (Nx targets) — see *AWS Lambda*
./mvnw install -DskipTests -pl '!apps/posts-api,!apps/tagging'   # the libs into ~/.m2 (see below)
./mvnw quarkus:dev -pl apps/posts-api   # a single application
./mvnw test                    # the whole suite — REQUIRES Docker
pnpm test                      # the LOWER LEVEL, through Nx: Java + the `web` unit tests (see *Tests*)
pnpm test:e2e                  # the THREE heavy levels, serially — see *Tests*
npx nx run web-e2e:test:e2e    # only the browser level (Playwright) — see *apps/web-e2e*
./mvnw clean verify -Dnative.it -Pnative-clt-toolchain   # the NATIVE binary + the *NativeIT classes
npx nx run "dev.manuelantunes:quarkus-axon-graphql-posts:test:native"   # the same thing, cached
pnpm lint                      # ESLint on the JS packages + Spotless on the 8 Java modules
pnpm lint:fix                  # fixes BOTH sides at once (see *THE LINT*)
./mvnw package                 # build + tests
./mvnw test -Dtest=PostTest                                       # one class
./mvnw test -Dtest=PostLifecycleE2ETest#aNewPostArrivesAlreadyTaggedAtVersionTwo   # one method
./mvnw test -Dtest='*E2ETest'                                     # end-to-end only
open target/jacoco-report/index.html   # coverage — quarkus-jacoco runs along with `test`
open http://localhost:3001     # Dev Services Grafana: traces, logs and metrics FOR BOTH SERVICES
```

Federated (Apollo Router in front). The `-Dquarkus.http.host=0.0.0.0` is **not** a detail: in dev
Quarkus listens only on `127.0.0.1` and the router runs in a container — without it the router cannot
reach the application.

```bash
./mvnw quarkus:dev -Dquarkus.http.host=0.0.0.0
rover supergraph compose --config docker/federation/supergraph.yaml > docker/federation/supergraph.graphql
docker compose --profile federation up -d router          # http://localhost:4000
rover supergraph compose --config docker/federation/supergraph-example.yaml   # composes with a fictional neighbour
```

`docker-compose.yml` serves **three** cases: running the packaged JAR (`prod` profile), having the
Keycloak admin console, and — behind the `apps` profile — the two application containers that
`apps/web-e2e` drives. The profile is what keeps a bare `docker compose up -d` unchanged for the
first two. The containers carry a `quarkus-` prefix so they do not clash
with the original Spring project's, and the host ports are variables (`POSTGRES_PORT`,
`KEYCLOAK_PORT`).

```bash
docker compose up -d && ./mvnw package && java -jar target/quarkus-app/quarkus-run.jar
docker compose down -v         # full reset
curl -s localhost:8080/q/health | jq    # includes "Axon eventprocessors", from the extension
```

### `pnpm dev`: nx runs the processes, Maven resolves the modules

`pnpm dev` = `nx run-many --target serve`. Each app's `serve` target is an `nx:run-commands` declared
in `apps/*/project.json`, and all it does is `./mvnw quarkus:dev -pl apps/<app> -Ddebug=<port>`. Nx
here is **only the parallel executor of two long-running processes**; what resolves dependencies
between modules is still the Maven reactor.

**The libs have to be installed into `~/.m2`, and `pnpm dev` does not install them.** With `-pl` and
without `-am`, Maven resolves `axonposts-platform` and friends from the local repository — so new
code in a lib (and there is code there now: `AxonMetrics`) only reaches the applications after a
`./mvnw install -DskipTests -pl '!apps/posts-api,!apps/tagging'`. The filter is by exclusion and not
by enumeration: it keeps BOTH `axon-native-support` modules in the reactor, which is what the
extension's validation requires, does not need editing when a new lib arrives, and skipping the
applications avoids their `quarkus:build` — which is exactly the intermittent step documented further
down.

Proven end to end: both applications come up together, Dev Services gives **one Postgres to each**
(its own event store, as the design requires) and **one RabbitMQ, one Keycloak and one LGTM for
both**, and the saga crosses — the post is born at version 1 with no tag and reaches version 2 with
the `Untagged` decided by the other process, in a single trace with both `service.name` values inside
(`http://localhost:3001`, see *Observability* below).

Two collisions between the two processes, and both were actually observed:

1. **The debugger port.** `quarkus:dev` opens JDWP on 5005 by default, and the two fight over it.
   Hence `-Ddebug=5005` and `-Ddebug=5006` in the `project.json` files. Without that the second one
   up dies with `transport error 202: bind failed: Address already in use` — and it is INTERMITTENT,
   because it depends on who got there first: three runs passed before the fourth failed.
2. **Discovery of SHARED Dev Services is a race, and this one is still open.** A shared container
   (RabbitMQ, LGTM, Keycloak) is found by LABEL: whoever comes up first creates it, whoever arrives
   later reuses it. Starting together, both can create before the other is labelled — and that is
   what happened on one run: **two** RabbitMQ instances (each service on its own broker, the saga
   CHANGES), and `posts-api` dying with `Bind for 0.0.0.0:3001 failed: port is already allocated`
   while trying to create a second LGTM. The fixed `grafana-port` turns the silent failure (two
   stacks, broken telemetry) into a loud one — which is better, but is not a fix.
   **A workaround that works, measured:** start them serially, `apps/tagging` first and `posts-api`
   after it is up. Then the topology comes out right every time. The real fix is to make the shared
   services exist BEFORE the applications — the candidate is Compose Dev Services, which this project
   already has on the classpath (`compose` shows up in both apps' *Installed features*) and does not
   use.

**THE ROOT CAUSE OF THE TWO PROBLEMS BELOW WAS THE JDK, and it has been fixed.** Read this section
before those two: this machine's `JAVA_HOME` came from `~/.zshrc`, which zsh reads **only in an
interactive shell**. Every process without a terminal — Nx firing a target, the IDE, a hook — was
born from a non-interactive shell, did not see that line, and inherited an old `JAVA_HOME` pointing
at a **JDK 17**. Hence `class file version 65.0 ... up to 61.0` on inferred targets, which run on an
in-process Maven with the JDK Nx inherited.

`JAVA_HOME` and `GRAALVM_HOME` moved to `~/.zshenv`, which zsh reads on EVERY invocation. After that,
**MEASURED**: `nx run <project>:mvn-test` passes, with the 36 chained `^mvn-install` tasks, **3 runs
out of 3** — the `nx-build-state.json` trap described in item (1) below **does not appear**. It was a
symptom, not the cause.

What still holds from item (2) is the shape, not the verdict: inferred goals run on a resident,
in-process Maven. For `quarkus:dev` that is still a problem — it wants a real CLI — and that is why
`serve` is an `nx:run-commands` with `./mvnw`.

**The original report, kept because its measurement is still correct for what it measures:**

**DO NOT use the `quarkus:dev` target that `@nx/maven` infers.** It does not work, for two
independent reasons, both measured on version 23.2.1 (the latest) and neither configurable:

1. **The plugin decomposes the Maven lifecycle into one target per mojo execution**, so `package`
   runs `jar:jar@default-jar` on its own. Since the same plugin also restores
   `target/nx-build-state.json` — which records `mainArtifact.file` — the mojo finds the artifact
   ALREADY attached to the project and aborts with `You have to use a classifier to attach
   supplemental artifacts to the project instead of replacing them`. It passes once after a `clean`
   and fails on ALL subsequent runs; deleting `nx-build-state.json` fixes that run and the next one
   rewrites it. Since every inferred target depends on `^install`, any plugin target falls into this.
   The `*-ci` targets run the same mojo and have the same defect.
2. **The goals run on a RESIDENT, in-process Maven**, and `quarkus:dev` needs a real CLI: there it
   dies with `Cannot invoke "String.toLowerCase(java.util.Locale)" because "version" is null`. The
   same goal through `./mvnw` starts normally.

Two lines left `nx.json` along with it, and both were traps: `targetDefaults.build` pointed at a
target that **does not exist** in this workspace (`@nx/maven` infers phases, not `build`), and
`targetDefaults.test` overrode the inferred `dependsOn` with that same non-existent `build` —
`targetDefaults` takes precedence over a plugin-inferred target, so `nx test` ran surefire **without
compiling anything first**, silently. The one who runs the suite is `./mvnw test`, from the root, as
always.

`quarkus:dev` reloads by itself on the next request after a class changes. The event store is
**persistent** ever since the saga became choreographed: a reload no longer wipes anything. A post
created before the reload still answers `post(id:)` and starts returning `NOT_FOUND` on `updatePost`,
which rehydrates the aggregate from the stream.

**THE `posts-api` BUILD IS INTERMITTENT, and the cause is not in the project.** In roughly half of
IDENTICAL runs Quarkus augmentation does not find classes that are in `target/classes`:

```
Unsatisfied dependency for type ...PostViewMapper
Producer method return type not found in index: PostInputMapper
Could not load class with name: ...FindAllPostsQueryTest      (and with it the 149 tests)
```

Every time, the `.class` files exist, are correct and (when generated) properly annotated. **Just
repeat the command.** It only appeared once the application layer moved into the app, bringing the
MapStruct annotation processor with it — in `libs/` this never happened.

**Where it hurts and where it does not:** `./mvnw clean test` passes (`test` does not run
`quarkus:build`). What fails is `package` — and therefore the `build` target of `apps/web-e2e`,
which packages before bringing up the applications.

Ruled out by measurement: dirty state in `target/`, snapshots installed in `~/.m2`, a stale Jandex
index in the libs, `quarkus.arc.exclude-types`, a test naming a generated class, processor options in
the execution vs in the plugin, `<proc>none</proc>` in the test round, `useIncrementalCompilation=false`,
a Jandex index in the app, `quarkus.builder.parallel=false` (sequential augmentation does not fix it)
and hand-written bean producers instead of `componentModel` — with those the failure stops being
intermittent and becomes **deterministic** (`Producer method return type not found in index`), which
is worse. Hence the current configuration being the conventional one.

**Main suspect: the JDK — and it was TESTED AND REFUTED.** This machine's JVM is **25**, which
Quarkus 3.39 does not support (`release` is 21), and the next step on record was to run on a JDK 21.
Done, with a Temurin 21.0.12 downloaded just for the measurement: **3 packagings, 3 failures**, with
the SAME exception (`Unsatisfied dependency ... PostViewMapper`). Over the same period, JDK 25 gave
**0 successes out of 6**. The JVM version is not the variable.

Two new observations from the same episode, and both are better leads than the previous one:

- **the failure is FAST — ~4.7s in the module**, with no recompilation. Augmentation runs against a
  `target/classes` that already exists and does not see the impls that are there;
- **the rate is not stable over time.** The document recorded "about half"; in a one-hour window
  there were ~15 failures in a row, including with `clean`. Whatever it is, it has state, and the
  state is not `target/` (see the `clean` measurement in the `apps/web-e2e` section).

Whoever depends on a green packaging today — the `build` target of `apps/web-e2e` and the
`sst deploy` — repeats the command. **This is still open**, and the next step is no longer the JDK.

**THE BYTECODE WAS CHECKED, and it is the lead that remains.** It is not the generated source that is
wrong nor the `.class` that is missing: `javap -v` on both impls shows `RuntimeVisibleAnnotations`
with `Ljakarta/enterprise/context/ApplicationScoped;`, on freshly compiled classes, in the same
minute as the failure. Class present, annotated and fresh — and `ArcProcessor#validate` says
`Unsatisfied dependency`. What does not see it is the INDEX, and that is where the next
investigation has to start.

**And incremental `target/` state IS a trigger, at least for `test`.** After a series of failed
`package` runs, `./mvnw test` started failing too — and `./mvnw clean test` passed again, in 33s.
These are two different augmentation paths (`test` does not run `quarkus:build`), and only the `test`
one recovers with `clean`: for `package`, three runs with `clean` measured the same 1-in-3 as without
it. **If the suite starts failing without anyone having touched the code, `clean` is the first thing
to try.**

**Two rules left over from that episode**, and both stand on their own:

1. **Processor options go at the PLUGIN level**, not in an `<execution>`. Pinned to `default-compile`,
   the test round regenerated the impls without them — without `@ApplicationScoped` — and what was
   left in `target/` depended on who wrote last.
2. **No test names a generated class** (`*MapperImpl`) nor depends on its bean. Whoever needs a mapper
   in a unit test uses a local double; the one exercising the real mapper is the end-to-end suite,
   through the GraphQL edge.

There is no lint/format plugin configured beyond what *THE JAVA SIDE* describes. The quality gate is
the compiler: MapStruct runs with `-Amapstruct.unmappedTargetPolicy=ERROR`, so a destination field
with no source **breaks the build**.

Token for testing by hand:

```bash
TOKEN=$(curl -s -X POST <issuer>/protocol/openid-connect/token \
  -d grant_type=password -d client_id=axon-posts-api \
  -d username=manuel@example.com -d password=segredo123 | jq -r .access_token)
```

In dev the `<issuer>` is the Dev Services Keycloak (random port; see `/q/dev/`); with compose it is
`http://localhost:8081/realms/axon-posts`. **They are two different Keycloaks, and a token from one
is not valid in the other**: a token from 8081 sent to `quarkus:dev` gives `JWK with kid '…' is not
available` followed by `introspect … 403 "Client not allowed."` in the log, and `unauthorized` for
the client. It is not missing configuration — it is a signature from an issuer that application does
not know.

The **same** pair of errors has a second cause: sending the `KEYCLOAK_IDENTITY` cookie (the JWT that
appears when you log into the Keycloak console) instead of an access token. Decoding the payload
separates the two cases immediately — an access token is `alg: RS256`, `typ: Bearer`, with
`azp`/`scope`/`realm_access.roles`; the cookie is `alg: HS512`, `typ: Serialized-ID`, with
`sid`/`state_checker` and no roles. The `HS512` is the clue: the realm's HMAC key does not go into
the JWKS, so the `kid` really is unknown. Seeded users (password `segredo123`):
`manuel@example.com` (role `author`), `leitor@example.com` (no role), `promovido@example.com`
(role `author`, exists to exercise the `Reader` → `Author` promotion).

## Architecture

A proof of concept of Axon Framework **5** (annotated entities + DCB, no Axon Server) with SmallRye
GraphQL over Mutiny and subscriptions over WebSocket. Event store **persistent in Postgres**, with a
token store; Keycloak is the identity provider and the application is only a resource server. **Two
services** talk over RabbitMQ in a choreographed saga.

**One tag per event, and that comes from the framework.** Axon 5.3.1 has exactly two
`EventStorageEngine` implementations: `InMemoryEventStorageEngine`, with full DCB, and
`AggregateBasedJpaEventStorageEngine`, which is the Axon 4 compatibility mode — one tag per event, and
the sourcing query filters only by `aggregateIdentifier` (`aggregateType` does not even take part).
Real DCB on relational storage does not exist outside Axon Server. That was the trade: durability cost
the post events their second `@EventTag`.

This is the conversion of a Spring Boot project — the README is the document of that conversion,
decision by decision. **When a decision changes, update it too.**

### A post's lifecycle has TWO phases

`PostPreCreated` = the post exists. `PostCreated` = the post is **complete** (it has its first tag)
and visible. It is born at version 1 and reaches version 2.

There are two phases because the first tag is no longer decided here: the one who decides is **another
service**, and the message crosses a broker. Pretending that creating and publishing are the same
instant would require waiting for the neighbour inside the write transaction.

```
@Mutation createPost
  → commandGateway.send(CreatePost)
     → Post.create(...)  → PostPreCreated          [v1, no tag]   → posts.save(post)
  → the mutation answers v1                         ~~~ RabbitMQ: posts.PostPreCreated.<postId> ~~~

                                            apps/tagging
                                              → ChannelEventInbox APPENDS to its own event store
                                              → CompleteOnPostPreCreated → CompletePostWithDefaultTag
                                              → Post.complete(...) → PostCreated  [v2, with the tag]
  ~~~ RabbitMQ: posts.PostCreated.<postId> ~~~

  → ChannelEventInbox APPENDS to this side's event store (reading the stream first, for the sequence)
  → PostCreatedProjection materializes the row, INSIDE the append's transaction
  → PostCreatedEventHandler emits onPostCreated — on another processor, in EVERY container (see below)
```

Neither service names the other: one publishes `posts.PostPreCreated` and listens for
`posts.PostCreated`, the other does the inverse. Replacing the tagging service means replacing who
answers that routing key.

**In test the decision is doubled in-process** (`InProcessTagAssignment`, removed from the build in
dev/prod by `@IfBuildProperty`), because eventual consistency makes an in-flight message cross the
`truncate` boundary between tests. The real path is covered by `apps/web-e2e`, outside Surefire.

### The Axon ↔ channels integration, in both directions

- **outbound**: every appended event is offered, after the commit, to **this service's outboxes**, by a
  `MessageDispatchInterceptor`. Generic — no event type is named in code. The routing key comes from
  `@Event` + `@EventTag`: `namespace.Name.aggregateTag`.
- **inbound**: every received message is **appended to the local event store**, and it is the store —
  not the queue — that feeds the event processors. The broker is transport; Axon works as it does in
  any application with no messaging, with tokens, replay and durability.

**ONE CHANNEL PER DESTINATION, and the outbound side no longer has a hub.** It used to be a single
channel, `axon-events`, through which every event passed — a central point in a saga that calls itself
choreographed, and the reason "part on Kafka, part on RabbitMQ" was not expressible: a connector is an
attribute of a channel, and there was only one channel.

#### The rule that decides where each thing is declared

> **The code says WHAT goes out. The configuration says WHERE TO.**

There was an intermediate version with the selector in `application.properties`
(`axonposts.messaging.outbox.<channel>.events=posts.*`), written to mirror the inbound `routing-keys`.
The symmetry was apparent: on the **inbound** side the selector really is configuration, because it
travels with queue names and bindings, which change per environment; on the **outbound** side it never
changes per environment — what a service publishes is its contract, and a contract in `.properties`
can be altered without going through code review.

And there was a version with an `interface AxonOutbox` of three methods, implemented by a bean in each
service. It stated the same two facts in one class, with the channel name written twice and nothing
checking. The qualifier says the same thing in two lines, and the check now exists.

#### The outbound side, piece by piece

| piece | where | what it decides |
|---|---|---|
| `EventAddress` | lib | reads the event ONCE: qualified name, namespace, id and ordering key |
| `OutboxRouting` | lib | which outbox receives which event, by namespace; validates the wiring |
| `@AxonOutbox` | **a lib qualifier, used in the application** | the channel and the **namespaces** that go out through it |
| `ChannelAddressing` | lib, one per connector | how that broker addresses (routing key, record key) |

**A NEW OUTBOX = TWO THINGS:**

1. an `Emitter` producer in the application's `infrastructure/outbox/` — one declaration, both facts:

```java
static final String CHANNEL = "post-events-out";

@Produces @Singleton
@AxonOutbox(channel = CHANNEL, namespaces = "posts")
Emitter<AxonEventEnvelope> postEvents(@Channel(CHANNEL) Emitter<AxonEventEnvelope> channel) {
    return channel;
}
```

2. the `mp.messaging.outgoing.<channel>.*` block: connector, exchange/topic. Nothing about *what* goes
   out.

**Three CDI facts that decided this shape, and all three were measured:**

- **`@AxonOutbox` cannot go on the injected field**, next to `@Channel`. A qualifier at an injection
  point requires a bean with *all* the qualifiers there, and the `@Channel` emitter is a Quarkus
  synthetic bean carrying only `@Channel`. The lib cannot offer that bean either: a producer matching
  any channel would need `@Channel` with a `@Nonbinding` `value()`, and it is **binding** — it is what
  distinguishes one channel from another. In a producer the collision disappears.
- **The channel name appears twice** because there is nowhere to read it once: ArC returns
  `Bean#getInjectionPoints()` **empty** for producers (measured: `injectionPoints=[]`), so the
  parameter's `@Channel` is invisible at runtime. What prevents divergence is `OutboxRouting`, which
  checks the produced emitter against what `ChannelRegistry` holds under that name.
- **The lib collects with `@AxonOutbox Instance<Object>`**, not `Instance<Emitter<…>>`: Quarkus
  validates every injection point whose required type is `Emitter` and demands `@Channel` on it —
  `Invalid emitter injection - @Channel is required for parameter 'outboxes'`. `Object` escapes that
  validation; the cast is checked during collection.

A producer declaring a channel SmallRye did not wire **brings down table resolution**, with the channel
name in the error. The inverse — a channel block with no producer — is not detectable, because not
every outgoing channel needs to be an Axon outbox; its signal is SmallRye's `has no downstream` at
startup.

An event matching several outboxes goes out on all of them — that is what keeps "everything on an
audit bus and only the posts on the broker" expressible with two beans. An event matching none does
not go out, and that is the design: the event store remains the durable log, and what was not published
can be republished.

**The known limit:** granularity is the namespace, so you cannot send `posts.PostCreated` to one
destination and `posts.PostUpdated` to another. The day that is needed, the place to solve it is the
`AxonOutbox` port — one more method — not a properties file.

**A new protocol = one more `ChannelAddressing`**, declaring the `connector()` it serves
(`smallrye-kafka`, `smallrye-pulsar`…). It does **not** replace the RabbitMQ one: the two coexist, and
what chooses between them is that channel's `mp.messaging.outgoing.<channel>.connector`. A connector
with no `ChannelAddressing` brings down resolution — without addressing the message would go out with
no routing key and the exchange would discard it without a line in the log.

**The ordering key is NO LONGER configured.** There used to be
`axonposts.messaging.ordering-tag-keys=postId,…` in both services, and both `application.properties`
already admitted in writing that the list broke no ties: `AggregateBasedJpaEventStorageEngine` accepts
**one tag per event**. Today the key is the event's tag, read from the event (`EventAddress`), and two
tags produce a `WARN` instead of a silent alphabetical choice. What locks down the three rules is
`OutboxRoutingTest`.

Three independent guards against duplicate execution, and each covers what the others do not: the
**origin mark** in the metadata discards the service's own echo (and cuts the resend loop); the
**inbox** (`axon_message_inbox`) discards a redelivery, in the same commit as the append; and the
**aggregate** discards the repeated decision (`Post.isComplete()`), which is the only one that
survives a cleared inbox.

### Layer rules (follow these when adding code)

1. **One file per message, and the class takes its name.** There is no `…Handler` class for
   command/query/subscription: `CreatePostCommand` **is** the command — it carries the message record
   nested inside (`CreatePostCommand.CreatePost`) and the `@CommandHandler` that handles it. The
   dispatcher imports the nested type. The same holds for `FindPostQuery.FindPost` and
   `OnPostUpdatedSubscription.OnPostUpdated`.
2. **Events are the inverse: one class per reaction.** Domain events live in `domain.*.event` and what
   raises them are the entities, through the `DomainEventPublisher` port. Whoever reacts lives in
   `application.<aggregate>.event`, one file per responsibility, and the class takes the event's name:
   `PostCreatedEventHandler`, `PostUpdatedEventHandler`. It is the same shape as the Spring version —
   the `QueryUpdateEmitter` injected by parameter, one `emit` and nothing else.
3. **The command decides and saves; the event notifies and orchestrates.** Event handlers do not write
   to the database — one emits to the subscriptions, the other dispatches the commands that follow on.
   **One exception, narrow and declared**: an event arriving from ANOTHER service has no local command
   behind it, so whoever receives it materializes the projection. That is a projection's classic role
   in CQRS; what was unusual here was the command accumulating that role, which only worked while
   everything was local.
   **The exception has its own package** — `application.post.projection` — and not out of a taste for
   symmetry: the package is what chooses the processor, and projecting needs a delivery that notifying
   does not. See *A handler's package chooses its DELIVERY*, just below.
4. **An entry point is PRESENTATION, wherever it comes from.** `interfaces/graphql` for
   HTTP/WebSocket/SSE and `interfaces/messaging` for the queues. The criterion is not the transport,
   it is the DIRECTION: an outbound adapter (the outbox, the repositories, the identity provider) is
   infrastructure; what brings something from outside in is presentation. An `@Incoming` is an
   address, just as a `@GraphQLApi` is a path.
   A listener, therefore, does not reach a repository nor decide a rule: it hands the message to the
   ingestion mechanism and leaves, just as a resolver hands off to the command gateway.
5. **Presentation does not reach the application's own `domain` or `infrastructure`.** The
   `@GraphQLApi` classes talk to the command/query gateway and to the
   `application.auth.AuthenticatedUser` port (implemented by `infrastructure.security.CurrentUser`).
   No domain repository in a resolver.
6. **No package-by-role at the root.** There is no loose `dto/`, `mapper/` or `exceptions/` any more:
   each type lives in the layer that **owns** it, and the package says which one.

### A handler's package chooses its DELIVERY

An `@EventHandler` does not say which processor it runs in: what says it is the **package**, in a line
of `application.properties`. And the processor is not tuning — it decides **how many times** the
reaction happens, **where** it happens and **what** happens when it fails. Two reactions to the same
event can need opposite answers to those three questions, and when they do, they do not fit in the
same class.

That is the case for `PostCreated`:

|  | project (`application.post.projection`) | notify (`application.post.event`) |
|---|---|---|
| how many times | once | in **every** container |
| where | in the append's transaction | outside it |
| if nobody is listening | writes anyway | there is nothing to do |
| if it fails | aborts the append | logs and moves on |
| processor | subscribing | pooled streaming, in-memory token, HEAD |

The classes on both sides are ordinary `@EventHandler` methods — neither knows which processor it is
in, and neither has a line of event reading. **The configuration is the entire difference.**

And both columns hold up:

- **projecting has to be subscribing** because only there does the handler run in the transaction of
  whoever appended. That is where the guarantee the rest relies on without knowing comes from —
  *whoever sees the event in the store sees the row* — and that is why the notifying handler can read
  the database without racing the write. On a processor with an in-memory token, a container frozen
  between invocations (which on Lambda is the normal state) would take the materialization with it;
- **notifying has to be streaming** because an `emit` only reaches subscribers of its own process, and
  on Lambda whoever holds the SSE connection is never whoever serves the mutation: that invocation has
  not returned.

**Consequence when writing new code**: a handler that writes goes to `projection`; one that notifies,
to `event`. Getting the package wrong breaks neither compilation nor tests — it silently changes
delivery semantics.

### Where each thing lives (and why)

```
application/<aggregate>/view/    PostView, TagView, UserView…, PostPage, *ViewMapper
interfaces/graphql/api/          the @GraphQLApi classes — resolvers only, nothing else
interfaces/graphql/dto/          CreatePostInput, UpdatePostInput
interfaces/graphql/mapper/       PostInputMapper (input → command)
interfaces/graphql/relay/        Connection/Edge/PageInfo/Connections/Cursors + Post/TagConnection/Edge
interfaces/graphql/error/        GraphQlErrors, @TranslatesErrors, the 4 exceptions with @ErrorCode
interfaces/graphql/sse/          GraphQL over SSE: the route, the handler and the wire format
docker/federation/               supergraph.yaml, router.yaml and the example subgraph (SDL only)
```

The deciding rule: **what crosses the bus is application; what only exists in the schema is
presentation.**

- The `*View` types are the result of the queries — they cross the query bus, go into `PostPage`, are
  emitted by the subscriptions. Hence they are `application`, and the `*ViewMapper` classes (entity →
  view) with them. Presentation **consumes** them; the dependency points inwards.
- The `*Input` types never leave the edge: `PostInputMapper` turns them into a command before anything
  else. Hence they are `interfaces`, and their mapper too.
- **What remains as conscious debt**: the `*View` types carry MicroProfile GraphQL annotations
  (`@Name("Post")`, `@Id`, `@Description`) and, since federation, `@Key` — that is what gives the type
  its name in the schema and its role in the topology, and it is the application knowing about
  protocol. Removing it would mean duplicating every view (one for the application, one for the
  schema) and doubling the mappers; not worth it at this proof of concept's size. If it ever is, the
  boundary to move is `*ViewMapper`.
- `DataIntegrityTranslator` stays in `error/` despite knowing Hibernate: it is part of the
  **classification** mechanism, and the one calling it is `GraphQlErrors`. Moving it to
  `infrastructure` would create the project's only presentation → infrastructure dependency, which is
  exactly what rule 4 avoids.

### One class per entity

`Post`, `Tag` and `User` are each an `@Entity` (JPA) + `@EventSourcedEntity` (Axon) + domain behaviour
in the same class. There is no mirror infrastructure entity; the value objects are `@Embeddable`
records that validate in the canonical constructor. Consequences that matter when editing:

- The entity is **mutable** (JPA requires a no-arg constructor and non-final fields), but with no
  public setters: only events change state, and only decisions produce events.
- **`@EventSourcingHandler` has to be idempotent.** The same event arrives through two paths (the
  domain applies it when deciding; Axon applies it when appending). That is why every event field is an
  **absolute value**, including the resulting version — no `version.next()` inside `on(...)`. Locked
  down by `applyingTheSameEventTwiceLeavesTheSameState`.
- **Deciding ends by calling evolving**: `Post.create(...)` ends at the `@EntityCreator` and
  `update`/`assignTag` end at `on(event)`, the same paths as replay. Locked down by
  `theStateReturnedByUpdateIsTheSameAsSourcingTheRaisedEvent`.
- **Events carry primitives** — they are contract, they get stored. Conversion to value objects happens
  at the entity's boundaries.
- **The ids are scalars ON THE WIRE**: `PostId`, `TagId` and `UserId` carry `@JsonValue` +
  `@JsonCreator`. Without that a one-component record comes out as an object
  (`{"postId":{"value":"abc"}}`) and whoever consumes it on the other side has to model a wrapper that
  only exists in here. While the event store was in memory nothing was serialized and nobody noticed;
  in the first service that deserialized the payload, the saga died with
  `MismatchedInputException: Cannot deserialize value of type String from Object value`.
- The entity's id type is **not** in an annotation of its own, and it is **not declared by hand either**:
  it is the first argument of `EventSourcedEntityModule.autodetected(PostId.class, Post.class)`, and what
  provides it is `infrastructure/axon/EntityIdType`, in `libs/platform` — in the PLATFORM and not in an
  app, because both services need it. **A new entity declares nothing**; see *The id type is DERIVED, not
  declared* below for the rule and for what it refuses.

### Identity and authorization

- **No user is registered in the application.** `UserProvisioning` creates the profile *just-in-time*
  on the first request with a new token, or links the account to an existing user by e-mail (account
  linking).
- **`User` is a polymorphic aggregate**: `@EventSourcedEntity(concreteTypes = {Reader, Author})`, and
  the concrete type is a pure function of the history. Axon fixes the type at creation, so **promoting
  means closing one aggregate and opening another** (`UserSupersededEvent` + a new `UserRegisteredEvent`
  with `supersedes` + `LinkAccount` per credential). That is three units of work: the window between
  them is detectable and `UserProvisioning` repairs it on the next login
  (`resumeInterruptedPromotion`), with no saga and no job.
- **Authentication is not proactive; the method is what authorizes.** A GraphQL endpoint is just an
  HTTP path, and `quarkus.http.auth.proactive=false` is what lets `post`/`posts` be public in the same
  POST where `me`/`createPost` require a token. Every write mutation carries
  `@RolesAllowed(Role.AUTHOR_CLAIM)` — and a new mutation without it shows up because
  `AuthorizationE2ETest` is parameterized over the list of operations. **When adding a mutation, add it
  there.**
- **No role prefix.** Quarkus puts `realm_access.roles` into the `SecurityIdentity` as they are, so the
  `@RolesAllowed` literal is what the realm emits (`author`). The `Role.AUTHOR_CLAIM` constant exists
  because an annotation requires a compile-time literal — keeping it next to the enum is what makes a
  divergence visible.
- **Being an author authorizes writing, not writing on someone else's.** The "is this post's author"
  check is in the domain (`Post.assertWrittenBy`), not in the resolver: it depends on the aggregate's
  state, so it is an invariant.
- Domain exceptions become error codes in `interfaces/graphql/error/GraphQlErrors` — one `if` per
  family, walking the cause chain. The one calling it is `ErrorTranslationInterceptor`, a CDI
  interceptor bound by `@TranslatesErrors` **on the class** of each `@GraphQLApi` — SmallRye asks CDI
  for the resolver and gets the client proxy, so the interceptor chain applies there as on any bean.
  **A new resolver class = the annotation on the class**, and not a line per method. Without it the
  errors come out as "System Error".
  A consequence of having moved up from the method's body to around it: the synchronous path is now
  translated — a `@Valid` that blows up now comes out `BAD_REQUEST`, and no longer `ValidationError`
  with no `code`.
- **The interceptor's priority is `PLATFORM_BEFORE + 100`, and the number matters.** Quarkus's security
  interceptors are `@Priority(150)`; sitting at `APPLICATION` (2000) ran inside them and let refusals
  come out with `"message": null` (Quarkus exceptions have no message) and a lowercase `code`. Outside
  them, they become `UNAUTHORIZED`/`FORBIDDEN` with a message, and the log swaps the Mutiny stack +
  `CompositeException` + `CIRCULAR REFERENCE` for one line. `AuthorizationE2ETest` locks down both —
  the code **and** the message.
- That is why the `io.quarkus.security.*` exceptions **left** `show-runtime-exception-message`: they no
  longer reach the client. Only the project's four stay there.
- **Known limit**: a token with an invalid signature is refused by `HttpAuthenticator` before the
  resolver — `HTTP 401` with an empty body, no `errors`. A *missing* token becomes an ordinary GraphQL
  error.

### Schema and persistence

- **The database schema comes from Flyway** (`src/main/resources/db/migration`), and Hibernate runs
  with `schema-management.strategy=validate`: a new entity with no migration **does not start**. The
  tests run the same migrations.
- **DEV SERVICES EXECUTES THE LIST OF MIGRATIONS, and nothing is generated.**
  `quarkus.datasource.devservices.init-script-path` accepts `Optional<List<String>>` — checked in
  `DevServicesBuildTimeConfig`'s bytecode — so it receives `db/migration/V*.sql` directly, in order.

  **It used to receive a `db/init/schema.sql` concatenated by `maven-antrun-plugin` in
  `process-resources`, and that violated the R for REPEATABLE in FIRST**: the test result depended on
  a MAVEN PHASE having run. Anyone running the suite from the IDE — which copies resources but does
  not execute antrun — saw `ContainerLaunchException: Could not load classpath init script:
  db/init/schema.sql`, a message about a container for a defect that has nothing to do with
  containers. The plugin left both poms; the migrations ARE the schema, and the duplication goes with
  it.

  **A NEW MIGRATION = ONE MORE LINE in the property**, and the one enforcing it is
  `DevServicesSchemaTest`, in BOTH apps: it fails if a classpath migration is not on the list, if the
  order is not ascending or if a path does not resolve. It is deliberately not a `@QuarkusTest` —
  what it asserts is configuration and classpath, which exist without a running application, so it
  runs in milliseconds and **without Docker**. A guard that only runs with Docker up is a guard that
  does not run.

  **The order is by VERSION, not alphabetical**, and `DevServicesSchema.VERSION_ORDER` (in
  `libs/test-support`) implements Flyway's: numeric parts separated by `.` or `_`, compared part by
  part, the shorter first when it is a prefix. The first version of this did `Integer.parseInt` on the
  whole chunk — it worked with this project's migrations, all single-part, and **would have exploded
  with `NumberFormatException` on the first one following the convention Flyway itself recommends**
  (`V1_0_1__description.sql`). The defect was SLEEPING: no test would catch it until somebody wrote
  that migration. `DevServicesSchemaVersionTest` locks down the cases nobody writes today — `V10`
  after `V2`, `V1_10` after `V1_2`, `V1_0` before `V1_0_1`.

  **Renaming an ALREADY-APPLIED migration is not an option**: the version is what gets stored in
  `flyway_schema_history`, so `V1__` → `V1_0_1__` becomes a new migration in Flyway's eyes. The
  composite convention holds for the NEXT ones, and the code already supports it.

  **AND THE CHECKSUM COVERS THE WHOLE FILE, COMMENTS INCLUDED.** Measured: stripping the comments out
  of the six applied migrations left every already-migrated database refusing to start, with
  `Migration checksum mismatch for migration version 1 … 6`. The compose stack's `flyway-posts`
  service runs `migrate`, and `migrate` validates first — so it aborted, and took the whole
  `apps/web-e2e` run down with it **before a single service came up**. The message names the
  migration, not the reason, and nothing points at the edit that caused it.

  CI never sees this (an ephemeral runner starts from an empty database), which is exactly what makes
  it a local-and-production trap: what sees it is every environment where those migrations had
  already run — this machine's `postgres-data` volume, and the deployed stage, whose
  `PostsMigrate`/`TaggingMigrate` functions fail the next deploy the same way.

  **`apps/web-e2e` no longer sees it either, and by construction**: its stack now DROPS AND
  RECREATES both databases before migrating, so there is no history to disagree with. See *The stack
  owns its databases* in that app's section. What remains exposed is the `docker compose up -d` +
  packaged JAR workflow, whose database nothing recreates, and the deployed stage.

  **For those, the recovery is `repair`, and it is not `down -v`**: it rewrites the stored checksums
  to match the files and keeps the data. There are TWO databases, so it runs twice:

  ```bash
  docker compose run --rm flyway-posts \
    -url=jdbc:postgresql://postgres:5432/axonposts -user=axonposts -password=axonposts \
    -locations=filesystem:/flyway/sql -connectRetries=20 repair
  docker compose run --rm flyway-tagging \
    -url=jdbc:postgresql://postgres:5432/axonposts_tagging -user=axonposts -password=axonposts \
    -locations=filesystem:/flyway/sql -connectRetries=20 repair
  ```

  `repair` stays a one-off recovery and does **NOT** go into the compose `command:`. There it would
  silently accept any change to an applied migration — and that acceptance is the guard itself.

- **`migrate-at-start` is `false`, and that is not a preference.** `AxonExtension.init` is a
  RUNTIME_INIT recorder that resolves the event storage engine and touches the EntityManager —
  building the persistence unit BEFORE Flyway gets its turn. With `validate` against an empty database
  the application dies with `missing table [accounts]`, and deferring inside the `ComponentBuilder`
  does not help (the lambda is called from inside init itself). Who creates the schema: in dev/test,
  Dev Services executes the LIST of migrations in `initdb` (see the item above); on compose and in
  production, the `flyway-*` services. That is what production would do anyway, and the `validate`
  gate still holds because the script is generated from the migrations themselves.
- `baseline-on-migrate` is `false` on purpose: a non-empty database with no history is a database
  somebody created out of band.
- Indexes that no JPA annotation expresses live only in the SQL — the main one is
  `uk_users_email_active`: a unique e-mail **among the active ones**, because a closed reader and the
  author who superseded them coexist with the same e-mail.
- Logical deletion via `@SQLDelete` + `@SQLRestriction`. A side effect with a test of its own: deleting
  the account **hides the author's posts**, because `Post.author` is `@ManyToOne(optional = false)`
  against a filtered row.
- **One file per aggregate in persistence**, in `infrastructure/persistence/<aggregate>/`. There used
  to be two (adapter + Panache repository) because `PanacheRepositoryBase.findById(Id)` returns the
  entity and the domain port returns `Optional` — same signature, incompatible returns. Taking the
  `EntityManager` through the constructor removes the conflict and leaves one file. The package is
  scoped per aggregate (`.../post`, `.../user`) because a shared `.../panache` would be a **split
  package** between the two modules.
- **`merge`, never `persist`.** The entity comes reconstituted from the events by Axon: it is always
  *detached*, whether the row exists or not.

### GraphQL

- **Code-first schema.** There is no `.graphqls`; the SDL is generated and served at
  `/graphql/schema.graphql`. The root `schema.graphql` is a committed copy of it (nothing reads it at
  runtime); update it with `curl -s http://localhost:8080/graphql/schema.graphql > schema.graphql` when
  touching the contract. It carries `@link`/`@key`/`@shareable` because `schema-include-directives` and
  `schema-include-schema-definition` are on — those two lines exist so that file **composes** without a
  running application, and removing either makes `rover` read the subgraph as Federation 1.
  `FederationSchemaTest` locks that down. That is also why the DTOs carry `@Name`/`@Input`: `PostView`
  → `Post`, `CreatePostInput` → `CreatePostInput` (without the annotation it would become
  `CreatePostInputInput`).
- **`UserView`'s accessors carry `@Name`, and that is not redundancy.** SmallRye's `InterfaceCreator`
  only counts as an interface field a method that looks like a getter (`getX()`) *or* that carries
  `@Name`. These accessors are record-style. Without the annotation the interface comes out with zero
  fields, and an interface with no fields is **silently discarded** — the error shows up at startup as
  `type User not found in schema`.
- **Cursor connections in `interfaces/graphql/relay`**, written once: generic
  `Connection<N, E extends Edge<N>>` and `Edge<N>`, with a **one-line** concrete subclass per paginated
  type (`PostEdge extends Edge<PostView>`). The subclass is what gives the schema the Relay convention
  name — an instantiated generic would become `Edge_Post`. A new `…Connection` field = two lines, and
  `RelaySchemaTest` checks the result in the SDL.
- **Fields with arguments use `@Source List<T>`**, including paginated ones: SmallRye's
  `BatchDataFetcher` passes the field's arguments in the batch context. That is what let `Post.tags` and
  `Author.posts` be two methods instead of the Spring project's two `BatchLoaderRegistry` classes.
- Batch-resolved collections are `LAZY` on purpose: with `EAGER` the N+1 would happen before the batch
  entered the picture.
- **Subscriptions**: the core `QueryGateway`'s `subscriptionQuery(...)` returns a Reactive Streams
  `Publisher`, adapted to `Multi` with `FlowAdapters.toFlowPublisher`. The subscription's
  `@QueryHandler` **has to exist** (it returns `Optional.empty()`). The topic filter is evaluated at
  `emit`: the subscription's payload carries the predicate itself.
- **The update reaches the subscriber after the commit, and Axon is what does that.**
  `SimpleQueryBus.emitUpdate` calls `runAfterCommitOrImmediately`: it buffers the updates in a
  `ProcessingContext` resource, registers **one** `runOnAfterCommit` and delivers the batch together;
  with no context, or with one already committed, it delivers immediately. An `@EventHandler` writes
  `emitter.emit(...)` and nothing else.

  **The condition for this to work is that Axon's unit of work OWNS the transaction.**
  `quarkus-axon-transaction` does *begin-or-join*: if a JTA transaction is already open, it joins — and
  then Axon's after-commit fires with the transaction still open, the subscriber reads the database on
  another thread inside it, and the transaction aborts:

  ```
  ARJUNA012125: TwoPhaseCoordinator.beforeCompletion - failed ... ConcurrentModificationException
  ARJUNA012108: CheckedAction::check - atomic action ... aborting with 2 threads active!
  This statement has been closed.
  ```

  That is why **`ChannelEventIngestion.ingest` does not carry `@Transactional`**: the inbox row and the
  append go inside the same `unitOfWorkFactory().create("axon-inbox")`, which opens the transaction and
  commits it. Atomicity is the same; what changes is who owns it. Measured in both directions with
  `pnpm test:e2e`: **11 out of 12** with the annotation, **12 out of 12** without it — and no Surefire
  test catches the difference, because in test the tagging is doubled in-process and ingestion does not
  run. What locks it down is `AxonWiringTest.theIngestionOwnsItsOwnTransaction`, which checks the
  annotation's absence.

  **There were two attempts to solve this from the outside, and both are on record because both looked
  right.** A hand-written JTA synchronization inside `PostCreatedEventHandler` — which put
  infrastructure in the application and died in the metrics interceptor (`isStarted()` still `true` at
  `AFTER_COMMIT` → `ProcessingContext is already in phase AFTER_COMMIT`, raised **before** the
  emission). And a `QueryBus` decorator in the platform, which worked and was 280 lines redoing, on the
  JTA axis, what the framework already did on its own. Both disappeared when the transaction boundary
  came to match the unit of work's. **It was not a missing wheel: it was our wheel spinning on the
  wrong axle.**
- **Two transports on the same `/graphql`, chosen by header.** `Upgrade: websocket` →
  `graphql-transport-ws`/`graphql-ws`, which comes from SmallRye. `Accept: text/event-stream` → GraphQL
  over SSE, which does **not**: SmallRye 2.18.5 and the Quarkus 3.39 extension have not one line of
  `event-stream`, and `interfaces/graphql/sse` is the endpoint written here (the `graphql-sse`
  *distinct connections* mode). The handler inherits from `SmallRyeGraphQLAbstractHandler` — the same
  class as Quarkus's HTTP and WebSocket handlers — and that is what makes request context,
  `SecurityIdentity`, `@RolesAllowed` and error translation work identically across all three
  endpoints. A conscious dependency on an extension's `runtime` package, which is not public API.
- **Quarkus's GraphiQL speaks WebSocket, always — it is not an SSE bug.** The webjar's `render.js`
  carries `Accept: application/json` in the default headers and a hard-coded
  `subscriptionUrl: getWsUrl()`, and `SmallRyeGraphQLProcessor`'s `updateUrl` only rewrites `const api`
  and `const logo`. Without `subscriptionUrl`, `createGraphiQLFetcher` **throws** on a subscription
  instead of falling back to SSE. Exercising the SSE endpoint means `curl -N` or
  `new EventSource('/graphql?query=subscription{...}')` in the console; what really locks it down is
  `SseSubscriptionE2ETest`. Do **not** treat "the UI uses ws" as a sign that SSE broke.
- **The SSE route's order is what breaks, and the number is not guessable.** Quarkus numbers the
  application's routes **sequentially** (WebSocket `-99`, schema `2`, execution `4`), not in the
  10,000s. A high `order` lands after the execution handler, which answers `406 Not Acceptable` to
  whoever asked for `text/event-stream` — the new route simply does not run. Hence
  `-SecurityHandlerPriorities.AUTHORIZATION + 2`. And since Vert.x Web **pauses** the request when
  routing and this route has no `BodyHandler` (on purpose: it hands back with `ctx.next()` whatever is
  not SSE, and the body would be read twice), a `request.resume()` is missing — without it the POST
  hangs. `SseSubscriptionE2ETest` locks down both, including that the usual JSON POST still answers
  `application/graphql-response+json`.
- **`.onOverflow().buffer(...)` after `publisher(...)` is mandatory, not tuning.** The
  `subscriptionQuery` `Publisher` **does not honour incremental demand**: with
  `request(Long.MAX_VALUE)` it delivers everything, with `request(1)` per item — which is what
  SmallRye's `SubscriptionSubscriber` does — it delivers the first and stops. The buffer separates the
  two demands (Mutiny requests unbounded from Axon and serves the subscriber from its own buffer). It
  fails silently: the handshake completes, the first event arrives, the connection stays open. **A new
  subscription = the operator along with it**, and
  `NewsletterSubscriptionE2ETest.theSameSubscriptionKeeps...` is what catches its absence — along with
  its sibling in `SseSubscriptionE2ETest`, because the SSE subscriber requests one item at a time for
  the same reason and falls into the same trap.
- **Validation at two heights**: Bean Validation on the `*Input` types is edge fail-fast
  (`BAD_REQUEST` before a command exists); the value objects still validate on their own, and that is
  the validation that counts. In a partial update use `@Pattern`, not `@NotBlank` — constraints are
  ignored when the value is `null`.
- **Blocking off the event loop**: `SimpleCommandBus`/`SimpleQueryBus` execute on the dispatching
  thread and inside it there is blocking JPA. Every resolver dispatch is
  `Uni.createFrom().completionStage(() -> gateway...).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())`,
  written in the resolver itself. **The `Supplier` is mandatory**: the overload taking a ready
  `CompletableFuture` executes the gateway on the event loop, and the offload becomes decorative. Do
  **not** call the gateway directly, without the `runSubscriptionOn`. The full explanation is in
  `interfaces/graphql`'s `package-info`.
- `version` on `PostView` is `int` and not `long`: the MicroProfile GraphQL specification maps `long`
  to the `BigInteger` scalar, and the field is `Int!`.
- **Depth ceiling**: `quarkus.smallrye-graphql.instrumentation-query-depth=20`. SmallRye's default is
  **10**, and 10 breaks GraphiQL's introspection (depth 15) and the schema's deepest Relay query (11).
  It fails in a misleading way — the application starts, the SDL is served, a shallow query answers,
  and only the UI opens blank. `SchemaIntrospectionTest` locks down both cases; lowering the number
  takes it down.

### Federation (Apollo Federation 2)

The application is a **subgraph**. SmallRye serves `_service { sdl }` and
`_entities(representations:)`; what belongs to us are the annotations on the views, the `*EntityApi`
classes and two lines of `application.properties` (`federation.enabled`,
`federation.batch-resolving-enabled`). `docker/federation/` holds `supergraph.yaml`, `router.yaml` and
a neighbouring subgraph written as SDL only.

Entities and keys: `Post`, `Tag`, `Author`, `Reader` and the **interface** `User`, all by `id`.
`PageInfo` carries `@Shareable` — it is the only type another subgraph also defines.

**A NEW ENTITY = FOUR THINGS, and missing any one breaks at runtime, not at compile time:**

1. `@Key(fields = @FieldSet("id"))` on the *view* (it is what becomes the schema `type`);
2. an `<X>EntityApi` in `interfaces/graphql/api/` with a batched `@Resolver`;
3. a `Find<X>sByIds` query in `application/<aggregate>/query/`, returning a **map** by id;
4. `findAllById` on the repository port + the method on the `infrastructure/persistence/` adapter + the
   in-memory double in `support/`.

The `@Resolver`'s four traps, all silent:

- **the argument has to be called `id`** (or whatever is in the `@Key`). The match is by *return type +
  set of argument names*, not by method name. `postId` compiles and `_entities` ends up with no
  resolver;
- **the batched argument does NOT carry `@Id`.** `ReferenceCreator` tests for `@Id` before unwrapping
  the collection: with it, the expected type becomes `ID` of `java.util.List` and each id is read as
  JSON. What reaches the client is `NullPointerException: resultList is null`, with no mention of an
  argument;
- **the list element does NOT carry `@NonNull`.** With `[Post!]` the return-type match fails — the
  `FederationDataFetcher` expects a *named* type after unwrapping the list — and the batch stops being
  used without a log line;
- **one position per representation, in the order received**, with `null` where it does not exist. That
  is why the resolver projects the id list over the query's map, instead of returning what came out of
  the database.

A polymorphic type needs **one `@Resolver` per concrete type plus one for the interface**
(`UserEntityApi` has three): `List<UserView>` and `List<AuthorView>` are the same erasure in Java and
three different GraphQL types, and it is the GraphQL type the match uses. Asking for the wrong type
answers `null`, never the other type.

The `@Link` lives alone in `FederatedSchemaApi`, and **there can be only one** — repeating the
Federation `@link` in another `@GraphQLApi` class brings the application down at startup. A directive
used and not imported comes out prefixed (`@federation__key`): when using a new one, add the `@Import`
along with it. The version is a literal on purpose; do not swap it for
`Link.FEDERATION_SPEC_LATEST_URL`.

**Two RED errors in the editor are false positives, and they are not fixable in code.** The Quarkus
Tools plugin (Red Hat) reports, via LSP4IJ:

```
Directive 'io.smallrye.graphql.api.federation.Key' is not allowed on element type 'INTERFACE'   UserView
Directive 'io.smallrye.graphql.api.federation.link.Link' is not allowed on element type 'SCHEMA' FederatedSchemaApi
```

Both annotations declare exactly those positions (`@Directive(on = {OBJECT, INTERFACE})` and
`on = {SCHEMA}`), and the generated SDL proves they work. The bug is in
`MicroProfileGraphQLASTValidator`, which reads the `on` values like this:

```java
name = init.getText().substring(init.getText().indexOf(".") + 1);   // indexOf, not lastIndexOf
```

Read from a library `.class`, the text comes qualified
(`io.smallrye.graphql.api.DirectiveLocation.INTERFACE`); the `indexOf(".")` stops at the dot in `io.`
and what is left is `smallrye.graphql.api.DirectiveLocation.INTERFACE`, which matches nothing. It only
shows up on those two because the validator **does not check `OBJECT`** — which is why `@Key` on the
records (`PostView`, `TagView`…) passes quietly.

There is no fix in code: those two positions are the only ones SmallRye accepts, and
`@SuppressWarnings` does not catch it (it is a language-server diagnostic, not an inspection — not
even `"ALL"` silences it). Whoever is bothered turns it off in **Settings → Languages & Frameworks →
MicroProfile → Validation** (stored in `.idea/microProfileSettings.xml`, which is not committed). **Do
not remove the `@Key` from the interface or move the `@Link` to quiet the IDE** — that would trade a
real capability for a wrong warning.

`@Blocking`/`@NonBlocking`/`@RunOnVirtualThread` **cannot** be combined with `@Resolver`. Not a problem
here: the offload is written in the method body (`runSubscriptionOn`), as in every resolver.

**`_entities` is public and resolves any entity by key, with no token** — that is Federation's premise
(the subgraph stays internal, the router is the boundary). A concrete consequence: `Reader`, which was
not reachable anonymously, now is. Do not publish this application straight to the internet.

### Infrastructure configuration

**What configures Axon is the Quarkus extension** `at.meks.quarkiverse.axonframework-extension`
(`quarkus-axon` + `quarkus-axon-transaction`, **whose versions come from `quarkus-axon-bom`**, imported
in the root pom — `${quarkus-axon.version}` is written once, there, and no module repeats it. It used to
be a `<version>` on each of the nine declarations plus the property declared a SECOND time in
`apps/posts-api/pom.xml`, which is the shape where bumping the root moves everything except that one
app). It
discovers entities and command/query/event handlers at **build time** and publishes gateways and buses
as beans. The hand-written `AxonProducer`, `AxonHandlerLookup` and `JtaTransactionManager` **no longer
exist** — 539 lines became 78. The README has the assessment of that trade, including what got worse.

What remains in `infrastructure/axon` is what the extension cannot guess, one file per decision:

- **`EventSourcedEntities`** — each entity's id type, implementing `EventSourcedEntityConfigurer`. It
  delegates the answer to `EntityIdType`; see *The id type is DERIVED, not declared*, just below. **A new
  entity = nothing.**
- **`ApplicationClock`** — the `Clock` as a bean, so it can be fixed in tests.

#### The id type is DERIVED, not declared

**What the extension resolves on its own is `String`**, and that is the whole problem:
`EventSourcedEntityBeanBuildItem.determineIdClass` reads `@IdType` off the entity and, in its absence,
returns `String.class` — checked in the bytecode. The id type is what registers the `Repository<ID, E>`,
and `SimpleStateManager.loadManagedEntity` picks the repository with
`repository.idType().isAssignableFrom(id.getClass())`. With `String` registered and a `PostId` arriving
from `@TargetEntityId`, **nothing matches and `@InjectEntity Post` never loads** — with no compilation
error and no line in the log.

`@IdType(PostId.class)` on the entity stays **deliberately rejected**: it would be the domain's first
dependency on a platform library. What replaced it is not a map either — the map was the same fact stated
twice, and forgetting an entry was the silent `String` all over again.

**`EntityIdType` derives it from the annotations that are already there**, in this order:

1. a parameter annotated `@InjectEntityId` on an `@EntityCreator` — Axon's own way of saying "this is the
   id";
2. otherwise, the `@EventTag` member whose key matches the entity's `tagKey`, read off the events the
   entity is sourced from (the parameter types of its `@EntityCreator` and `@EventSourcingHandler`
   members, plus those of its `concreteTypes`). **Its declared type is the id type.**

Rule (2) is the framework's own contract, and the reference states it: *"every emitted event must carry an
`@EventTag` annotation whose key matches that `tagKey`"*, with the key derived from the member name when
`key()` is empty. `EntityIdType` mirrors Axon's derivation exactly, including
`tagKey().isEmpty() ? entityType.getSimpleName() : tagKey()` and the getter convention
(`get` + uppercase, stripped and decapitalized) — both read from
`AnnotationBasedEventCriteriaResolver` and `AnnotationBasedTagResolver`.

**Two events that tag the same key with different Java types make startup FAIL**, naming both. That is not
pedantry: Axon registers one repository per id type, so there is no answer, and the alternative is picking
one silently. **An entity nothing tags falls back to whatever the extension resolved**, which is the escape
hatch for a `@EventCriteriaBuilder` entity and for `@IdType` when somebody really wants it.

Locked down by `EntityIdTypeTest` in `libs/platform` — nine cases, no Quarkus, 0.07s — and by
`AxonWiringTest.everyEntityIsRegisteredUnderItsOwnIdType`, which is the one that proves it in a running
application. **Proven in both directions**: with `EntityIdType.of` taken out of
`EventSourcedEntities.createConfigurer`, that test fails on `repository(Post.class, PostId.class)`.

It works in native because the classes it reflects over are already registered with fields and methods:
`AxonNativeImageProcessor` registers every class carrying `@EventSourcedEntity` and every class declaring
an `@EventTag`, `@EntityCreator` or `@EventSourcingHandler` member — which is exactly the set this rule
walks.

Two lines of `application.properties` are worth as much as code, and both fail silently:

1. `quarkus.axon.subscribingprocessor.namespaces` and
   `quarkus.axon.pooledprocessor.<name>.namespaces` — which packages run in which processor. The value
   is the package name because the extension reads `@Namespace` from the *class* and falls back to the
   package (which is why the `package-info.java` `@Namespace` was removed: there it no longer had any
   effect).
   **A new handler in an already-listed package: nothing to do. A handler in a NEW PACKAGE: one more
   entry in one of the two.** Whoever is left out gets no error — it goes to an **anonymous**
   `PooledStreamingEventProcessor`, asynchronous and with a JPA token store, and the two properties
   that package needed silently stop applying. What catches it is
   `AxonWiringTest.everyPackageWithAnEventHandlerIsAssignedToAProcessor`.
   **The order is handler first, property second**: a namespace listed with no handler at all makes
   the application fail to start, with a `NullPointerException` at startup (`getEventhandlers` does
   `map(map::get).flatMap(...)` over a `null`).
2. No event store line — the POM is what chooses. With `quarkus-axon-jpa-eventstore` and
   `quarkus-axon-tokenstore-jpa` on the classpath the extension swaps the in-memory defaults for the
   JPA ones, and the tables come from V2.
   **The two modules each declare a `QuarkusAxonEntityManagerProvider` class**, both
   `@ApplicationScoped` and neither `@DefaultBean` — having both without excluding one brings startup
   down with `AmbiguousResolutionException`. Hence the `quarkus.arc.exclude-types` line in
   `posts-api`; `apps/tagging` does not need it because it has no token store (it has no streaming
   processor).

**The substitutions work by absence**, and that is what `AxonWiringTest` locks down: the extension
declares `@DefaultBean`s and steps aside for whatever exists. Deleting `EventSourcedEntities` or
removing `quarkus-axon-transaction` from the pom breaks no compilation — it brings back `String` as the
id and `NoTransactionManager`, which does not commit the event together with the row.

Inherited from the extension, with visible effects:

- a domain exception reaches the resolver inside a `CommandExecutionException`
  (`quarkus.axon.exception-handling.wrap-on-command-handler`, `true` by default). Nothing broke because
  `GraphQlErrors` and `PostCommandFixtures.hasCause` walk the **chain**;
- `/q/health` carries `Axon eventprocessors` (hence `quarkus-smallrye-health` in the pom);
- `quarkus.axon.update-check.disabled=true` switches off AxonIQ's network call at startup.

#### What the extension offers and this project does NOT use

Surveyed against `2.0.0-alpha6`, which is the LATEST published (`<release>` in Maven Central's metadata).
Every property below was checked against the `@ConfigMapping` bytecode, because the published docs are
not reliable — see the first row.

| capability | verdict |
|---|---|
| `quarkus.axon.discovery.<kind>.{enabled,included-packages}` | BUILD_TIME, per component kind. The docs say to disable it *"when you reference a module containing annotated classes but don't want them registered"* — which is `apps/tagging` importing `libs/posts`. Not used because the isolation here is **structural**: the libs contain no handler at all. ⚠️ **The published doc prints `event-sourced-entity`; the method is `eventSourcedEntities()` with no `@WithName`, so the real key is `event-sourced-entities`** — the singular is a key Quarkus reports as unrecognized and that does nothing |
| Event upcasting | the extension's `DefaultAxonFrameworkConfigurer.registerEventUpcasters` is an **EMPTY method** in alpha6 (`Code: 0: return`). There is a doc page for it and no implementation behind it. It is still the capability this project will need FIRST, since the events are a contract between two services and are stored: the day a field changes, upcasting is the mechanism, and it will have to be wired against Axon directly |
| `pooledprocessor.<name>.initial-segments` | default 16, and the Lambda log says `Initializing (16) segments`. On `post-subscriptions` — fan-out with an in-memory token, where every container reads everything — that is 16 coordinator claims doing the work of one. Worth measuring at `1`; not changed here because the measurement belongs on the stack |
| Snapshots (`@Snapshotting`, `SnapshotStoreConfigurer`) | not used; the `Post` and `User` streams are short |
| `serialization.blackbird.enabled` | default `false` |
| `exception-handling.log-{event,query,command}-handling-errors` | all default `true`; nothing to do |
| `command-gateway.retry.scheduling`, `CommandBusConfigurer` | not used |
| Health checks, `Configuration` injection, CDI beans as handler parameters | **already used** |
| Sagas (`sagastore-*`), `jdbc-eventstore`, processors as separate modules | **do not exist on the 2.x line** — published only under `0.1.x`, which is Axon 4 |

**Live reload is the weak point.** It has been observed, once and without reproduction afterwards, that
the projection silently stopped after a reload: a post is born at version 1, with no tag, and the log
says nothing. If it happens, restart `quarkus:dev`. The knob the extension documents for the
neighbouring symptom ("no command handler available") is
`quarkus.axon.live-reload.shutdown.wait-duration.amount`; it is **not** used here because it was not
proven to fix this case.

### Observability: OpenTelemetry in EVERY application

**RULE: a new application is born instrumented.** `quarkus-opentelemetry` plus
`quarkus-observability-devservices-lgtm` in `provided`, and the same `quarkus.otel` lines both apps
already have. A signal that exists in one service and not in the other gives you half a trace, and half
a trace is worse than none: **the gap looks like latency**. That is exactly what happened while only
`posts-api` was exporting — the publish showed up and then came a silence of unknown duration, which
was `tagging` deciding the tag with nothing recorded.

What the instrumentation costs in code: **nothing**. HTTP, JDBC and messaging-connector traces are
automatic, and the link between the processes comes for free because `tracing.enabled` is already
`true` by default on both sides of the RabbitMQ connector. The whole saga is **one trace**, measured:

```
quarkus-axon-graphql-posts   POST /graphql                                SERVER     322 ms
quarkus-axon-graphql-posts     GraphQL                                    INTERNAL   309 ms
quarkus-axon-graphql-posts     axonposts.events publish                   PRODUCER   (×3)
axonposts-tagging              axonposts.tagging.post-precreated receive  CONSUMER     2 ms
axonposts-tagging                axonposts.events publish                 PRODUCER
quarkus-axon-graphql-posts     axonposts.posts-api.post-completed receive CONSUMER
```

Four things that stand on their own, and the first three fail silently:

1. **`quarkus.application.name` is Grafana's `service.name`.** Without it every trace arrives as
   `unknown_service` — and with two services in the same trace that erases precisely the information a
   distributed trace has to give: which side the time was spent on.
2. **Logs and metrics are `false` by default in Quarkus.** Only traces come on, so
   `quarkus.otel.logs.enabled` and `quarkus.otel.metrics.enabled` are what make the signal EXIST. They
   are not tuning.
3. **The LGTM container is SHARED** (`quarkus.observability.lgtm.shared` is `true` by default,
   `service-name` is `lgtm`): one stack, one Grafana, both `service.name` values inside. Whoever STARTS
   it is whoever comes up first — and under `pnpm dev` both start together. Hence
   `quarkus.observability.lgtm.grafana-port=3001` being declared in BOTH apps: **the duplication is
   necessary**, because without it the Grafana URL would depend on who won the race. Verified with both
   up: a single container, and the race does not produce a second one.
4. **`quarkus-opentelemetry` does NOT bring an HTTP port** — it depends on `quarkus-vertx`, not on
   `quarkus-vertx-http`. That is what allows instrumenting `tagging` without giving it an endpoint or
   making it fight over 8080 with the other app under `pnpm dev`.

In **test** observability is off in both (`%test.quarkus.observability.enabled=false` +
`%test.quarkus.otel.sdk.disabled=true`), and both reasons are measured: bringing up Loki+Grafana+Tempo+
Mimir per suite costs Docker memory this machine does not have to spare, and with the SDK on and no
collector every test pays for an export attempt and fills the log with connection failures.
`sdk.disabled` turns off the whole instrumentation, not just the exporter. The devservice's `provided`
scope does not remove it from the test classpath — which is why those lines, and not the absence of the
dependency, are what turn it off.

**The blind spot in SPANS, and the ONE thing that is missing.** What Axon does inside the command/event
bus produces no span, so the event store append, the `@EventSourcingHandler` and the domain decision stay
INSIDE the `receive` span, as an opaque block. **This section used to say Axon 5 has no tracing, and that
is WRONG** — it was true of 5.0 and the quoted release note ("not yet available in Axon Framework 5.0")
is what made it look permanent.

Measured in 5.3.1's bytecode, everything is already there and already wired:

- `org.axonframework.messaging.tracing` has `SpanFactory`, `Span`, `SpanScope`, `LoggingSpanFactory` and
  the attribute providers;
- `MessagingTracingConfigurationEnhancer` is registered by **ServiceLoader** (it is in
  `META-INF/services/…ConfigurationEnhancer`) and **decorates** `CommandBus`, `QueryBus`, `EventBus`,
  `EventSink` and `EventHandlingComponent` with the `Tracing*` versions;
- the extension's `DefaultAxonFrameworkConfigurer.configureTracing` calls an `AxonTracingConfigurer` bean
  when one is resolvable, and otherwise logs `Tracing configuration is not available` — which is the line
  in this project's startup log today.

**The only missing component is a `SpanFactory`**: `MessagingTracingConfigurationEnhancer` resolves it
with `getOptionalComponent(SpanFactory.class).orElse(null)`, and nobody registers one. Register one and
every bus above starts emitting spans, with no other change.

**And the ready-made implementation does NOT fit, which is the whole reason this is still open.**
`org.axonframework.extensions.tracing:axon-tracing-opentelemetry` does have a 5.x line, up to
**5.2.0-RC1** — but its `OpenTelemetrySpanFactory` implements the OLD interface
(`createHandlerSpan(Supplier<String>, Message, boolean, Message...)`), while 5.3.1 asks for
`createHandlerSpan(String, Message, ProcessingContext)` plus six others. Dropping that jar in gives an
`AbstractMethodError` at runtime, not a compile error. `axon-framework-bom` 5.3.1 does not manage it
either — it manages `axon-metrics-micrometer`, and no tracing artifact.

**Writing the `SpanFactory` by hand was considered and REFUSED.** `Span` has five abstract methods plus
defaults whose semantics tie a scope to the `ProcessingContext` lifecycle, and `SpanScope` four more:
200–300 lines of context and concurrency code, in the exact area where this repository has already paid
twice (the hand-written JTA synchronization and the 280-line `QueryBus` decorator, both deleted). It
would also be dead the day the extension ships 5.3.x.

**THE TRIGGER IS EXPLICIT: when `axon-tracing-opentelemetry` is published for 5.3.x**, add the jar to
`libs/platform` and one `AxonTracingConfigurer` bean next to `AxonMetrics` that registers
`OpenTelemetrySpanFactory` as the `SpanFactory` component. Check the interface first — compare
`SpanFactory`'s methods against the implementation's, which is what caught this.

**Do NOT add the extension's own `quarkus-axon-tracing` module.** Read from the jar: it has ONE class,
and `configureTracing` logs `configure OpenTelemetry tracing` and **returns without touching the
configurer**. It produces no span and makes the log say tracing is on. It is also pinned at
`2.0.0-alpha3` while the rest of the extension is at `alpha6`.

**That is why Axon's METRICS are not decoration** — they are the only signal of what happens in there,
and they come from `libs/platform`, in `infrastructure/axon/AxonMetrics`. It is in the PLATFORM for the
same reason as `EventSourcedEntities`: both services need it, and neither has anything of its own to
say. Measured, with both applications up:

```
post_projection_latency{processorName="post-projection", service_name="quarkus-axon-graphql-posts"}  58
tag_decision_latency   {processorName="tag-decision",    service_name="axonposts-tagging"}          391
```

That is each processor's LAG, and it is the question no trace answers: a trace tells you about a
request that has already gone through, and here what matters is what has not gone through yet. Along
with it come a counter, a timer with buckets, a percentile and capacity figures for `CommandBus`,
`QueryBus` and `EventStore`, with the processor name as a TAG (`use-dimensions`), which allows
comparing both sides on the same chart.

**Why hand-written, and NOT with `quarkus-axon-metrics`.** The extension exists, at our exact version
(`2.0.0-alpha6`), and does exactly the two lines of `AxonMetrics.configure`. But it drags in
`quarkus-micrometer`, which depends on **`quarkus-vertx-http`, and not in optional scope** — which
would give an HTTP port to anyone importing the platform, including `apps/tagging`, which has and wants
none (it would fight over 8080 with the other app under `pnpm dev`). What the extension really needs is
a `MeterRegistry`, and `OpenTelemetryMeterRegistry` is one over the `OpenTelemetry` bean that already
exists — in a JAR, not in an extension. Two JARs (`axon-metrics-micrometer`,
`opentelemetry-micrometer-1.5`) and one class, and the metric goes out over the **same OTLP** as the
trace and the log.

This is also what made `quarkus-micrometer-opentelemetry` unnecessary in `posts-api` — a **Preview**
extension in Quarkus 3.39 that did enter here and left when the configuration moved up to the platform.
Both services now use exactly the same mechanism.

`AxonMetrics` also **works by absence**: it substitutes the extension's `NoMetricsConfigurer`, which is
a `@DefaultBean`. Deleting it breaks no compilation — the metrics silently disappear from BOTH
services.

## The second service (`apps/tagging`)

A complete Axon microservice, **with not one line of HTTP**: it reacts to `posts.PostPreCreated`,
decides the first tag and publishes `posts.PostCreated`. It has its own event store (its own database),
its own queue and no aggregate — it works with the real `Post`, imported from `libs/posts`.

- **it has no aggregate of its own.** There used to be a `TagAssignment`, and it was an invention: the
  question that matters is "is this post already complete?", and `Post` answers it. An entity to store
  what another entity already knows is duplicated state, and duplicated state diverges;
- **it does not redeclare events.** Importing `libs/posts` is the point of the domain being a lib. A
  domain event written twice is the same rule in two places, and the first new field separates them
  silently;
- **it has no read model**, and that is why `quarkus.hibernate-orm.packages=org.axonframework`: the JPA
  entities come on the classpath with the domain, and without that restriction `validate` would demand
  `posts`, `tags`, `users` and `authors` in a service that uses none of them. It rehydrates the `Post`
  from the EVENT STORE and applies rules; none of that goes through JPA;
- **it does not decide what the default tag is.** The name and the identity belong to the domain
  (`Tag.DEFAULT_NAME` and `Tag.DEFAULT_ID`, the latter derived from the former by
  `UUID.nameUUIDFromBytes`). It is a pure function: the same id on every node, every restart and every
  service. It matters because **two** places assign the default tag — this service in production and
  the in-process double in the other app's suite — and both have to arrive at the same id; with the
  rule in the domain that is a consequence, not a coincidence maintained by hand.

## AWS Lambda (`infra/aws/`)

A second deployment target, **additive**: no file that existed before it was changed. The same two
applications, packaged by Maven profile, become **six functions**; RabbitMQ becomes an SNS FIFO topic
with three SQS FIFO queues (plus three DLQs) subscribing by filter policy; and the identity is a
**Cognito** user pool, with the realm's three users seeded.

**KEYCLOAK IS STILL EVERYTHING IN DEV AND TEST** — Dev Services brings up the container, imports
`docker/keycloak/realm-axon-posts.json` and the 155 tests use that realm. Cognito applies only on AWS,
and the swap happened because the application is only a resource server: Keycloak cost Fargate + ALB +
ECR (~US$ 25/month) to deliver what Cognito delivers in the free tier.

The price is **four lines** in `application-lambda.properties` (issuer, client id, audience and
`quarkus.oidc.roles.role-claim-path=cognito:groups`). The last one is the only divergence in
BEHAVIOUR, and it fails silently: without it every write mutation answers `FORBIDDEN`, and no test
catches it, because in test the issuer is a different one.

**And the bearer is the ID TOKEN, not the access token.** Cognito's access token does not carry
`email`, and `UserProvisioning` calls `Email.of(identity.email())`. Putting `email` in it requires the
V2_0 trigger, which AWS only offers on the Essentials/Plus plans; the Lite tier only has V1_0, which
customizes the ID token. What keeps this safe is `quarkus.oidc.token.audience` checking `aud`. See
`infra/aws/cognito.ts`. Practical consequence: the test token does NOT come from `/oauth2/token`
(Cognito does not accept `grant_type=password` there) — it comes from `aws cognito-idp initiate-auth`.

```bash
pnpm add -D sst                            # once
./infra/scripts/package.sh                 # the four zips in infra/dist/ (a shortcut to Nx)
npx sst deploy --stage dev                 # ...and the migrations run IN HERE
./infra/scripts/e2e.sh                     # the whole saga, asserted against the stack
npx sst remove --stage dev                 # NOT optional: ~US$ 0.13/hour

npx nx run "dev.manuelantunes:quarkus-axon-graphql-posts:lambda-http"      # behind the API Gateway
npx nx run "dev.manuelantunes:quarkus-axon-graphql-posts:lambda-sqs"       # driven by the return queue
npx nx run "dev.manuelantunes:quarkus-axon-graphql-posts:lambda-stream"    # the streaming Function URL
npx nx run "dev.manuelantunes:axonposts-tagging:lambda"                    # the SAME zip serves both
npx nx run "dev.manuelantunes:axonposts-tagging:lambda:jvm"                # no native binary
npx nx run-many -t lambda-http,lambda-sqs,lambda-stream,lambda -c native --parallel=1   # all four
```

**SIX functions from THREE zips**, and the last two are the queue ones with a different environment
variable: `quarkus.lambda.handler` is RUNTIME configuration, so `QUARKUS_LAMBDA_HANDLER=flyway-migrate`
turns the same artifact into the function that creates the schema. **`migrate-at-start` is still
`false`** — on Lambda that stops being a workaround and becomes the only correct choice: a migration at
startup of a function that scales to N environments would be N concurrent attempts, each cold start
waiting on another's Flyway lock inside one invocation's timeout. The order is: deploy, invoke the two
migrations, test.

**Four things SST 4.17.1 does differently than assumed**, all measured against the installed version:
(1) `sst.aws.Function` **does not support Java** — the runtimes are node, go, rust, python and
container, so the six functions are `aws.lambda.Function` from the raw Pulumi provider, which SST
exposes as the global `aws`; (2) the code goes through **S3**, because the zips are 59–72 MB and a
direct upload stops at 50 MB — and without `sourceCodeHash`, swapping the content under the same key
**does not update the function**, and the deploy publishes the old artifact saying it succeeded; (3)
`dlq` requires the `{ queue, retry }` pair, and a FIFO queue's DLQ is also FIFO; (4)
`rawMessageDelivery` is not a subscription option — it goes through `transform.subscription`. And in a
`Service`'s `image`, `dockerfile` is relative to the **context**; and a file path in the config
resolves from `.sst/platform/` and not from the root, so the `FileAsset` needs `$cli.paths.root`.

**`✓ Complete` from `sst deploy` does NOT mean it worked.** The deploy that tripped over that path
printed `✓ Complete` on screen — with the Keycloak URL — and failed to create the SIX functions, the
API Gateway and the three S3 objects. The error was only in `.sst/log/pulumi.log` (`3 errors`). A
resource that does not show up after a "successful" deploy: that file is what answers, and `npx sst
diff` confirms it, because it stops seeing what failed to register.

**AND ON AN EPHEMERAL RUNNER THAT FILE DIED WITH THE JOB.** This happened again, and the second time
cost double: `✓ Complete`, exit 1, and the job log without ONE line about the cause. On a machine the
file is right there to be read; in CI the only way out was to redeploy blind, 25 minutes per attempt,
against a stack that bills. Today `tools/github/deploy-sst` has an `if: failure()` step that dumps the
error lines into the job log and uploads all of `.sst/log/**` as an artifact. **A deploy that fails in
CI: that log group is the first thing to open.**

**All three profiles write to `target/function.zip`** — the second erases the first. That is why the
script copies to `dist/` between them, and why there is no way to package all three in one invocation.

**The protocol swap is not code.** `@AxonOutbox` still says `channel = "post-events-out"`,
`namespaces = "posts"` — because what a service publishes is its contract — and what swaps RabbitMQ for
SNS is one line of `application-lambda.properties`. That is exactly what the `ChannelAddressing` port
existed to buy.

**`quarkus.profile=lambda,prod` has to hold at BOTH moments**, and both entries matter: build time
(from the Maven profile) and runtime (`QUARKUS_PROFILE` on the function). With `lambda` alone, the
`%prod.` lines of `application.properties` silently stop applying and the function comes up pointing at
the development datasource. With build time only, the function ignores
`application-lambda.properties` and tries to talk to a RabbitMQ that does not exist.

**Why TWO new modules, and who decided:** the build. `quarkus-amazon-lambda-http` **brings** the
`quarkus-amazon-lambda` processor, which scans the index for a `RequestHandler` and refuses what it
finds (`Multiple handler classes`). It is not enough for the HTTP function not to use the handler — it
cannot be on its classpath. Hence `libs/axon-aws` (outbound addressing, in all three functions) and
`libs/axon-lambda` (the handler, only in the queue ones).

**The existing `@Incoming` methods are still the entry point.** The channel becomes
`smallrye-in-memory` and the Lambda handler pushes the record into it — so `PostPreCreatedListener`,
`PostChangesListener` and `PostCompletionListener` run without a line changed, with the
`@Blocking(ordered = false)` and the Axon unit of work already measured there. The in-memory connector
is therefore a **production dependency**, and it has a second role: in the API Gateway function it is
the null object for the `post-completed-in` channel, which is declared without a profile and cannot be
removed by a profile file, only overridden.

**FIFO is not tuning.** `MessageGroupId` is the aggregate's tag (the `EventAddress.orderingKey()`,
which broke no ties in the routing key). On a standard queue this saga does not work worse — it breaks,
with `duplicate key ... uk_aggregateevententry_aggregate`, intermittently and in proportion to load.

**The infrastructure is SST**, and the root `sst.config.ts` does not contain it: it does `app()` and an
`await import("./infra/aws")` inside `run()` — dynamic because the modules create resources at the top
of the file, and statically they would be evaluated before `app()` ran.

```
infra/dist/      the three zips (generated)
infra/scripts/   package.sh (a shortcut to the Nx targets), migrate.sh, discover.sh, e2e.sh
infra/aws/
  index.ts       the facade: load order and outputs. Creates nothing.
  support/       the DEFINITIONS: ArtifactStore, QuarkusFunction/QueueWorker/Migrator, HttpApi.
                 NOTHING here creates a resource on import.
  network/ data/ messaging/ identity/    the base infrastructure
  compute/       the six functions and the API Gateway; platform.ts is where support/ finds the resources
  edge/          the Router: ONE CloudFront distribution in front of the site and the subgraph
  web/           the Next.js client, behind the router
```

**The arrow always points the same way**: the definer does not know the instantiator.
`compute/platform.ts` is the only point where the two sides meet, and that is why it exists separately
— without it, `support/functions.ts` would have to import `network` and `role`, and a helper that
imports infrastructure stops being a helper.

**Everything with satellites is a `ComponentResource`**: `ArtifactStore` (bucket + one object per
artifact), `ExecutionRole` (role + policy), `QuarkusFunction` and the subclasses `QueueWorker`
(+ event source mapping) and `Migrator` (+ the invocation), and `HttpApi` (API + integration + route +
stage + permission). That gives a shared lifecycle, a URN of its own per piece and a deploy tree that
describes the system.

**THE MIGRATIONS RUN ON THEIR OWN AT DEPLOY.** `Migrator` creates the function and the
`aws.lambda.Invocation` that calls it, with `input: Date.now().toString()` so it runs every time
(Flyway is idempotent) and `if (!$dev)` because under `sst dev` there is no published artifact. A
migration that fails becomes a DEPLOY that fails.

**A file path in the config uses `$asset()`**, which resolves relative to the app root. `$cli.paths` is
`@internal` and a raw relative path breaks — see the trap just below.

**Three things that fail SILENTLY during provisioning**, and all three are in `infra/aws/`:
`RawMessageDelivery=true` on each subscription (without it the body is the SNS envelope and ingestion
dies with `UnrecognizedPropertyException: Type`); `ReportBatchItemFailures` on each event source mapping
(without it AWS ignores the `SQSBatchResponse` and the whole batch comes back); and
`AXONPOSTS_LAMBDA_SQS_CHANNEL` on each queue function.

**THE SUBSCRIPTION ON LAMBDA — the THREE layers, and all THREE are solved.** It matters to separate
them because each has its own cause and its own fix, and fixing one does not make the others disappear:

1. **The handler does not stream, and that is not configuration.** In 3.39.2's bytecode:
   `LambdaHttpHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>` — the
   return type IS the whole response. `NettyResponseHandler` accumulates each `HttpContent` into a
   `ByteArrayOutputStream` and only completes the `CompletableFuture` at the end. Zero occurrences of
   `vnd.awslambda`, `RESPONSE_STREAM` or `HttpResponseStream` in the JAR.

   **And `quarkus-amazon-lambda`'s `RequestStreamHandler` does not solve it either — but it comes
   close.** `AbstractLambdaPollLoop` (in `quarkus-amazon-lambda-common`) does, in the stream branch,
   `responseStream(url).getOutputStream()` and passes THAT stream to the handler: the bytes written go
   to the connection with the Runtime API, not to an intermediate buffer. What is missing is in
   `responseStream`, which has exactly five instructions — `openConnection`, `User-Agent`,
   `setDoOutput(true)`, `setRequestMethod("POST")`, `return`:
   <ul>
     <li>without `setChunkedStreamingMode`, the JDK's `HttpURLConnection` <b>buffers everything</b> to
         compute the `Content-Length`. Nothing goes out before `close()`;</li>
     <li>without `Content-Type: application/vnd.awslambda.http-integration-response`, AWS reads no
         prelude — there is no way to set status or headers.</li>
   </ul>
   The method is `protected`, but the one inheriting it is `AmazonLambdaRecorder$1`, anonymous inside
   the recorder: changing those two lines means forking the extension or shadowing the class on the
   classpath.
2. **The transport in front does not support streaming.** The API Gateway does not offer it in any
   mode: response streaming on AWS exists only on a Function URL with `InvokeMode: RESPONSE_STREAM`.

**THE FIRST TWO WERE SOLVED — and not with a fork.** The way out was to invert the problem:
`StreamingFunction` (`infra/aws/support/functions.ts`) packages the application with the
`-Plambda-stream` profile, which <b>adds no Lambda extension at all</b> — it is the HTTP server it
always was. What turns it into a function is the <b>AWS Lambda Web Adapter</b>, an official layer that
runs as an extension, waits for the health check and translates each invocation into a request to
`localhost`. With `AWS_LWA_INVOKE_MODE=response_stream` and a Function URL in `RESPONSE_STREAM`, what
Vert.x writes goes out as it writes it.

MEASURED against the stack: the `SseStream` keep-alive comments arrived at 21:48:58 and 21:49:13 —
<b>15 seconds apart, exactly the `axonposts.graphql.sse.keep-alive`</b>. The connection stays up and
the bytes arrive progressively. Through the API Gateway, no byte arrives in 30 s.

The only dependency the profile adds is `axonposts-axon-aws`, and it has nothing to do with Lambda:
these are the connectors `application-lambda.properties` names. Without it the build fails at BUILD
TIME with `The channel 'post-events-out' is configured with an unknown connector (smallrye-sns)`.

3. **And the source was IN-PROCESS — the one that survived the other two fixes.** Measured on the
   Function URL with streaming already working: subscribing to `onPostUpdated`, creating a post and
   editing it delivered no event — the mutation lands in another container, because what holds the
   connection is busy with an invocation that has not returned.

**THE THIRD WAS SOLVED BY CONFIGURATION, and the fix has not one line of event reading.** What notifies
the subscribers is still `PostCreatedEventHandler` and `PostUpdatedEventHandler` — the same two classes
as the Spring version, in `application.post.event`, with the `QueryUpdateEmitter` injected by parameter
and one `emit` in the body. They do not know which processor they run in. **What changed was their
package's processor**: `application.post.event` is declared as <b>pooled streaming</b> in
`application.properties`, with an <b>in-memory token store</b> and an initial position at <b>HEAD</b>.
The consequence is the whole solution:

- **streaming** — the processor reads the EVENT STORE, which is shared. It sees what any container
  appended. (`AggregateBasedJpaEventStorageEngine` supports this: it has `stream(StreamingCondition)`
  with `GapAwareTrackingToken`, gaps and batches — verified in the bytecode.)
- **in-memory token** — each container has its own cursor. With the JPA token store, one container
  would claim the segment and the others would see nothing: that is the opposite of a fan-out, where
  EVERY container needs to see EVERY event.
- **HEAD** — whoever comes up now wants what arrives from now on, not the history re-emitted.

Reading, keeping the cursor, batching and handling failure is Axon's job, with the
`EventStorageEngine` and the `TokenStore` the application already configures.

**What had to leave was the PROJECTION, not the emit.** Materializing the row for a `PostCreated` that
arrived from another service needs the opposite: once, in the append's transaction, aborting the append
if it fails. On a processor with an in-memory token, a container frozen between invocations — which on
Lambda is the normal state — would take the materialization with it. That is why it lives in
`application.post.projection`, which is subscribing. See *A handler's package chooses its DELIVERY*.
<p>
And the ordering, which used to be hand-written care (reconcile before emitting, in the same function),
now comes from the transaction: the streaming processor only sees the event **after** the commit that
wrote the row.

**And that required UNDOING a bean exclusion that was hiding a bigger defect.**
`PooledEventProcessingConfigurer` was in `quarkus.arc.exclude-types`, with the argument that a pooled
processor "is not what this project wants anywhere". The real effect was not switching off an accident,
it was switching off the CAPABILITY: while it was excluded, a namespace forgotten in
`subscribingprocessor.namespaces` did not become an asynchronous processor — it became <b>no processor
at all</b>, and its handlers never ran, with no warning anywhere. What protects against the original
accident now is `AxonWiringTest.everyPackageWithAnEventHandlerIsAssignedToAProcessor`, which requires
every package with an `@EventHandler` to be declared in the subscribing processor OR in a named pooled
one.

**MEASURED ON THE FUNCTION URL**, with the subscription open in one container and the mutation served
by another:

```
event: next
data: {"data":{"onPostUpdated":{"id":"e91dad31-…","title":"editado — deve chegar na subscription","version":2}}}
```

### THE NATIVE BINARY — and the four defects only it reveals

The JVM cold start was **14.8 s**, and with the Lambda Web Adapter it is billed as invocation time (the
boot happens INSIDE the handler, so there is no `Init Duration` in the REPORT). The native binary
solves that, and `libs/axon-native-support` exists exactly so it works.

**Measured on the stack, the same function, the same code:**

| | JVM | native |
|---|---|---|
| cold start (health 200) | 14.8 s | **2.8 s** |
| warm `createPost` | 0.70–0.89 s | **0.50–0.57 s** |
| opening a subscription | 26 s | **5 s** |
| memory used (REPORT) | 413 MB | **187 MB** |
| zip | 70 MB | 56 MB |

It wins on BOTH axes. The intermediate reading that "native is slower warm" was measuring a dying
process — `curl -w %{time_total}` measures the time to the response, and an error response also has a
time.

**HOW IT IS BUILT:** an Nx target, in the `native` configuration (the default) or `native-container`.

`native` compiles with the MACHINE's GraalVM; `native-container`, inside the Mandrel builder image. The
difference is not convenience: `native-image` produces an executable for the SYSTEM it runs on, and
Lambda needs ELF/Linux — so **from a Mac, only `native-container` produces a binary Lambda will
execute**. The local one is for running and debugging the native application here, and it serves
entirely on a Linux box with GraalVM installed. **The note that the container needs ~12 GiB in Docker is
out of date**: all four artifacts were built with Docker at **8 GiB**, with `native-image` seeing
8.23 GB and `-J-Xmx10g`. The `exit 137` in `[1/8] Initializing` is still the symptom of running out of
memory, and it mentions memory nowhere — but 8 GiB is enough today. The below-12-GiB warning left with
`build-env.sh`; the symptom is in the diagnostic table in the section *`build-env.sh` WAS DELETED*.

**And on a Mac with a broken Xcode the local build fails without naming Xcode.** Measured:
`xcode-select -p` points at `Xcode.app`, the `cc` that comes from there does not even load
(`dlopen(@rpath/libxcodebuildLoader.dylib): Symbol not found: _XPCTypeBool`, exit 72), and
`native-image` dies in ~20s with `Unable to detect supported DARWIN native software development
toolchain`. The Command Line Tools are an independent installation and work; today what puts them in
play is `xcode-select` (see the section *`build-env.sh` WAS DELETED*), with BOTH things the recipe
requires — the PATH (that is where `native-image` gets `cc` from) and the `-isysroot`, because
`native-image` does **not** pass `SDKROOT` or `DEVELOPER_DIR` through to the compiler. The switch only
matters when the system `cc` is broken.

**THE FOUR FAILURES, and what each one teaches.** None appears on the JVM; three of the four appear only
at RUNTIME:

1. **`org.LatencyUtils` missing** — `io.micrometer.core.instrument.AbstractTimer` references it and
   Micrometer declares it OPTIONAL. What normally brings it in is `quarkus-micrometer`, which this
   project deliberately does not use. It fails at BUILD, in `[2/8] Performing analysis` — the only one
   of the four the compiler catches. Fix: declare the dependency in `libs/platform`.
2. **`AnnotationBasedEntityIdResolver` not registered** — Axon's reflection has TWO levels, and
   `AxonNativeImageProcessor` only covered the first: it registered the `*Definition` classes and not
   what they INSTANTIATE. The application comes up far enough for health to answer 200 and **dies on
   every request** with `Runtime exited with error: exit status 1`, which Lambda reports with no cause.
   Fix: `AXON_DEFAULT_IMPLEMENTATIONS` in the same processor.
3. **The Relay base classes** — `PostConnection` is an EMPTY subclass of `Connection<N, E>`, and an
   inherited method does not enter the subclass's registration. The client receives `"System error"`
   with `path: ["posts","edges"]` and the server **does not log a line**. What gives it away is
   `pageInfo` and `edges` failing together while `__typename` and the SDL answer: the problem is the
   OBJECT, not a field. Fix: `@RegisterForReflection` on `Connection` and `Edge`.
4. **Configuration precedence changes between JVM and native.** `application-lambda.properties` says
   `quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL}` with no profile; `application.properties` says
   `%prod.quarkus.oidc.auth-server-url=...localhost:8081...`. On the JVM the first wins; in native, the
   second — and the function comes up pointing at a Keycloak that does not exist. Public answers,
   authenticated 500s. **It is not that the file stops loading**:
   `axonposts.graphql.sse.keep-alive=2s`, from the same file, applies (measured: keep-alives every
   2 s). It is the contest between a property WITH a profile and one WITHOUT. Fix:
   `QUARKUS_OIDC_AUTH_SERVER_URL` as an environment variable in `environment.ts` — ordinal 300 beats any
   file, in both packagings.

**ALL SIX FUNCTIONS ARE NATIVE.** The three packaged by the `quarkus-amazon-lambda*` extensions produce
a `function.zip` with a native `bootstrap` in place of the Java handler — hence
`runtime: provided.al2023`. The streaming one is the only `java25`: there what executes is `run.sh`
through the Web Adapter layer, and that hook belongs to the MANAGED runtime; the sandbox ships a JVM
that never runs.

**The saga closes in ~6 s** — it was ~21 s with `tagging` on the JVM and ~50 s with everything on the
JVM.

**TWO MORE FAILURES, and both only appeared when the SECOND service became a binary:**

5. **`apps/tagging` did not declare `axon-native-support`.** Only `posts-api` had it. The binary is
   produced, starts, and dies at STARTUP with `No suitable constructor found for entity of type
   [AnnotationBasedEventSourcedEntityFactoryDefinition]` — the first of the failures that extension's
   Javadoc describes. **A service that uses Axon and compiles native declares the extension**, and the
   pom now says so in writing.
6. **A MESSAGE's reflection does not stop at its own class.** `PostCreatedEvent` is `@Event` and was
   registered; the `AssignedTag` it carries inside a `List<AssignedTag>` is a NESTED record, with no
   annotation, and was left out. `tagging` consumed `PostPreCreated` (a FLAT record, which serializes
   fine), decided the tag and could not publish the `PostCreated`:

   ```
   ConversionException: Exception when trying to convert object of type
     'dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent' to 'byte[]'
   ```

   **What made this hard to find**: the saga stopped at version 1, Lambda's `Errors` counter stayed at
   ZERO (the exception is handled by the interceptor), the queues were EMPTY and SNS showed a positive
   `NumberOfMessagesPublished` with zero failures. Everything pointed at the wrong place. What answered
   was comparing the metrics: `TaggingDecide` with 7 invocations and 0 errors, `PostsApiInbox` with
   NONE — the broken link was in the middle.
   <p>
   The fix is a rule, not a list: `AxonNativeImageProcessor` now registers the **transitive closure** of
   the types composing each message, stopping at whatever the Jandex index does not know (`String`,
   `Instant` and the rest of the JDK do not need it).

### THE TELEMETRY: the OpenTelemetry collector as a layer, and Better Stack at the end

Every function carries the official collector layer (`opentelemetry-collector-arm64-0_23_0`), which
reads `infra/lambda/collector.yaml` from its own zip and re-exports to Better Stack. The application
keeps exporting OTLP to `localhost` — it does not know what the destination is, and that is what makes
switching backends an infrastructure change.

**Why a collector, and not the application's exporter talking directly.** A Lambda is FROZEN when the
handler returns. An in-process exporter loses whatever has not gone out yet — and what has not gone out
yet is the end of the request. The layer is an EXTENSION: it gets the end-of-invocation hook and
flushes before the freeze. It is the explanation for what `CLAUDE.md` already recorded — logs not
arriving while traces did.

**THE LAYER COVERS ONE OF THE TWO HOPS, AND THE DEFECT WAS IN THE OTHER.** The path is
`application --(1) OTLP to localhost--> collector (layer) --(2) HTTPS--> Better Stack`. The layer gets
the end-of-invocation hook and dumps what is **already inside it** — hop (2). What decides when hop (1)
happens is the `BatchSpanProcessor` INSIDE the process, on a thread that fires every 5 s (1 s for
logs), and Lambda is frozen before that.

Measured, with the native binaries: `TaggingDecide` 438 ms, `PostsApiInbox` 233 ms, `TaggingReplicate`
195 ms — the three queue functions with **zero spans and zero logs** in the backend, while the collector
came up, ran and flushed without a single error, from an empty buffer. Proven in both directions: after
a saga with no telemetry at all, invoking the function with an EMPTY batch made the previous
invocation's span appear, **with its original timestamp**. The data was not lost — it was frozen on this
side of hop (1).

**It only appeared with native**, and the reason is a good one: on the JVM the ~15 s cold start happened
INSIDE the handler and the batch fired in the middle of it. The telemetry arrived through an accidental
window that the slowness opened (`tagging` once had 166 logs). Native comes up in 0.6 s and the window
closed.

**The fix is `TelemetryFlush`, in `libs/axon-lambda`**: a `forceFlush` of the three providers at the end
of the SQS handler and of the migration one. After it, without waking anything: `axonposts-tagging` with
spans and 65 logs, and the saga in a single trace.

**THE OBVIOUS ALTERNATIVE WAS TRIED AND IT BREAKS THE SAGA.** The extension has `quarkus.otel.simple`
(`OTelBuildConfig#simple`), which swaps the batch processor for `SimpleSpanProcessorWithBatchShutdown` —
each span goes out immediately, and the batching becomes the collector's `batch`. It is the right
division of responsibility, it was deployed, and **the saga stopped at version 1**:

```
ARJUNA012094: Commit of action ... invoked while multiple threads active within it.
ARJUNA012107: CheckedAction::check - atomic action ... commiting with 2 threads active!
Caused by: java.sql.SQLException: Enlisted connection used without active transaction
```

Exporting on `onEnd` means exporting INSIDE the transaction, and the Quarkus exporter dispatches on a
Vert.x worker that joins the same transaction. It is the SAME family of failure the subscriptions
section already records for `@Transactional` on ingestion — there "aborting with 2 threads active", here
"commiting". It is also what makes the explicit flush the right choice and not the leftover one: it runs
**after** the commit, on the handler's thread, with no active transaction.

**What it does NOT solve: metrics.** They have no processor — they have a periodic reader
(`MetricsRuntimeConfig` only exposes `exportInterval()`); the `SdkMeterProvider`'s `forceFlush` goes
along, but whatever is outside the collection interval still goes out on the next invocation.

**The flush does NOT go into the HTTP and streaming functions**: the first is invoked in sequence (one
request's batch goes out on the next) and the second keeps the process alive — they were the only two
that exported throughout the entire episode.

**`collector.yaml` has no `batch`** — it holds data waiting to fill a batch, and a half-full batch dies
with the freeze.

**But it DOES have `decouple`, and that line is a reversal the measurement forced.** The previous
version excluded it with the argument that it "is the same bet as batch under another name". Measured on
the stack: **14 failures in 5 minutes, and only on the streaming function**, all `context canceled`. That
is not the network — it is the INVOCATION ENDING with the export in flight. Exporting inside the
invocation's cycle only works when the invocation lasts longer than the export, and the streaming
function is precisely the one that answers fast and produces the most spans: without `decouple`, its
trace was the one most often lost.
<p>
Along with it came `timeout: 30s` and `retry_on_failure` on the exporter, and the number came from
`net/http: TLS handshake timeout` in the log: a new container's first export pays for DNS plus a TLS
handshake going out of a VPC, through NAT, and what was being lost was the cold start's trace — the most
interesting one of all.
<p>
**After**: `no more retries left` = **0** on all four functions. What remains in the log are attempts
(`dial tcp ... i/o timeout`) that the retry resolves — a visible failure, with the data delivered.

**The credentials come from the root `.env`**, which SST loads on its own; `support/functions.ts` passes
them to every function and FAILS the deploy if they are missing — a collector with no destination comes
up, does not complain, and silently loses the telemetry. Measured after the deploy: zero
`Exporting failed` on the three functions.

### TRACE PROPAGATION BETWEEN THE SERVICES

With RabbitMQ the whole saga was **a single trace**, for free: the connector's `tracing.enabled` was
already on on both sides. On AWS that regressed because of an asymmetry this document already recorded —
`smallrye-reactive-messaging-aws-sqs` brings an instrumenter and injects; **`aws-sns` 4.37.0 has no
tracing package at all** — and this system's outbound side is SNS.

**The outbound side now injects by hand**, in `AwsEventAttributes.inject`: the W3C `traceparent` goes
into the attribute map, next to `axon-routing-key` and friends. It is the only place both outbound
paths (SNS and SQS) pass through, and to the wire the `traceparent` is an attribute like any other.

**The inbound side extracts**, in `SqsChannelIngress`: there is no connector to instrument (Lambda hands
the `SQSEvent` straight to the handler), so the context is read from the attributes and activated with
`makeCurrent`.

**MEASURED on the stack**, in the publish log:

```
sns → group=939a4535-… attributes={axon-message-name=PostPreCreated, …,
      traceparent=00-7fc419aec2bb7739d0472b62d998f8d1-7c487761ccc959e1-03}
```

**THE OUTBOUND LEG IS CLOSED, and it was confirmed in the backend** — not just in the publish log. A
single trace, with both services inside:

```
quarkus-axon-graphql-posts   POST                         server
quarkus-axon-graphql-posts   GraphQL                      internal
axonposts-tagging            post-precreated-in receive   consumer
```

What creates the span on the other side is `SqsChannelIngress`: there is no connector to instrument
(Lambda hands the `SQSEvent` straight to the handler), so it extracts the context from the attributes
and opens a CONSUMER span by hand. Without that span `tagging` did not appear in any trace — and it is
worth remembering it was invisible for a SECOND, independent reason: the frozen batch, which the
telemetry section describes.

**MEASURED, with a root injected by hand at the proxy** (playing the browser's part): the outbound leg
closes `browser → web → posts-api → GraphQL:Create → tagging`, all under the same trace and with the
right nesting. The RETURN leg does not:

```
post-precreated-in receive   tagging     e090ff06…   the browser's trace   has a parent
post-completed-in receive    posts-api   92d47d4b…   A NEW TRACE           (root)
post-changes-in receive      tagging     8bddc39d…   -                     has a parent
```

The third line is what closes the diagnosis: the `updatePost` replica HAS a parent, so it is not SNS/SQS
losing the context — it carries it fine when the publisher is `posts-api` from the HTTP request's
thread. It breaks only when the publish comes AFTER an ingestion.

**WHAT STILL DOES NOT CROSS, and the cause is known.** `apps/tagging` receives the context, but the
`PostCreated` it publishes back goes out **without** `traceparent`. OpenTelemetry's `Context` is
thread-local, and ingestion hands the message to an in-memory channel that runs the work on ANOTHER
thread (`source.runOnVertxContext(true)`): the `makeCurrent` holds on the thread waiting for the ack, not
on the one doing the append and the publish. The fix is to carry the context in the message METADATA and
restore it in `ChannelEventIngestion` — infrastructure, without touching the listeners, which by rule
only hand off and leave.

### THE GRAPHQL SPANS

They were one line, and not code. SmallRye ships a `TracingService` in `smallrye-graphql-cdi` that talks
the OpenTelemetry API directly; it is an `EventingService` discovered by ServiceLoader and **gated by
`quarkus.smallrye-graphql.tracing.enabled`**. Off, nothing happens and nothing warns.

And the registration for the native binary comes along with it: Quarkus's `SmallRyeGraphQLProcessor` has
an `activateTracing` that emits the `ServiceProviderBuildItem` when the property is on. There was a
hand-written `@RegisterForReflection` here plus a `quarkus.native.resources.includes`; **both left** when
this was checked in the extension's bytecode — what the platform already does does not need rewriting.

### THE WEB CLIENT EXPORTS TOO

`apps/web/src/instrumentation.ts` is Next's hook, and it is just an `if`: the Node SDK depends on
`async_hooks` and module patching, so importing it at the top would break the edge bundle's BUILD.
`instrumentation.node.ts` turns on `@vercel/otel` with `fetch` and the GraphQL instrumentation;
`instrumentation.edge.ts` turns on `fetch` only — `@opentelemetry/instrumentation-graphql` does not run
on edge, and leaving it there is a broken build waiting for the first edge route.

The Next function carries the SAME collector layer, with `collector.yaml` injected by
`transform.server` (the component's `server` does not expose `copyFiles`). It exports to
`localhost:4318` — HTTP, which is what `@vercel/otel` speaks; the Java functions use 4317 (gRPC), and
the same `collector.yaml` opens both ports.

**What this adds**: the missing hop. A browser operation crosses TWO functions — the `/api/graphql` proxy
and `posts-api` — and only the second used to appear.

**And the `fetch` instrumentation needs `propagateContextUrls`, which starts EMPTY.** In `@vercel/otel`
the default is `[]` (apart from Vercel's deployment URLs): it creates the call's span — it shows up in
the trace — and **does not inject the `traceparent`**. The result is misleading, because the hop is drawn
and even so the service on the other side opens a new trace. Measured before: 317 `axonposts-web` spans
and 127 `quarkus-axon-graphql-posts` spans in the same hour, and ZERO traces with both. After the line,
`posts-api`'s `POST /graphql` started arriving with a REMOTE PARENT.

**Known limit, and it is the same as the telemetry section's**: the Next function is also frozen on
return, and `@vercel/otel` also uses a batch processor. Its spans do arrive, but **delayed by one
invocation** — on a site with traffic that goes unnoticed, and in an idle environment it does not.
`TelemetryFlush` does not reach it: it is Java, and there the exporter is the Node SDK.

### THE BUILD IS AN NX TARGET, AND THE TARGET LIVES IN THE RESOURCE GRAPH

Declaring a function is declaring its build. `QuarkusFunction` receives in `code` either a
{@code QuarkusBuild} — artifact, **build command**, **zip path**, bucket and sources — or another
function's `code`, because there are **six functions and four artifacts**, and three of them share a zip
with another.

```ts
export const postsInbox = new QueueWorker("PostsApiInbox", {
    code: {
        artifact: "posts-api-sqs",
        buildCommand: `npx -y nx run "${projects.postsApi}:lambda-sqs:native"`,
        output: "infra/dist/posts-api-sqs.zip",
        bucket: codeBucket,
        sources: sources.postsApi,
    },
});
export const postsMigrate = new Migrator("PostsMigrate", { code: postsInbox.code });
```

**What builds is an Nx target — the same decision `apps/web` already had.** There were ~250 lines of
`infra/scripts/package.sh` doing by hand what a monorepo tool does: choosing what to rebuild, storing
the result, and being the same command on the machine, in CI and at deploy. Today the script has 76
lines and **does not know how to build anything**: it translates the old flags into the targets and runs
all four serially.

| target | Maven profile | zip |
|---|---|---|
| `lambda-http` (posts-api) | `-Plambda-http` | `infra/dist/posts-api-http.zip` |
| `lambda-sqs` (posts-api) | `-Plambda-sqs` | `infra/dist/posts-api-sqs.zip` |
| `lambda-stream` (posts-api) | `-Plambda-stream` | `infra/dist/posts-api-stream.zip` |
| `lambda` (tagging) | `-Plambda` | `infra/dist/tagging.zip` |

**One target per ARTIFACT, and not one with the artifact in `--args`.** Nx interpolates `{args.x}` in the
command, but in `outputs` it only interpolates `{options.x}` — so a generic target would have the zip
path depending on a command-line argument, and running it without the argument would silently produce
`infra/dist/.zip`. With one target per artifact the `outputs` is a literal and the cache has a stable
entry per artifact.

**THE NAME CANNOT BE `package`.** `@nx/maven` infers 132 targets for each app, one per lifecycle phase
and one per mojo execution — and `package` is one of them, with `dependsOn: ["^install"]`. A
same-named target in `project.json` does not replace the inferred one: it MERGES with it, and would
inherit that `dependsOn` — which is exactly the `nx-build-state.json` trap documented further up. Hence
`lambda-*`, which are the Maven profile names and collide with no phase.

**The configurations are the TWO ways of compiling native, plus the JVM one:**

| configuration | what it passes to Maven | what it produces |
|---|---|---|
| `native` (default) | `-Dnative` | a binary from THIS machine's GraalVM |
| `native-container` | `-Dnative -Pnative-container` | an ELF/Linux binary, in the Mandrel builder |
| `jvm` | nothing | the usual `quarkus-app`/`function.zip` |

In `apps/tagging` the trigger is `-Dnative.tagging`, and that is by design: since `-pl` does not work in
this reactor, a shared trigger would make that module compile a binary on every invocation that asks for
native on the other app. The configuration is part of the command written in the SST component, which is
where you read which packaging each function receives.

**WHAT GOES TO AWS IS `:native-container`, and the default `:native` DOES NOT do.** `native-image`
produces an executable for the SYSTEM it runs on and does not cross: on a Mac the `cc` targets
`arm64-apple-darwin`, the `bootstrap` comes out **Mach-O**, and `provided.al2023` only executes
ELF/Linux. The failure mode is the worst there is — the deploy publishes the zip and prints
`✓ Complete`, and the function dies on invocation. The default stays `native` because whoever builds on
the machine almost always wants to run the application there.

**And `native-container` was NOT enough: there is a SECOND axis, and it cost a whole deploy.** The
Mandrel builder image is MULTI-ARCH (`linux/arm64` and `linux/amd64`, checked in the manifest), and
Docker picks the variant by the HOST's architecture, silently. On an Apple Silicon Mac it comes out
aarch64 and matches the six functions' `architectures: ['arm64']`; on an `ubuntu-latest` runner
(x86_64) it comes out amd64, and the function refuses to execute:

```
PostsMigrate axonposts:aws:QuarkusFunction → PostsMigrateInvocation aws:lambda:Invocation
{"errorMessage":"failed to exec /var/task/bootstrap","errorType":"Runtime.InvalidEntrypoint"}
```

It is the SAME failure mode as the paragraph above on another axis — there the operating system, here
the architecture — and the message **mentions architecture nowhere**. What caught it was the
`Migrator`: it INVOKES the function inside the deploy, so a migration that does not run becomes a
deploy that fails. Without that invocation the deploy would have printed `✓ Complete` and the six
functions would have been dead.

**Two fixes, and both are necessary:**

1. **the platform is DECLARED** — `quarkus.native.container-runtime-options=--platform=linux/arm64`
   in the ROOT's `native-container` profile. It stays only there: measured with
   `help:effective-pom`, the property reaches `apps/tagging` **and** `apps/posts-api`, even though the
   latter declares a profile with the same id (it redefines the other two properties and not this
   one). On an arm64 host the pin costs nothing — it is the variant Docker would already pick;
2. **the deploy job runs on `ubuntu-24.04-arm`** (free: the repository is public). Without it the pin
   would still be right, but it would require binfmt/QEMU on the runner, and an emulated
   `native-image` is unviable across four artifacts.

**And there is a pass-through argument**: `--extraFlags='...'` goes into the Maven command and — being a
target option — **enters the hash**, so the cache stays correct when somebody experiments with a flag.

**The cache, measured:** the first run takes whatever Maven takes; the second, **96 ms**. Deleting the
zip and running again **restores it from the cache** instead of rebuilding — which is the fresh-clone
case and the `sst refresh` case. And a `touch` on a source **does not** invalidate anything: Nx compares
CONTENT, not dates.

The `inputs` are the `quarkusLambda` named input, in `nx.json`, and what it says is what matters: the
app's own `production` (which already excludes `src/test/**` and `*.md`), plus `libs/**/pom.xml`,
`libs/**/src/main/**`, the `mvnw`, the `.mvn/` and the `collector.yaml`. Checked with Nx's own hash
inspector: **207 files** for `posts-api`, **118** for `tagging`, and ZERO coming from `target/`, from
`src/test/` or from `.DS_Store` — Nx builds the file map respecting `.gitignore`, so the two failure
modes that cost ~25 min of rebuild in one deploy are no longer possible. And the two apps stay isolated:
touching `apps/tagging` invalidates no `posts-api` artifact.

**Three mechanisms, and each covers what the others do not:**

- **`triggers`** with the SOURCES' fingerprint **plus the zip's existence** decides whether the COMMAND
  runs. The first half is from the sources and not from the zip because the first time the zip does not
  exist — it is a product of the resource, not an input to it. **The second half was added later, and it
  cost a whole deploy to surface:** an unchanged fingerprint makes Pulumi SKIP the command, and a skipped
  command produces no file. On an ephemeral runner, where `infra/dist/` starts empty, `assetPaths` then
  points at a path that only existed on the PREVIOUS deploy's machine; the `BucketObjectv2` tries to read
  it as it is created, does not find it, and the deploy dies **before creating anything at all** —
  printing `✓ Complete` and exiting with code 1.
  <p>
  MEASURED: the deploy that changed only files under `infra/` (which are not in `sources`, and should not
  be) built nothing, created nothing and took **2m59s of silence** between `~ Deploy` and `✓ Complete`.
  It needs BOTH conditions to appear: an S3 object yet to be CREATED **and** a SKIPPED command — which is
  why the `posts-api` functions, whose objects already existed, survived, and the three `tagging` ones did
  not.
  <p>
  `QuarkusFunction.artifactPresence` is asymmetric on purpose: a present zip returns a stable literal (the
  build stays skipped), an absent zip returns a fresh value on every evaluation. A literal like
  `"missing"` would be recorded in the state and would match on the next runner — where the zip is also
  absent — skipping the command again;
- **Nx's cache** decides whether running the command REBUILDS anything. It is what replaced the script's
  mtime check, and it wins on the three points where that hurt: it compares content, it respects
  `.gitignore` and it knows the graph;
- **`assetPaths`** reads the zip AFTER the build and hands it to S3 as a Pulumi asset. The documentation
  is explicit: *"a list of path globs to read after the command completes"*. **They do not decide whether
  the build runs** — that is `triggers`.

**Two details that genuinely cost time:**

- the four builds are **serialized** by a chained `dependsOn`, because the Maven profiles all write to
  `target/function.zip` and two in parallel erase each other. It is the same necessity that makes SST's
  own `siteBuilder` use a semaphore of 1. **Nx does not know this**: running the targets by hand with
  `run-many` requires `--parallel=1`, which is what `package.sh` passes;
- `build-env.sh` existed because the JDK and the native toolchain were wrong IN THE ENVIRONMENT, and it
  fixed them on every invocation. Both fixes were applied where they belonged — `~/.zshenv` and
  `xcode-select` — and the only residue that needed code became the `native-clt-toolchain` profile, which
  these targets' `native` configuration activates. See *`build-env.sh` WAS DELETED*.

**The development loop that made this viable**: running the binary in a local container
(`arm64v8/ubuntu` + the mounted `application`) against a Dev Services Postgres. Each
build→deploy→test cycle costs 11 minutes; build→container costs 7 and answers the same question. Two of
the four failures were found that way. What you CANNOT test that way is whatever depends on the Lambda
environment — `AWS_REGION` (without it the SNS publish fails with `Unable to load region`) and DNS,
which on the Docker bridge returns only IPv6.

**And there is a side effect that cost two test failures, because it is not on the subscription path.**
Hibernate statistics belong to the `SessionFactory` — it counts `PreparedStatement`s from ALL threads.
With a streaming processor reading the row of every post that passes through the event store, the FIRST
measurement of `BatchLoadingE2ETest` and `FederationEntitiesE2ETest` lands on top of its sweep and the
second does not: measured, 11 statements for 1 post against 4 for 5 posts. The test reported a
non-existent N+1 and, worse, would have started hiding a real N+1 under the noise. What fixes it is
`AbstractGraphQlE2ETest.statisticsOfAQuietDatabase()`, which opens the measurement window only after two
equal samples of the count. **A measurement counting statements in an application with an asynchronous
processor has to wait for silence** — and that is what the control proved: the same two classes pass 2/2
with no pooled processor and fail 3/3 with one.

**And there is a fourth reason, which is ARITHMETIC and not architecture.** A subscription is a long
connection, and Lambda charges for and limits exactly that: the environment is FROZEN between
invocations (a parked subscriber receives neither keep-alive nor writes) and the connection only exists
while the handler has not returned. Holding a subscriber therefore means keeping an invocation alive — a
15-minute ceiling, billed by duration.

On this function (arm64, 2048 MB): 2 GB × 900 s = 1800 GB-s × US$ 0.0000133334 = **US$ 0.024 per
subscriber every 15 min**, or **US$ 0.096/hour per connected subscriber**. A Fargate task of
0.25 vCPU / 0.5 GB costs US$ 0.0123/hour and serves all of them. <b>ONE subscriber on Lambda costs eight
times the whole machine that would serve everyone</b> — and it still drops every 15 minutes.

**What does NOT get through, measured and not deduced:**

- **subscriptions, over WebSocket or SSE.** The `quarkus-amazon-lambda-http` 3.39.2 JAR has not one
  occurrence of `vnd.awslambda.http-integration-response` or `RESPONSE_STREAM`: the response is an
  `APIGatewayV2HTTPResponse` assembled whole in memory. And `quarkus-amazon-lambda`'s
  `RequestStreamHandler` is **not** response streaming — it gives an `InputStream`/`OutputStream` over
  the invocation payload, buffered. But the transport is the smaller half:
  `SimpleQueryBus.emitUpdate` is **in-process**, and whoever appends the `PostCreated` is another
  function. There is nowhere to emit from, and reconnecting does not help because it is not the
  connection that is missing, it is the source;
- **the single trace.** On the inbound side there is no connector to instrument; on the outbound side,
  `smallrye-reactive-messaging-aws-sns` 4.37.0 has no tracing package (the SQS one does). The rule
  already written further down applies: half a trace is worse than none, because the gap looks like
  latency. What answers for now is `AxonMetrics`.

**RUN ON THE REAL ACCOUNT** (`us-east-1`, via `./infra/aws/e2e.sh`): the saga closes — the post is born
at version 1 with no tag, comes back at 2 with `Untagged` in ~50s, updates to 3; the six queues (three
work, three DLQ) stay empty; `_entities` answers without a token. Those ~50s are almost all cold start
of two 72 MB JVMs inside a VPC — it is the number that justifies the native binary.

And the two claims about subscriptions were confirmed on the stack, not just in the JAR:
`Accept: text/event-stream` goes **25 seconds without receiving a byte** (not even the 15 s keep-alive
arrives) and the WebSocket upgrade dies at the load balancer with **HTTP 400**.

**Three things that only appeared on AWS**, and all three fail misleadingly:

1. **The migration function would not come up — because of the problem it exists to solve.** Axon's
   recorder touches the EntityManager at STARTUP, `validate` runs against an empty database and the
   application dies with `missing table [accounts]` before any handler exists. The way out is
   `QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=none` **on that function only**; the other four
   keep `validate` and keep refusing to start if entity and schema diverge.

   **AND THAT LINE WAS NOT ENOUGH: the same chicken and egg has a SECOND layer**, which only appeared
   once the arm64 binary finally executed and the function got as far as starting. `validate` only
   CHECKS the schema, and switching it off solves what it was checking; the
   `PooledStreamingEventProcessor` **queries** it, and no `strategy` reaches that:

   ```
   Coordinator: Processor [post-subscriptions]. Initializing (16) segments
   ERROR: relation "aggregateevententry" does not exist        (SQLState 42P01)
   ProcessRetriesExhaustedException: Tried invoking the action for 30 times
   → AxonExtension.init fails → Runtime.ExitError, exit status 1
   ```

   The Coordinator reads the event store to find HEAD — the table that **V2 creates and that this
   function exists to create**. Thirty attempts in ~3 s and the whole startup falls over. It is not a
   diverging table name: V2 creates `aggregateevententry` and `tokenentry`, exactly the names Axon 5
   expects.

   The way out is `QUARKUS_AXON_SUBSCRIBINGPROCESSOR_NAMESPACES` **on that function only**
   (`infra/aws/compute/migrations.ts`), listing BOTH packages with `@EventHandler`: a subscribing
   processor binds to the event bus and does not touch the database at startup. It works because the two
   lists **partition** — checked in the bytecode,
   `DefaultAxonFrameworkConfigurer.eventhandlersForPoolProcessors(all, subscribingNamespaces)` filters
   out of the pool whatever the subscribing one claimed — and because
   `quarkus.axon.subscribingprocessor` is `@ConfigRoot(phase = RUN_TIME)`, so the environment's ordinal
   300 differentiates TWO functions that share the same zip. The `pooledprocessor.post-subscriptions.*`
   block is left orphaned there and that is harmless: the pool configurator is driven by the DISCOVERED
   handlers, and an entry no namespace matches is never consulted.

   **`apps/tagging` does not need this**: it only has a subscribing processor and it also excludes
   `PooledEventProcessingConfigurer`. **The list in `migrations.ts` mirrors `application.properties` and
   nothing checks it** — a new package with a handler goes into both places, and whoever is left out only
   reappears as a migration function dying against an empty database.
2. **The SNS connector attribute is `topic.arn`, with a DOT.** With a hyphen it is silently ignored
   (`SRMSG19504: Topic arn ... : null`) and the first publish dies with
   `InvalidParameterException: TopicArn or TargetArn ... no value for required parameter`, which reaches
   the GraphQL client as `System error`/`invalid-parameter` with no mention of configuration. The name
   was read from the connector's BYTECODE; it also uses `group.id` and `email.subject`.
3. **Keycloak's `iss` comes in lowercase** — the ALB's DNS has uppercase letters, but the `iss` is
   assembled from the `Host` header. DNS is case-insensitive; `iss` is not.

**And swapping Keycloak for Cognito collected on what `V1__initial_schema.sql` had promised.** The first
run failed with `user … already has an account in KEYCLOAK` — not a bug, the domain rule: Cognito's `sub`
is not Keycloak's, and without `identity_provider` in the token both became the same provider. The fix
was the path that migration already documented: `COGNITO` in the `AuthProvider` enum, the
`V6__cognito_provider.sql` rebuilding `ck_accounts_provider`, and a *pre token generation* trigger
**V1_0** putting `identity_provider: "cognito"` into the ID token. The result is better than "it works
again": the same person crossed the issuer swap without becoming two users —
`account linking: … from COGNITO linked to user …`, one row in `users` and two in `accounts`.
**A NEW `AuthProvider` ENUM VALUE = A NEW MIGRATION**, and there is now a precedent.

**An environment variable with no default kills startup, including for something that serves no HTTP.**
`quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL}` has no default (the `%prod.` one it replaces did), and
giving the variable only to the API function killed the other two `posts-api` functions at startup: the
OIDC extension initializes with the APPLICATION, not with the first request. That is why OIDC lives in
`postsEnv`.

And one about tooling: **`sst outputs` does not exist in 4.17.1** (the command prints the help) — the
outputs only come out of `sst deploy`. Hence `infra/aws/discover.sh`, which asks AWS for the name
prefixes.

The document of that migration, decision by decision, is `infra/aws/README.md`.

## The test client (`apps/web`)

A Next.js (App Router) app with Apollo Client, **additive like the rest**: no Java application file was
changed for it to exist. Seven pages, one per flow, and each one exists to prove something — the full
table and the decisions are in `apps/web/README.md`. What needs to be known from here:

**File organization is by FEATURE; atomic design is by COMPOSITION.** There is no `atoms/` or
`organisms/` folder: there is the route and what it needs (`app/<feature>/_components`, `_hooks`), plus
`app/_components` for what crosses features and `components/ui` for the shadcn primitives. The atomic
scale still exists — it just is not a folder.

**The props come from FRAGMENTS, and that is checked by the compiler.** The graphql-codegen
`client-preset` generates *fragment masking*: the component declares the fragment it needs and receives
`FragmentType<typeof Fragment>`, an opaque type. Reading a field outside the fragment **does not
compile**, even if the page's query brought it. The demonstration is in the `PostCard_post` /
`PostArticle_post` pair — the card **does not ask for `content`**. A new component = a new fragment in
the same file; the page only spreads (`...PostList_connection`) and enumerates no field.

**THE QUERY BELONGS TO THE PAGE, and it is prefetched on the server.** Every page with a query has a
`query.ts` next to its `page.tsx`; the server component passes it to `PreloadQuery` from
`@apollo/client-integration-nextjs` and the client component reads the SAME document with
`useSuspenseQuery`. The result comes across React's stream — the HTML arrives already filled and
hydration does not repeat the request. The variables are shared constants (`FEED_PAGE_SIZE`,
`LIVE_SNAPSHOT_SIZE`): server and client asking for different sizes is not an error, it is a second
request, and only the network tab will tell. `errorPolicy: "all"` on both sides, because with the
default an error becomes an exception in the RSC render and the whole route falls over. Two pages have no
prefetch, and both say why in the file itself: `/saga` measures a post that does not exist yet, and a
subscription never terminates.

**`possibleTypes` is generated, not written.** `me` returns the `User` interface and `_entities` the
`_Entity` union; without the map, matching `... on Author` in the cache is heuristic and silently applies
the fragment to the wrong type. What generates it is the `fragment-matcher` plugin (`codegen.ts`), and
what consumes it is `lib/apollo/cache.ts`.

**Authentication is by SERVER ACTION.** `app/actions/auth.ts` is the only door to Cognito: the password
goes to the Next server, which calls `InitiateAuth` and stores both tokens in `httpOnly` cookies. The
browser never sees the refresh token, and the ID token only reaches it in memory. The bearer is the **ID
token** for the reason already documented in `infra/aws/identity/index.ts`.

**THE BROWSER ONLY KNOWS `/api/graphql`** — the application's own proxy (`app/api/graphql/route.ts`).
Three things this solves: the ID token **stops existing in the browser** (the proxy is what puts it in
the header, reading the `httpOnly` cookie; `lib/apollo/token.ts` and the `auth-link` are gone, and the
`Session` that goes down to the client no longer has the field); there is no CORS on the path; and there
is a single place where streaming can be solved. The SERVER does not go through the proxy — `PreloadQuery`
talks to the API directly, because opening an HTTP connection to itself would cost another invocation and
latency.

**Subscriptions: the proxy RELAYS, and nothing else.** `/api/graphql` does a `fetch` to the upstream and
returns `upstream.body` — the response body, as it came. It does not interpret the protocol, assembles no
frames and does not know what a subscription is. The semantics belong to the domain, served by
`posts-api`: `onPostCreated` when the post REACHES version 2, `onPostUpdated` on every change from there
on.

**There was an EMULATION here, and it died of a good cause.** While the upstream was the API Gateway —
which assembles the whole response in memory — the stream did not open, and the proxy polled in a loop,
reshaping the client's selection into a `post(id:)` query with an alias. It stopped having a purpose when
`NEXT_PUBLIC_GRAPHQL_URL` came to point at the Function URL with response streaming: the relay always
works, and an alternative path that never executes is a path nobody fixes. The `subscription-probe.ts`,
the mode-announcement frame and the "real stream / emulated" badge on the `/live` page went with it.

**WHAT HOLDS THAT RELAY UP IS A CDN NUMBER**, and it is not in the proxy's code. CloudFront cuts off an
origin that stays silent longer than the `OriginReadTimeout`, and a BARE `sst.aws.Nextjs` creates its
distribution with a literal **20 s** (`ssr-site.ts:996`) — verified on the account. A `posts-api` cold
start takes 15–26 s (timed twice) and in that window the proxy has no byte to relay: half the cold starts
became 504s. The proxy once had keep-alives purely to fill that silence. **A silent proxy is NOT a
stalled proxy — but the CDN cannot tell the difference.**

What solves it is the `sst.aws.Router` in `infra/aws/edge/`: with the site ROUTED, SST mirrors the
server's `timeout` into the route's metadata (`ssr-site.ts:1857`), and the number stops being written
twice. **60 s is the ceiling**, and not by taste: it is CloudFront's maximum for a dynamically chosen
origin, and going past it makes the edge refuse the origin — an attempt with 360 s took the whole site
down with 502.

**MEASURED on the stack after the swap**: a subscription through the proxy took **26 s to the first
byte** and delivered the event normally. With the previous 20 s, that connection would have been cut —
which is the proof that the ceiling was the bottleneck, and not the proxy.

**And there is an effect no configuration fixes: EVERY CONCURRENT SUBSCRIBER PAYS A COLD START.** An SSE
connection holds the invocation for as long as it is open, and a Lambda container serves one invocation
at a time — so the second simultaneous subscriber necessarily lands in a new container. Measured: two
connections opened together took 27 s, with the function already warm. It is the same arithmetic that
makes `warm: 1` insufficient here: it warms ONE container, and the second subscriber does not find it
free. What removes this is provisioned concurrency (≈US$ 21/month for a 2 GB container) or the native
binary — `libs/axon-native-support` exists for that.

**CORS belongs to the API GATEWAY, not to the application** (`infra/aws/support/http-api.ts`). That way
the preflight does not wake a 72 MB JVM. `allowOrigins: ["*"]` because allowing the site's URL would
create a circular dependency with the component that needs the API's URL. In dev Quarkus has no CORS on:
use `QUARKUS_HTTP_CORS=true QUARKUS_HTTP_CORS_ORIGINS='http://localhost:3000'` with `quarkus:dev`.

**Nx builds, OpenNext packages, SST publishes.** The `web:open-next-build` target depends on `build`, and
it is what `sst.aws.Nextjs` calls (`buildCommand`) — a single definition of how the site is built, with
caching. Two lines of `next.config.ts` exist because of this and neither is tuning:
`outputFileTracingRoot` pointing at the monorepo root (without it the bundle comes out missing the
packages pnpm left as symlinks, and the error only shows up at runtime) and `output: "standalone"` turned
on by `INFRA_PROVIDER=aws` (which the component's `environment` injects into the build).

**Traps already paid for**, and the first ones fail silently:

0. **DO NOT leave `next dev` running during an `sst deploy`.** `open-next.config.ts` has
   `buildCommand: "exit 0"` — it PACKAGES `.next`, it does not build it — and the dev server rewrites
   that directory continuously. What ships becomes a development build: the HTML references
   `/_next/static/chunks/main-app.js` (no hash, a name only dev uses), S3 answers 403, the page does not
   hydrate, the form falls back to a native POST and the server action dies with
   `TypeError: a[d] is not a function` in `webpack-runtime`. None of those messages points at the cause.
1. **Login: every SOFT navigation to `/feed` broke the header.** The layout reads the cookie, and Next
   prefetches visible `<Link>`s — the ANONYMOUS layout payload is already in the Router Cache when the
   login happens. Under `next dev` (no prefetch) nothing shows up. There were THREE sources of soft
   navigation, removed one at a time: the action's own `redirect()`; the `revalidatePath("/", "layout")`
   in it (which revalidates `/login`); and — the one that survived the other two — the fact that **Next
   re-renders the current route after every server action**, which fired the `redirect` that was in
   `/login`'s `page.tsx`. Final design: the login page does not redirect (whoever has a session sees a
   card), and the form calls `signIn` by hand — not through `useActionState` — so that
   `window.location.assign` runs in the same tick as the response. A new session is a new document.
2. **`Query.posts` is ASCENDING creation order.** `posts(first: 10)` are the ten OLDEST; a post created
   now goes to the end. The real-time panel was silent because of this. With no `last`/`before` in the
   schema, `/live` asks for the whole page (ceiling 100) and looks at the tail — above 100 posts the
   newest disappear, and the honest fix is reverse pagination in the API.
3. **`relayStylePagination()` without `keyArgs` merges EVERY read of `posts`** — `/live`'s
   (`first: 100`) with the feed's (`first: 6`), and the feed came back with a hundred cards.
   `keyArgs: ["first"]` separates the two without breaking "load more".
4. `@graphql-typed-document-node/core` has to be a DIRECT dependency (pnpm does not expose transitive
   ones, and without it every `graphql()` becomes `unknown`); the shadcn *base-nova* `Button` is Base UI
   and uses `render={<Link/>}`, not `asChild`; and `secure: true` on the cookie kills login under
   `next dev`, because the origin is http.

```bash
pnpm --filter @axonposts/web dev        # http://localhost:3000
pnpm --filter @axonposts/web codegen    # regenerates src/gql/ (runs along with the build)
pnpm --filter @axonposts/web schema:pull GRAPHQL_URL   # updates apps/web/schema.graphql
npx nx run web:open-next-build          # what SST calls at deploy
```

## Tests

Surefire runs everything under `./mvnw test`, including the `*E2ETest` classes — **Docker has to be
up**.

### THE TEST LIVES IN THE MODULE IT TESTS

It all used to be in `apps/posts-api/src/test`, including what proves the domain that lives in `libs/`.
Today:

| module | tests | what it proves |
|---|---|---|
| `libs/platform` | 7 | `SoftDeletable` |
| `libs/users` | 7 | `Authenticatable` and account linking |
| `libs/posts` | 24 | `Post` and `Tag` — the domain invariants |
| `libs/axon-channels` | 19 | addressing and tag encoding: the WIRE contract |
| `libs/axon-aws` | 6 | the attributes the SNS filter policy matches |
| `libs/test-support` | 7 | migration order, in Flyway's version format |
| `apps/posts-api` | 117 | application, GraphQL, wiring and the 8 `*E2ETest` classes |
| `apps/tagging` | 10 | the decision, the wiring and the whole path with no broker |
| `apps/web` | 28 | the ID token claims — Cognito's and the realm's — and the saga's version semantics in the UI |
| `apps/web-e2e` | 21 | the whole system through Chromium: login, authorization, reading, the subscription and the saga |

**The argument is `apps/tagging`:** it imports `libs/posts` to get the `Post` rules. While those rules
were proven in ANOTHER app's test folder, the lib did not stand on its own.

**`libs/test-support` is the declared exception to the rule that `libs/` holds only domain and
infrastructure.** It keeps the two fixtures more than one module uses (`RecordingDomainEvents`,
`UserFixtures`), in `src/main` and consumed with `<scope>test</scope>` — not in a `test-jar`, which
would require a maven-jar-plugin `<execution>` in every lib that exports a fixture, with the mojo targets
`@nx/maven` infers along with it. The package is `...testing` and not `...support` so there is no split
package with the `support/` left in `apps/posts-api`.

**`AuthenticatableTest` builds its own objects, and not out of carelessness.** Using the shared fixture
would make `libs/users` depend on a module that depends ON IT, and the Maven reactor refuses — it does
not distinguish test scope when detecting a cycle. What is left is better than the workaround: a lib
exercising its own invariants through its own public API needs nobody.

### `apps/tagging` had ZERO tests, and now has three levels

| file | level | what it catches |
|---|---|---|
| `CompletePostWithDefaultTagCommandTest` | unit, no Quarkus | the DECISION: default tag, version 2, and the third guard against duplicate delivery |
| `TaggingWiringTest` | `@QuarkusTest` | the WIRING: what fails silently |
| `TagDecisionE2ETest` | `@QuarkusTest` | the whole PATH, from the inbound byte to the outbound envelope |

**The whole path with no broker, and that is not doubling.** Three `%test.` lines swap the RabbitMQ
connector for `smallrye-in-memory`. What changes is only the TRANSPORT: ingestion still deserializes the
real envelope, appends to the real event store and fires the real processor, and the outbound side still
goes through `OutboxRouting` and a `ChannelAddressing`. The test pushes a `byte[]` in and READS the
envelope that came out.

**The wiring guard here is stricter than the other app's.** There, a package outside
`subscribingprocessor.namespaces` falls into an anonymous pooled processor and becomes eventually
consistent. Here `PooledEventProcessingConfigurer` is excluded — there is no pooled processor to fall
into, and the handler simply DOES NOT RUN. The saga stops at version 1 and nothing in the log says why.

### `libs/axon-native-support` had ZERO tests — and was the module with the most to lose

It had none, and it is the one that needed them most: **none of the failures it prevents appears on the
JVM**, and three of the four appear only at RUNTIME, on AWS, with messages that point at the wrong place.

`AxonNativeImageProcessorTest` indexes the `Fixtures` classes with the **real Jandex** — the same one
augmentation uses — and calls the `@BuildStep` methods directly, collecting what they produce. There is
no Quarkus starting up: a build step is a method, and what it returns is an object. `BuildProducer` is a
single-method interface, so the test collects with a five-line double.

The fixtures are the real domain's shapes, reduced, and each one exists because of a failure that has
already happened — in particular an event with a NESTED record inside a `List<>`, which is exactly the
shape that stopped the saga at version 1 with Lambda's `Errors` counter at ZERO and the queues empty.

**Proven that the tests are NOT empty:** commenting out `types.addAll(composedTypesOf(types, index))`
fails exactly `registersTypesNestedInsideAMessagePayload` and
`theClosureIsTransitiveAndNotOneLevelDeep` — the two from that failure, and only those.

The module went from **0% (invisible, with no report) to 88.7%**.

**Axon enters the deployment pom only in `<scope>test</scope>`, and for the FIXTURES.** The processor
does not depend on Axon to compile: it works over the index and names the annotations by `DotName`, as
strings. But an honest test needs REAL annotated classes to index — otherwise it would be asserting
about an index it invented itself.

### COVERAGE: 60.3%, and the previous number was prettier because it LIED

`quarkus-jacoco` in both apps, `jacoco-maven-plugin` (from the root pom) in the libs — and `<skip>` for
the second in the apps, because two agents in the same module produce two `.exec` files that do not sum.

**Turning on coverage for the libs LOWERED the total from 64.3% to 60.3%, and that drop is the news:**
`libs/axon-channels` (1378 instructions) and `libs/axon-aws` (219) had no tests at all, so they generated
no report — and what generates no report does not enter the denominator. That was 1597 invisible
instructions.

```
libs/axon-channels   19.2%      apps/tagging      43.2%
libs/axon-aws        31.5%      apps/posts-api    76.0%
libs/platform        46.8%      libs/posts        76.9%
libs/users           43.9%      TOTAL             60.3%
```

### MOCKITO: where it goes in, and where it deliberately does NOT

- **NOT in the domain.** `PostTest`, `TagTest` and `AuthenticatableTest` still use no mocks. The domain
  decides on its own, and its only collaborator is a port that a three-line lambda doubles better than
  any framework. A mock there would couple the test to the SHAPE of the call instead of to its result.
- **YES in infrastructure.** `EventAddress` reads an `EventMessage` — a library type, with a handful of
  methods this code does not use. Implementing it by hand would be thirty lines nobody reads in order to
  assert two.
- **`quarkus-junit5-mockito` in the apps**, and not plain `mockito-core`: it brings `@InjectMock`, which
  REPLACES a CDI bean inside a `@QuarkusTest` — the difference between testing the real graph with one
  piece swapped and testing a piece on its own.

**The `test-unit` target runs `clean`, and it was measured twice.** Without it, after a series of failed
`package` runs the suite starts failing with `NoClassDefFoundError` in Quarkus's `FacadeClassLoader`
(2 out of 2), and passes again with `clean`. It costs ~9s on a cache MISS; on a hit the command does not
even run.

### TWO COMMANDS, ONE TARGET PER MODULE — Nx orchestrates, Maven executes

```
pnpm test       nx run-many -t test         → 10 projects (9 Maven + `web`)
pnpm test:e2e   nx run-many -t test:e2e --parallel=1   → 3 projects (2 Maven + `web-e2e`)
```

**Neither one lists anybody.** Every module with tests declares its own target, and `run-many` resolves
it. It used to be ONE target in `apps/posts-api` running `./mvnw test` from the root: the 190 tests ran,
but Nx saw a single project — with no per-module cache and no `affected`.

#### THE LOWER LEVEL DECLARES NO TARGET: `@nx/vitest` infers it

On the Maven side the target is hand-written in each `project.json`, because `@nx/maven` does not know
what "the lower level" is. On the JavaScript side it is not: `@nx/vitest` creates the target from the
`vitest.config`, with the command, `cwd`, cache, coverage `outputs` and — what matters most — the right
`inputs`, including `{ "externalDependencies": ["vitest"] }` and `{ "env": "CI" }`. The second one is not
a detail: the config chooses the reporter by `process.env.CI`, and without it a result cached on the
machine would be REPLAYED in the pipeline, never writing the junit XML.

**It is `@nx/vitest` and not `@nx/vite`.** Since Nx 23.2 Vitest has its own package, and it is the one
that serves a project that only TESTS with Vite. `web` builds with Next, not with Vite.

**There is ONE registration now, and it has no `include`/`exclude`.** There used to be two, because
`apps/posts-api-e2e` was a Vitest project as well and a plain `testTargetName: "test"` would have made
`pnpm test` bring up two JVMs and a broker — silently, staying green and ten times slower. The upper
level is **Playwright** today (`apps/web-e2e`), so the collision is gone: `@nx/vitest` finds one
`vitest.config` in this workspace, `apps/web`'s, and a new one in any other project is born AT the
right level with nobody editing `nx.json`.

**`test:e2e` is HAND-WRITTEN in `apps/web-e2e/project.json`, and `@nx/playwright` is NOT registered.**
The plugin infers a target named `e2e` from `playwright.config.ts`, and this monorepo's upper level is
called `test:e2e` on all three projects — that is what makes `nx run-many -t test:e2e` reach the two
Java suites and the browser one in a single command. Renaming the convention to suit a plugin would be
the tail wagging the dog, and the target is eleven lines.

**`testMode: "run"`, and not the `watch` default.** With the default the inferred command is plain
`vitest`, which only runs once when `CI` is set — on the machine, `pnpm test` would enter watch mode and
NEVER finish. Whoever wants to watch runs `pnpm --filter @axonposts/web exec vitest`.

**What is left in `project.json` is only what the plugin has no way to know**: `web`'s `dependsOn`
on `codegen` and the junit XML `outputs` — the plugin deduces the COVERAGE directory from the config,
and the reporters' `outputFile` it does not read.

**Careful when declaring `inputs` there: they REPLACE the inferred ones, they do not add.** `web`
declares none, and that is the normal case.

| | before | now |
|---|---|---|
| cold run | 1m17 | 1m32 |
| **with nothing changed** | 1m17 | **8.1s** (95% cache) |
| **after touching a lib** | 1m17 | **8.8s** (97% cache) |

The cost is 15s more the first time; the gain is the whole working day after it.

**NX IS WHAT PUBLISHES INTO `~/.m2`.** `-pl <module>` resolves dependencies from the local repository,
not from the reactor — so they have to be installed. That is `dependsOn: ["^mvn-install"]`, the target
INFERRED by `@nx/maven`: the order comes from the graph, not from a hand-written list.

**`targetNamePrefix: "mvn-"` is what makes all of this possible**, and it resolves in one go the family
of traps this section used to document. With the inferred targets prefixed (`mvn-test`, `mvn-install`,
`mvn-package`), a hand-declared `test` target has nothing to collide with — neither in the module, nor at
the ROOT, where the inferred `test` ran the whole reactor and duplicated everything.

**`-pl` WORKS for `test`**, and this corrects what is written further up: the
`quarkus-extension-maven-plugin` validation only hits BUILD goals. Measured — `-pl apps/posts-api` runs
the 56 tests in 26s, `-pl libs/axon-channels` runs 19 in 2.3s.

**And there is no longer a `build-env.sh` in front of any of this.** See the next section.

#### A COLD `~/.m2` is a different environment, and it only exists in CI and on a fresh clone

Three things broke here and NONE of them shows up on the machine of somebody who has run `./mvnw
install` once — because what is missing is always an artifact that command left in the local repository
ages ago. It was CI's first run that revealed them, all three at once, and all three with a message that
points at the wrong place:

| what is missing | what the message says | where it is fixed |
|---|---|---|
| the PARENT POM | `Could not find artifact dev.manuelantunes:quarkus-axon-graphql:pom (absent)`, inside a `Could not collect dependencies for project axonposts-users` | `root:publish-local`, in the ROOT's `project.json` |
| the extension's DEPLOYMENT artifact | `Deployment artifact ...-deployment is missing the following dependencies: axon-native-support::jar, quarkus-core-deployment::jar` | the `-pl` for `axon-native-support`, which now takes the PAIR |
| `apps/web/src/gql` | `Cannot find module '@/gql'` plus 44 cascading `implicitly has an 'any' type` errors | `dependsOn: ["codegen"]` on `web`'s `typecheck` |

**The parent POM is nobody's dependency, and it is EVERYBODY's.** `-pl libs/users` resolves
`axonposts-platform` from `~/.m2` — and reading that artifact's DESCRIPTOR requires the parent its POM
declares. Nobody installed the parent, because `-pl <module>` never includes it in the reactor. What
installs it now is `./mvnw install -N -DskipTests -q`, non-recursive, in a `root` project target.
**There is no hand-written list tying that target to the rest**: `@nx/maven` already puts an edge from
EVERY module to `root` — that is the POM parent relationship — so the `dependsOn: ["^publish-local"]`
each module already had reaches the new target and the order still comes from the graph.

**The extension's validation does not require the whole reactor: it requires the PAIR.** It is the same
one from the warning at the top (`-pl` does not serve build goals), and it runs on the RUNTIME artifact,
checking what the DEPLOYMENT one declares. With the deployment outside the reactor and outside `~/.m2`,
there is nowhere to resolve it from. Hence
`-pl libs/axon-native-support/runtime,libs/axon-native-support/deployment` — both in the same command,
which is the minimum the validation accepts.

**And `src/gql/` is generated and gitignored**, so the client's `typecheck` was asserting about a
directory that only exists after a `pnpm dev` or `pnpm build`. `build` runs codegen internally and so
never noticed; whoever checks WITHOUT building had to say in the graph that it depends on it.

**MEASURED, with `~/.m2/repository/dev/manuelantunes` deleted — which is exactly CI's state**:
`pnpm test` passes, 15 tasks in 57s, with `root:publish-local` ahead of the other eight.

### `build-env.sh` WAS DELETED — and what it knew is here

It existed because this machine's `JAVA_HOME` was wrong for everything that was not a terminal: the line
lived in `~/.zshrc`, which zsh reads **only in an interactive shell**. Nx, the IDE and any script were
born from a non-interactive shell, did not see that line and inherited a JDK 17 from the parent process.

**The two fixes that replaced it, both outside the code:**

1. **`JAVA_HOME` and `GRAALVM_HOME` moved to `~/.zshenv`**, which zsh reads on EVERY invocation. That is
   what made `./mvnw` work with no prefix and unblocked the `@nx/maven` inferred targets;
2. **`sudo xcode-select --switch /Library/Developer/CommandLineTools`**, because the `cc` coming from
   `Xcode.app` did not even load (`dlopen(libxcodebuildLoader.dylib): Symbol not found: _XPCTypeBool`).
   With the switch, the system `cc` works again and the `PATH` the script injected stopped being
   necessary.

**What the `xcode-select` switch did NOT solve, and became a Maven profile:** `xcrun` started handing
over **SDK 27** by default, and the Command Line Tools' `ld` cannot digest it —

```
/Library/Developer/CommandLineTools/SDKs/MacOSX27.0.sdk/usr/lib/libSystem.B.tbd:4:20:
error: unknown architecture
```

The fix is `-isysroot` pointing at the **symlink** `MacOSX.sdk` (today → 26.5; a symlink and not a fixed
version, so it does not break on the next CLT update). That is a Quarkus property, so it lives in the
**`native-clt-toolchain`** profile, which BOTH apps now have, and which the `lambda-*` targets' `native`
configuration activates. It does **not** go into `native-container`: there the compilation happens inside
the Mandrel builder image, where that path does not exist.

**The three checks the script did and that did NOT become code**, because they were diagnosis and not
fixes — they stay here, which is where somebody looks when the message does not help:

| symptom | what it really is |
|---|---|
| `exit 137` in `[1/8] Initializing`, with no mention of memory | Docker has less than ~12 GiB for the container build |
| `Unable to detect supported DARWIN native software development toolchain` | the system `cc` does not load — it is Xcode, and the way out is the `xcode-select` above |
| `error: release version 21 not supported`, or `class file version 65.0 ... up to 61.0` | `JAVA_HOME` came from a non-interactive shell. The line has to be in `~/.zshenv`, never only in `~/.zshrc` |
| `libSystem.B.tbd: error: unknown architecture` | the default SDK does not serve; it is the missing `-Pnative-clt-toolchain` |

### The `*NativeIT` classes SAY what they need, instead of blowing up in the framework

A `@QuarkusIntegrationTest` is black-box: it does not start the application in-process, it executes the
BINARY that `package` produced. Run without that binary — by clicking the class in the IDE — the failure
was

```
IllegalStateException: Unable to locate the artifact metadata file created that must be
created by Quarkus in order to run integration tests.
```

Technically correct and practically useless: it does not say WHICH command produces the artifact, and it
appears as an ERROR, which suggests a defect in the code — when the code was never executed.

`@RequiresNativeArtifact` is a JUnit `ExecutionCondition` that looks for
`target/quarkus-artifact.properties` and, not finding it, **SKIPS with the reason written out**,
including the command line that builds it. The condition runs BEFORE the Quarkus extension's `beforeAll`,
so the boot attempt never happens; the reason goes into the surefire XML, which is what the IDE shows
next to the test.

**Skipping and not failing, because there is nothing to assert.** A test with no subject is not failing,
it is out of context. And this does NOT hide a regression: in the flow that matters the artifact always
exists — the ones running the `*IT` classes are failsafe, in the `integration-test` phase, after
`package`, and only with the `native-it` profile, which is what flips `skipITs` to `false`. There the
condition never skips.

#### `-Dnative` BUILDS the binary; `-Dnative.it` also RUNS the ITs

These are two different jobs and used to be one profile, which made the ITs impossible to run. The
separation is forced by a fact that is easy to miss: **the `lambda-*` Nx targets pass `-Dnative`**, so
anything the `native` profile adds goes into the three PRODUCTION artifacts.

| | `-Dnative` | `-Dnative.it` |
|---|---|---|
| native binary | yes | yes |
| `skipITs` | stays `true` | `false`, plus the failsafe execution |
| the `%test` scaffolding | **never** | yes — see below |
| who uses it | `lambda-http`, `lambda-sqs`, `lambda-stream` | `./mvnw verify`, the `test:native` target |

**A native binary is built in `prod`, and that is what broke the ITs**: two things live only under
`%test`, and without them `AxonNativeIT` and `SubscriptionNativeIT` cannot pass, because they assert the
saga's outcome and there is no `apps/tagging` in a failsafe run.

1. `axonposts.saga.tagging-in-process=true` — it gates `InProcessTagAssignment` through
   `@IfBuildProperty`, so it has to reach **augmentation**. It travels in the
   `quarkus-maven-plugin`'s `<systemProperties>`, which is the supported way (checked in the plugin
   descriptor: the `build` mojo takes `systemProperties`);
2. `quarkus.axon.subscribingprocessor.namespaces` with `…post.saga` added. Enabling the double alone is
   NOT enough: that package would belong to no processor, fall into an anonymous pooled one with a JPA
   token store, and **startup dies** in `JpaTokenStore.retrieveStorageIdentifier`. It is the silent trap
   from *A handler's package chooses its DELIVERY*, arriving loudly.

**And the test port is `0`, on purpose.** Quarkus's default test port is **8081**, which is also this
project's `KEYCLOAK_PORT` in `docker-compose.yml` — and the local `test:e2e` deliberately leaves compose
up. The binary then dies with `Port 8081 seems to be in use`, which names no container. A random port
removes the whole class of collision instead of trading one number for another.

**Careful when re-running only the ITs**: that configuration lives in an `<execution>`, so a direct
`./mvnw failsafe:integration-test` from the CLI does **not** pick it up (the CLI runs `default-cli`) and
the binary goes back to 8081. Either run `verify`, or pass `native.image.path`,
`quarkus.http.test-port` and the namespaces by hand.

**`onPostCreated` announces v2; `onPostUpdated` starts at v3.** `SubscriptionNativeIT` used to assert
`onPostUpdated` at version 2 and could never pass: `PostUpdatedEventHandler` only handles
`PostUpdatedEvent`, and the completion at v2 is a `PostCreatedEvent`. The JVM sibling
(`NewsletterSubscriptionE2ETest.onPostUpdatedFiresOnARealEdit`) always had it right. The test now opens
both subscriptions and is named after the rule.

### The statement MEASUREMENT is the SMALLEST of three runs, and that is FIRST

`BatchLoadingE2ETest` and `FederationEntitiesE2ETest` count `PreparedStatement`s to prove the batch
works. `statisticsOfAQuietDatabase()` proves the database was quiet BEFORE the window — not that it stays
quiet DURING it. The processor that notifies the subscribers is asynchronous and counts in the SAME
`Statistics` (it belongs to the `SessionFactory`, not to the session), so it wakes up in the middle of the
measurement.

The symptom was a textbook REPEATABLE violation: the same classes passed **3 out of 3 in isolation** and
failed with the eight end-to-end classes together —
`two authors cost 7 statements against 4 for one author`. With more posts in the event store the
processor sweeps more, and the chance of landing on the window grows: **the result came to depend on who
else was running.**

**The fix comes from an observation, not from a tolerance:** the processor only ADDS. The query always
costs the same, and interference can only push the count up — so the SMALLEST of several runs is the real
cost. `cheapestStatementCount` does three and keeps the minimum.

And the property stays intact: a real N+1 makes EVERY run more expensive, including the cheapest — the
minimum goes up with it and the assertion breaks. **Three** because with one there is no minimum and with
two an unlucky window in each ruins both; the query is read-only, repeating it changes no state.

### `apps/web-e2e`: the saga as an APP, through a BROWSER

What used to be `docker/e2e/run.sh`, then `apps/posts-api-e2e` (Vitest, no browser), is today a
**Playwright** app. The migration kept every assertion the Vitest one had and added the half nobody
was proving: that a person can sign in, be refused, write a post in a FORM and watch the other service
complete it. `apps/posts-api-e2e` WAS REMOVED when this went green — the same saga asserted in two
places is the same rule in two places, and the first adjustment separates them silently.

It declares `implicitDependencies` on the two services **and on `web`**, and that is what puts it in
the graph: touching `apps/tagging` invalidates it, and an `nx affected` reaches it with nobody listing
anything.

```
apps/web-e2e/
  src/specs/      the SPECS — authentication, authorization, reading, live, saga
  src/fixtures/   what a spec gets: the accounts, signIn, graphql, the two stores, the broker
  src/support/    the MECHANISM: the stack, the services, the event store, the broker, the issuer
  src/global-setup.ts / src/global-teardown.ts
```

**`src/support/` is outside `testDir` on purpose** — what is there is mechanism, and mechanism is not a
spec. Playwright's `testDir` is `./src/specs`, so the split is structural and not a naming convention.

**THE TWO JAVA SERVICES ARE CONTAINERS; THE CLIENT IS A PROCESS.** `ChoreographyStack` brings
`posts-api` and `tagging` up through `docker compose --profile apps`, from images the build produced,
and starts `apps/web` with `next start`. The asymmetry is not sloppiness: the Java side ships images
and the Next side ships a `.next` directory — each is started the way it is deployed.

**THE CLIENT'S ENVIRONMENT IS PASSED EXPLICITLY, AND THE STACK RUNS ITS BUILD.** `NEXT_PUBLIC_*` is
**inlined at build time**, so the value that counts is the one the build saw. And `apps/web/.env.local`
exists on a developer's machine pointing at the **deployed AWS stage** (`infra/scripts/discover.sh`
writes it) — a build that inherited it would produce a client talking to API Gateway while the suite
asserted against a container on `localhost:8080`, and the failure would look like the saga not closing.
Real environment variables beat `.env` files in Next, so `ChoreographyStack.buildTheClient()` calls
`npx nx run web:build` with them set, in ONE place. It is deliberately **not** an Nx `dependsOn`: a
dependency runs with the ambient environment, which is exactly the one that is wrong here.

**AND `apps/web` GREW AN IDENTITY PROVIDER BECAUSE OF THIS SUITE.** It signed in only against Cognito,
and the local issuer is Keycloak — so the browser could not log in at all, and the UI gated every write
on `cognito:groups`, which a Keycloak token does not carry. What that bought is a port
(`lib/auth/identity.ts`), an OIDC password grant that DISCOVERS its token endpoint
(`lib/auth/oidc.ts`), a selector (`lib/auth/provider.ts`: OIDC when `OIDC_ISSUER_URL` is set, Cognito
otherwise) and a fallback in `lib/auth/claims.ts` from `cognito:groups` to `realm_access.roles`.
Nothing about the AWS deployment changed — `OIDC_ISSUER_URL` is unset there.

**The bearer the OIDC path stores is the ACCESS token, not the ID token**, and that is forced, not a
shortcut: Keycloak puts `realm_access.roles` in the access token and `posts-api` validates the access
token, so a session holding the ID token would sign in and be refused on the first mutation. On Cognito
it is the opposite — its access token carries no `email`, which `UserProvisioning` needs — which is why
that provider keeps returning the ID token. The two divergences are the two IdPs', not this code's.

What that removed, and none of it was cosmetic:

- **the host's JDK.** The test used to `spawn` `java -jar` with `JAVA_HOME`, and a `release 21` fast-jar
  under a JDK 17 exits with **code 1 and an empty log** — no `UnsupportedClassVersionError`, not a line
  on stderr — which is indistinguishable from "the application died at startup". `Service` carried
  bespoke code to name the JDK in that case. The image carries its own runtime, so the failure mode and
  its detector both left;
- **the staged copy.** There used to be a `target/stack` holding both fast-jars, and it existed for one
  reason: Maven writes into `apps/<app>/target/quarkus-app`, and those modules' own `test:e2e` runs
  `./mvnw clean`. Running both levels in one `pnpm test:e2e`, the order decided the result — MEASURED,
  the saga died with `Unable to access jarfile .../quarkus-app/quarkus-run.jar` and the target that
  deleted it had passed. The image now CONTAINS the artifact, so a later `clean` cannot reach it;
- **the packaging step in this project.** It moved to a `build` target on each app, which is where Nx
  expects it and what `@nx/docker`'s inferred `dependsOn: ['build', '^build']` already asks for.

**THE BUILD IS `@nx/docker`, NOT A HAND-WRITTEN `docker build`.** The plugin discovers `**/Dockerfile`
— the **exact** name, and `dirname()` of it becomes a project root, which is why the two Dockerfiles sit
at `apps/posts-api/` and `apps/tagging/` and not under `src/main/docker/`. It infers `docker:build` and
`docker:run`, and the only per-project override is the tag (`--tag axonposts/<app>:e2e`), because the
plugin's default is the path-derived `apps-posts-api`.

Three things about it that are worth knowing before touching this:

1. **it is marked Experimental** — *"Breaking changes may occur and not adhere to semver versioning"*;
2. **the inferred target has no `outputs` and no `cache`** (checked in `dist/src/plugins/plugin.js`).
   Nothing about the image is cached by Nx; Docker's layer cache does all of it, and the
   `.dockerignore` in each app is what keeps the context to `target/quarkus-app` instead of the whole
   79 MB `target/`;
3. **`dependsOn: ['build', '^build']` is why each app has a `build` target.** It is
   `test -d target/quarkus-app || (cd ../.. && ./mvnw package -DskipTests)` — self-healing, and with
   `outputs: ["{projectRoot}/target/quarkus-app"]` so each project OWNS its artifact and Nx restores
   it. **Proven**: deleting both `target/quarkus-app` and running `pnpm test:e2e` restored them from
   the cache and passed. `apps/tagging:build` chains after `apps/posts-api:build` because one
   `./mvnw package` produces both, and `-pl` does not work in this reactor.

**AND THE JAVA `test:e2e` TARGETS DEPEND ON `docker:build`**, which looks arbitrary and is not: they run
`./mvnw clean`, and without that edge Nx is free to schedule the clean between `build` and
`docker:build`, leaving the image build with no context. Observed working by luck first, then written
into the graph so luck stops being involved.


**THE STACK OWNS ITS DATABASES.** `ChoreographyStack.up()` drops and recreates `axonposts` and
`axonposts_tagging` (`drop database ... with (force)`, then `create database`) before running Flyway, so
`migrate` always runs against an empty database. It used to create the tagging one only *if missing* and
migrate on top of whatever was there — which meant the schema, alone among everything this test touches,
was inherited from some earlier run instead of produced by this one.

It cost a real failure to find: with the comments stripped out of the migrations, `migrate` validated the
stored checksums, refused, and took the whole run down **before a single service started** — and the
message talked about checksums, not about the test. The data was never the problem; `reset()` already
truncated the read model, the event stores and the broker queues. **The schema history was the one piece
of state nobody reset.**

Recreating it is what makes the suite self-validating in the sense that matters: **nothing about the
result depends on what ran here before.** It is the same R-for-REPEATABLE that the
`db/init/schema.sql` episode broke from the other side, and it costs ~1 s on a run that already spends
30 s booting two JVMs.

**Proven in both directions**, which is the only way this kind of guard is worth anything: with every
checksum in both `flyway_schema_history` tables deliberately overwritten with `999999`, the suite passes
6/6 — and the tables come back holding the real checksums, because the databases they lived in are gone.
Before the change, that same state was the failure.

`reset()` stays. Its truncate is now redundant for the tables — it runs right after a fresh migrate — and
its `broker.deleteKnownQueues()` is not, because RabbitMQ is not recreated. The `POSTS_READ_MODEL` list it
carries is a hand-kept mirror of the schema and is the next thing that can rot; it survives only because
removing it would also stop truncating the default tag that `V5` seeds, and that changes what the test
starts from.

**Readiness is a strategy, not an `if`** (`support/service.ts`). `posts-api` has `/q/health`; `web`
answers `/login`; `tagging` has **no port at all** — `quarkus-opentelemetry` depends on `quarkus-vertx`
and not on `quarkus-vertx-http`, which is what allows instrumenting it without giving it an endpoint.
The only sign that it came up is the line in its log. Three answers to the same question is what makes
`HttpHealth`, `HttpAnswering` and `LogLine` three implementations of `Readiness`.

**`workers: 1` and `fullyParallel: false`**: the tests would fight over the SAME event store and the
same broker. Parallelism here speeds up nothing — it changes what is being measured. And `retries: 0`,
for the same reason: a test that only passes on the second attempt hides exactly what this app exists
to measure.

**`globalSetup` and `globalTeardown` run in the MAIN process**, which is what lets a module-level
holder (`support/running-stack.ts`) give the teardown the very child handle the setup created — a
second `ChoreographyStack` would have no handle for the Next server and would leave it holding the
port. The specs run in WORKER processes and never need it: they reach the durable state through their
own fixtures, over `docker exec` and the management API, which need nothing handed across a process
boundary — and that keeps working precisely because the container names and the published ports are
FIXED.

**An early death is reported, and it still matters.** A container is asked of `docker compose ps`
whether it is running and of `docker inspect` for its exit code; a spawned process is asked its
`exitCode`. Without it, something that dies at startup costs the full 180 s of the readiness wait to
say nothing.

**The capture is `retain-on-failure`, and what it leaves is the point.** Video and trace on failure,
screenshot on failure, and — under `CI` — the HTML report and the junit XML always. A green run writes
almost nothing; a red one writes everything about the test that went red. In CI those become three
artifacts (`playwright-report`, `playwright-artifacts`, `e2e-reports`) and the job writes a
`$GITHUB_STEP_SUMMARY` saying which is which, so the way in from a pull request is the checks tab and
not a guess. A trace opens at <https://trace.playwright.dev> with nothing installed.

- **Pure domain** (`PostTest`, `TagTest`, `SoftDeletableTest`, `AuthenticatableTest`): no Axon, no CDI,
  no JPA. The only collaborator is `RecordingDomainEvents`, a double of the domain's own port. These
  tests crossed the conversion **without a line changed**.
- **Command** (`*CommandTest`): `AxonTestFixture` given-when-then, one per command, with an in-memory
  repository — which is how you prove the command saved, and what.
- **Relay** (`ConnectionsTest`): cursor ↔ offset, type prefix, page ceiling and connection assembly. In
  the Spring project that part belonged to the framework; here it is our code, so here it has a test.
- **Schema** (`RelaySchemaTest`): reads the generated SDL and fails if `Connection_`/`Edge_` come back,
  or if the `interface User` disappears. It is the guard for the generics mechanism, which no compiler
  checks.
- **Axon wiring** (`AxonWiringTest`): three things the extension decides by default and that this project
  decides differently — the `TransactionManager`, each entity's id type, and the coverage of the
  processor properties (`subscribingprocessor.namespaces` plus the `pooledprocessor.<name>` ones). The
  first two hold by **absence** of `@DefaultBean` (disappearing breaks no compilation); the third sweeps
  the `BeanManager` for `@EventHandler` and fails if any package was left out of BOTH lists — the
  subscribing one and each named pooled one. Whoever is left out gets no error: it falls into an
  anonymous pooled processor, with a JPA token store, and the delivery that package needed silently stops
  applying.
- **Federation** (`FederationSchemaTest`, `FederationEntitiesE2ETest`): the first reads
  `_service { sdl }` — which is what `rover` would read, and where `@key`/`@shareable` appear, something
  introspection does not show. The second calls the real `_entities`: it is the only place where a
  renamed argument, an extra `@Id` or a `@NonNull` on the list element fails. It includes the cost, by
  the same property as `BatchLoadingE2ETest`: N representations have to cost the same statements as 1.
- **Cross-PROCESS, through a BROWSER** (`apps/web-e2e`, via `pnpm test:e2e`): brings up the
  infrastructure, runs the migrations out of process, builds and starts BOTH applications and the web
  client, and asserts the whole saga from the form — including that each service's event store holds
  exactly the expected events (which is what would catch a resend loop, as a growing count) and that
  redelivering the same message does not produce a second decision. It is deliberately outside
  Surefire: what it proves is what a `@QuarkusTest` cannot assemble — two processes, two event stores,
  one broker and a real client.
- **End-to-end** (`e2e/*`): Dev Services brings up Postgres and Keycloak; a single application is shared
  by every class. Each method starts with `truncate ... cascade` **including the Axon tables**: with a
  persistent event store, clearing only the read model leaves incoherent state — the tag's row
  disappears, the Tag aggregate's stream is still there, and the next `CreateTag` fails against an
  aggregate that exists in the store and not in the projection. A subclass that adds `@TestProfile` gets
  its own application and the suite pays for another startup.
- **`@QuarkusTest` goes on each concrete class**, not on the `AbstractGraphQlE2ETest` base: it is the
  annotation that registers the test class as a bean for its `@Inject` fields. Only on the base, every
  subclass fails with "No bean found for required type".
- The test realm is **the same file** compose mounts (`docker/keycloak/realm-*.json` lands on the
  classpath through the pom's `<resources>`, and `quarkus.keycloak.devservices.realm-path` points at it)
  — two copies would diverge silently.
- `BatchLoadingE2ETest` measures the batch through Hibernate statistics: the same query with 1 and with 5
  posts has to cost the **same number of statements** — the property, not a magic number.
- An assertion about a gateway exception walks the **chain** (`PostCommandFixtures.hasCause`), not the
  root: Quarkus delivered the raw exception where Spring delivered it wrapped — and with the extension it
  comes back wrapped in a `CommandExecutionException`. Walking the chain is what survives both shapes;
  AssertJ's `rootCause()` does not.

## CI (`tools/github/` and `.github/workflows/`)

**THE RULE: one main action, composed of subactions.** The subactions do one thing each; the workflows
know none of them, only the main one. Changing how a test runs means touching one file, and no workflow
knows it changed.

```
tools/github/
  setup/        Node, pnpm, JDK and the two caches (~/.m2 and .nx/cache)
  test/         `pnpm test` + the Surefire reports as an artifact
  test-e2e/     the browser, `pnpm test:e2e`, the Playwright VIDEOS/TRACES and the THREE apps' logs
  web/          lint, typecheck and build of the client, in a single `run-many`
  deploy-sst/   the `sst deploy` and the GitHub Deployment  ← the subaction that already existed
  ci/           the integration MAIN: `setup` + the requested checks
  deploy/       the deploy MAIN: `setup` + AWS credentials + `deploy-sst`
.github/workflows/
  ci.yml        three jobs, all three calling `ci` with a different `checks`
  deploy.yml    workflow_dispatch with the stage
```

**The `ci` action has a `checks` input, and it exists for a structural reason.** A composite action runs
in a single job — so a main action doing the three checks in sequence would put the web client's lint
behind a level that brings up Postgres, Keycloak, RabbitMQ and two JVMs. With `checks`, the SAME entry
point serves one job per check: parallelism in the workflow, and the environment preparation declared in
one place. `checks: all` also works, and it is what makes sense in a local hook.

The match uses commas on both ends (`,${checks},`) and not a raw `contains`: without them, `test` would
match inside `test-e2e` and the lower level would run along with the upper one, silently.

**CI's JDK is 25.** The project's `release` and CI setup target the current Java LTS. The historical
Quarkus 3.39/JDK 25 augmentation measurements above were made before this upgrade and must be
revalidated as part of it.

**`test-e2e` tears compose down at the end (`down -v`), and the local one does not.** On the machine the
containers stay up on purpose, so the next run does not pay for the startup; on an ephemeral runner that
is worth nothing, and a surviving volume between jobs would be worth less still.

**It installs the browser, and only chromium.** `npx playwright install --with-deps chromium` is the
first step: the runner has no browser and `playwright test` would die naming a download, not a defect.
`--with-deps` is what brings the shared libraries Chromium needs on a bare `ubuntu-latest`, and the
single browser is the whole `projects` list in `playwright.config.ts` — installing the other two would
be ~300 MB per run for something nothing drives.

**THREE ARTIFACTS, AND THE SPLIT IS WHAT MAKES THEM USABLE FROM A PULL REQUEST.**
`playwright-report` is the HTML report and is the one to open first; `playwright-artifacts` holds the
video, the screenshot and the trace of each test that FAILED (the config is `retain-on-failure`, so a
green run makes it tiny or empty); `e2e-reports` holds the junit XML, the Surefire reports and the
three applications' logs. A last step writes a `$GITHUB_STEP_SUMMARY` table naming them, because an
artifact nobody can find is an artifact nobody reads — and from a PR the way in is the checks tab, not
the repository. A trace opens at <https://trace.playwright.dev> with nothing installed.

**There is no packaging step before `deploy`.** Each function declares the Nx target that packages it
(`QuarkusBuild.buildCommand`, in `infra/aws/support/functions.ts`) and Pulumi's `triggers` decides
whether the command runs. A `package.sh` before the deploy would build OUTSIDE the graph, and SST would
rebuild anyway.

**And that is why the deploy RUNNER is `ubuntu-24.04-arm` and CI's is not.** What builds the four native
binaries is `sst deploy` itself, inside the job — so the runner's architecture IS the artifact's
architecture. The `ci.yml` jobs package nothing native and stay on `ubuntu-latest`. The full explanation
is in *THE NATIVE BINARY*; the short version is that an x86_64 runner produced an amd64 `bootstrap` for
functions declared `arm64`.

**The Better Stack credentials are mandatory at deploy**, and they go through `GITHUB_ENV` and not
through a step-level `env:`: that way every following step sees them, including those inside
`deploy-sst`. On the machine they come from the root `.env`, which SST loads on its own; on a runner
there is no `.env`, and `requiredEnv` FAILS the deploy — on purpose, because a collector with no
destination comes up, does not complain, and silently loses the telemetry.

Secrets `deploy.yml` expects: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `BETTER_STACK_URL` and
`BETTER_STACK_API_KEY`.

### THE LINT — and the TWO traps of a monorepo that is almost all Java

**EACH SYSTEM HAS ITS OWN**, and inheritance is an import. `eslint.base.config.mjs` at the root is what
everyone shares; each project has an `eslint.config.mjs` that starts with `...baseConfig` and adds only
what is its own. `pnpm lint` is `nx run-many -t lint` and **lists nobody** — whoever has the target is
whoever has the config.

| project | config | what it adds |
|---|---|---|
| `web` | `apps/web/eslint.config.mjs` | the Next preset, Vitest, Tailwind and GraphQL |
| `web-e2e` | `apps/web-e2e/eslint.config.mjs` | the Playwright rules |
| `infra` | `infra/eslint.config.mjs` | the two SST exceptions |
| `dev.manuelantunes:quarkus-axon-graphql-posts` | — | Spotless, across the EIGHT Maven modules |

**The shape comes from Nx, and it exists for a mechanical reason**: in flat config ESLint uses **ONE**
file, the closest one to the directory it runs from, and each `lint` target runs with `cwd` in its own
project. **There is no automatic inheritance between config files** — inheritance is the
`import baseConfig`.

What stays in the BASE is what cannot diverge: the Nx plugin, the global ignores,
`@nx/enforce-module-boundaries` (which reads the WHOLE workspace's graph, so writing it twice would be the
same rule in two places) and the three TypeScript rules that describe how this monorepo writes code.

**`infra` is an Nx project for ONE reason: to have its own lint.** Those are 22 TypeScript files that
belonged to no workspace package, and so were linted by nothing. `infra/project.json` exists only so
`@nx/eslint/plugin` can infer the target from `infra/eslint.config.mjs`.

**It cost TWO exceptions, and in both SST is right and the rule is wrong:**

- `triple-slash-reference` — those were **21 of the directory's 27** violations, the only systematic one.
  The `/// <reference path=".sst/platform/config.d.ts" />` is how SST brings its generated types into
  scope (`$config`, `$app`, `sst.aws.*`, the global `aws`). There is no equivalent import because there
  is no module: it is a declarations file. Swapping it for an `import` would leave the file WITH NO TYPES
  AT ALL;
- `no-empty-object-type` with `allowInterfaces: "with-single-extends"` — not disabled, CONFIGURED.
  `interface MigratorArgs extends Omit<QuarkusFunctionArgs, "timeout" | "memory"> {}` is the pattern for
  naming a derived type in a public API. The deprecated predecessor `no-empty-interface` left, because it
  flagged the same lines a second time.

Two warnings remain, both in infrastructure code: a `userGroup` assigned and never used
(`identity/index.ts`) and a `!` (`support/functions.ts`). They stayed as WARNINGS on purpose — they are
decisions by whoever wrote the infra, not by the lint.

**There is NO `lint` target at the root, and the absence is the design.** Nx's `root` project is the
**Maven reactor** (`@nx/maven` claims the root), not a JavaScript project — `nx show project root` lists
the 50 lifecycle phases. `nx run-many -t lint` resolves the projects on its own; a target at the root
would be a hand-written list saying what the graph already knows.

The consequence, and it is real: **`sst.config.ts` is linted by no target.** It has to live at the root
because that is where the SST CLI looks for it. The root `eslint.config.mjs` still exists — it is what
the editor resolves and what an `npx eslint sst.config.ts` uses — and it carries that file's exceptions,
including `enforce-module-boundaries` turned off: the `await import("./infra/aws")` IS a relative import
into another project, and it has to be, because the `infra/` modules create resources at the top and
importing them statically would evaluate them before `app()` ran.

**The plugin EXCLUDES the Maven modules, in writing.** It would not infer the target for a project
without a single `.ts`/`.js` anyway, but the `nx.json` `exclude` says so on purpose: an implicit
guarantee is a guarantee nobody reads until it breaks.

**`includedScripts: []` in the ROOT's `nx` block fixes a recursion, and it was observed.** The root is an
Nx project (`"nx": { "name": "root" }`), so each of its `package.json` scripts becomes a target —
including `lint`, which IS `nx run-many -t lint`. The result is `root:lint -> root:lint`, which Nx
detects and uses to fail the whole pipeline. `includedScripts: []` says what those scripts are: entry
points for people, not targets. The same applies to `dev`, `test` and `test:e2e` — all four are wrappers
around `nx run-many`.

**`enforce-module-boundaries` needs an `allow` for its own config.** `apps/web/eslint.config.mjs`
IMPORTS the root one — a relative import crossing a project boundary, which is exactly what the rule
forbids. Without the exception, composing the configs instead of duplicating them would be a lint error.

The `depConstraints` are a single permissive one, and that is deliberate: today there is no shared JS lib
in this monorepo — the shared domain is Java, and what separates it is the Maven reactor. A matrix of
`scope:`/`type:` would be a boundary drawn against a dependency that does not exist, and a rule that
never fires is a rule nobody maintains. When the first JS lib is born, that list is the place to tighten.

**`apps/web-e2e` has its OWN config**, and what it adds is not style. The
`eslint-plugin-playwright` rules turned on there catch the ways a suite can LIE that no compiler sees —
a `test` with no `expect`, a forgotten `test.only`, an `expect` outside a test, an `expect` under an
`if`. In an app whose whole job is asserting things about three processes and a broker, a lying suite
is worse than no suite: it stays green while the saga does not close. And `src/support/` is outside
those rules for the same reason it is outside `testDir` — that is mechanism, not spec.

**`no-conditional-in-test` is the one that earns its place here.** A browser suite is full of
temptations to branch on what the page happens to show, and a branch in a test is a test that asserts
different things on different runs — which in a system with a broker in the middle means it asserts
nothing on the run that matters.

**`src/fixtures/` turns OFF `no-empty-pattern`**, and that is not laziness: `async ({}, use)` is
Playwright's own signature for a fixture that depends on no other fixture. The destructuring has to be
there and has to be empty.

The `no-standalone-expect` rule caught a real one on the first run: there was an `expect` inside
`beforeAll`, which fails as a HOOK error and does not name what was expected. The fix was not silencing
the rule — it was making `PostsApi.subscribe` REFUSE a status other than 200, because a subscription that
did not open is not a subscription.

**The same six rules apply in `apps/web`**, over `src/**/*.spec.{ts,tsx}`. A unit spec lies in the same
way a saga one does.

#### TAILWIND and GRAPHQL in `web`: what WENT IN was MEASURED, as `importOrder` was

The two new families follow the rule this document already applies to Spotless and Error Prone — **what
points at a defect goes in; what only imposes a convention the code never had stays out** — and in
neither case was the line chosen by taste: Tailwind's 15 rules and GraphQL's 37 were run against the
app's 71 sources before any decision.

**Tailwind: `eslint-plugin-better-tailwindcss`, and not `eslint-plugin-tailwindcss`.** Both have a stable
version for v4 today, and the choice is about the configuration model: in v4 the config is the CSS ITSELF
(`@theme` inside `globals.css`) and there is no `tailwind.config.ts` in this project. That plugin takes
`entryPoint: "src/app/globals.css"` and reads the theme from there; the other one was born around the JS
file.

```
172  enforce-logical-properties        `mt-1` → `mbs-1`, `size-7` → `block-7 inline-7`    OUT
101  enforce-consistent-line-wrapping  a className FORMATTER                              OUT
 12  enforce-canonical-classes         `text-sm leading-relaxed` → `text-sm/relaxed`      OUT
  2  enforce-shorthand-classes         `-translate-x-1/2 -translate-y-1/2`                OUT
  1  enforce-consistent-class-order                                                       IN
  1  no-deprecated-classes             found `backdrop-blur` in the site-header           IN
  1  no-unknown-classes                found `toaster`, which belongs to sonner           IN
```

The top four are the `importOrder` case again: with no convention to preserve, the rule fixes nothing —
it PICKS one and rewrites almost everything to impose it. `enforce-logical-properties` is the extreme: it
swaps Tailwind's vocabulary for one nobody here reads, to solve a problem (RTL) this application does not
have.

What went in is the plugin's own `correctness` — a non-existent class, a class fighting another, a class
assembled by CONCATENATION (which Tailwind does not extract and therefore prunes from the CSS) — plus the
three that cost one occurrence each. And they paid their way in: **`backdrop-blur` is DEPRECATED in v4**
(the blur scale was renamed, v3's `blur` became `blur-sm`), and it was in every page's header. The
`toaster` is the only exception, nominal on purpose: it is a sonner class, applied by the library in its
own CSS — a broad `ignore` there would switch off the protection against a typo, which is what the rule
exists to give.

**GraphQL: `@graphql-eslint/eslint-plugin`, in two blocks.** The first puts the `processor` over the
`.ts`/`.tsx` files — it runs `graphql-tag-pluck` and hands back whatever it finds as VIRTUAL `.graphql`
files. That works with the `client-preset` because pluck recognizes `` graphql(`...`) `` as a CALL, and
not only `` gql`...` `` as a tag. The second block lints those documents against `schema.graphql` — the
SAME file codegen reads, so lint and generated types do not diverge.

`operations-recommended` and not `operations-all`, and again by measurement: the five rules that exist
only in `all` produced 45 of the 57 occurrences, and the three biggest FIGHT the design
`apps/web/README.md` documents — `require-import-fragment` (18) wants `#import` comments, which belong to
the `.graphql`-files workflow and not to the `client-preset`; `no-one-place-fragments` (2) wants to inline
a fragment used once, when fragment masking's whole promise is that the COMPONENT owns what it asks for;
and `alphabetize` (25) reorders the selection, which here is read in the order the screen shows it.

**And it paid its way in too: `require-selections` found `TagList_post` reading a `Post` without asking
for `id`.** The Apollo cache normalizes `Post` by `keyFields: ["id"]` — a fragment like that is not
self-sufficient, and the symptom would be the same post becoming two objects in the cache, silently.

**Two rules from the preset were CONFIGURED instead of disabled**, and both for the same reason — they
were right about the wrong code:

- **`naming-convention` rejected all nine fragments** (`PostCard_post`, `TagList_post`…) for not being
  `PascalCase`. But that name is the `client-preset` convention, `<Component>_<prop>`, and it is what ties
  the fragment to the component declaring it. Swapping `style` for a `requiredPattern` makes the rule stop
  fighting the convention and start REQUIRING it;
- **`allowLeadingUnderscore`**, because `_entities` and `_Entity` are reserved names from the Apollo
  Federation specification. Forbidding the underscore would be forbidding talking to a subgraph.

And `schema.graphql` goes into `ignores`: it is a SCHEMA, the rules are about OPERATIONS, and without that
line `executable-definitions` flags each of its `type` declarations — 50 errors saying a type definition
is not executable, which is true and is not a defect. It is not even written here: it comes out of the API
through `pnpm schema:pull`.

#### `consistent-type-definitions` is AUTO-FIXABLE and the auto-fix BROKE the build

The base rule requires `interface` instead of `type` for an object type, and `--fix` converted 11
declarations at once. One of them could not be converted, and the compiler is what said so:

```
entities-probe.tsx(65,39): error TS2352: Conversion of type 'Representation[]' to type
'Record<string, unknown>[]' may be a mistake because neither type sufficiently overlaps
```

**An object type alias gets an IMPLICIT INDEX SIGNATURE; an interface does not.** That is why
`Representation[]` stopped being assignable to `Record<string, unknown>[]` — which is how the
representations reach `_entities`. The file went back to `type`, with an `eslint-disable-next-line` and
the reason written next to it.

The lesson is about ORDER, and it holds for any `--fix`: **running the typecheck after an automatic fix is
not diligence, it is part of the fix.** Here the lint went green and the build broke.

#### `pnpm lint` checks, `pnpm lint:fix` fixes — and `--fix` is NOT the interface

`lint:fix` is `nx run-many -t lint -c fix --skip-nx-cache`: a `fix` configuration on each of the three
targets, `eslint . --fix` on the two JavaScript ones and `spotless:apply` on the Java one.

**`pnpm lint --fix` was deliberately disabled, and the reason is the usual one here: it worked HALFWAY.**
`nx:run-commands`'s `forwardAllArgs` is `true` by default, so the flag was passed through raw — ESLint
understood it and fixed, `./mvnw` did not and answered with its help screen plus exit code 1. With
`forwardAllArgs: false` on all three, `--fix` becomes ignored everywhere, uniformly, and the one that
fixes is `lint:fix`.

**The ESLint targets' `fix` configuration repeats the command instead of using the `args` option**, and
that was measured too: `forwardAllArgs: false` blocks `args` along with it, so the configuration ran
without the `--fix` and **said it had passed**. Locked down by a manual test with a planted violation on
both sides — a `let` that should be `const` in TypeScript and trailing whitespace in Java.

#### PRETTIER, and `.editorconfig` as the single source of indentation

**This section used to say "there is no Prettier", and the decision CHANGED.** The old argument was that
`.editorconfig` already declared the convention — and the problem was that it declared it and nobody
applied it: of the 51 TS/TSX files, **43 were on 4 spaces** against a file asking for 2.

**`.editorconfig` was not working, and there were THREE independent causes:**

1. **It described what does not exist.** The `[*]` block with `indent_size = 2` also applied to the 153
   Java files and the 12 `pom.xml` files, which are on 4. An editor faced with a configuration that
   contradicts the content resolves it on its own — and resolves it by GUESSING. Today there are
   `[*.{java,xml}]` and `[*.sql]` blocks with 4, and `[*]` now describes what is left;
2. **VSCode does not read `.editorconfig` natively.** That is the `EditorConfig.EditorConfig` extension,
   and it is now in `.vscode/extensions.json`;
3. **`editor.detectIndentation` comes ON by default**, and it guesses the indentation from the start of
   the file, overriding any configuration. That was the direct origin of the symptom. It is turned off in
   `.vscode/settings.json`.

The two files in `.vscode/` are committed by an explicit exception in `.gitignore` — they are PROJECT
configuration, and without them the convention simply does not apply to whoever clones. The rest of
`.vscode/` stays ignored, like `.idea/`.

**Prettier reads `.editorconfig` by default, and that is what ties the two together.** Measured: with
`indent_size = 8` there, Prettier indents 8. That is why `.prettierrc.mjs` **does not declare
`tabWidth`** — the indentation is stated once, in the file the editor also reads.

What `.prettierrc.mjs` does declare is what `.editorconfig` cannot say: single quotes,
`@ianvs/prettier-plugin-sort-imports` with this monorepo's groups (React/Next → third party → `@/` →
relative) and `prettier-plugin-tailwindcss` pointing `tailwindStylesheet` at the **same** `globals.css`
the Tailwind ESLint plugin uses as its `entryPoint`.

**Two plugins from the original config were removed by measurement, not by taste**: `prettier-plugin-embed`
(with `embeddedGraphqlIdentifiers`) produced output **byte for byte identical** to Prettier alone — it
already formats the GraphQL inside `` graphql(`...`) `` — and `prettier-plugin-sql` came only as its
dependency, for embedded SQL that does not exist in this repository.

**What stayed OUT of Prettier, and why:**

| | why |
|---|---|
| `**/db/migration/**` | Flyway stores the CONTENT's CHECKSUM; reformatting an applied migration brings the application down at startup, in every environment where it has already run |
| `*.md` | `.editorconfig` itself turns off `max_line_length` and `trim_trailing_whitespace` there. Measured: 144 lines in the root README alone, almost all tables expanded to 150 columns — and `CLAUDE.md` would fall into the same bucket |
| Java and `pom.xml` | Prettier speaks neither. See *THE JAVA SIDE*, just below: there the absence of a formatter is still a measured decision |

The first pass reformatted **148 files**. It invalidates the Nx cache of almost everything, including the
four Lambda artifacts — `infra/lambda/collector.yaml` counted, and it is a `quarkusLambda` input. It is a
one-time cost.

`pnpm lint` now checks the formatting along with it (`prettier --check .` after the `run-many`), and
`pnpm lint:fix` fixes it. `pnpm format` and `pnpm format:check` exist to run only that half. In CI the one
checking is the client job, in the first step — `--check` and never `--write`, because in a pipeline
formatting is hiding.

### THE JAVA SIDE: Spotless for what is fixable, Error Prone for the defect

This file used to say "there is no lint/format plugin configured" and that the gate was the compiler. It
still is — only now the compiler knows more.

**That is the split, and it decides where each thing fails:**

| | what it catches | how it is fixed | where it fails |
|---|---|---|---|
| **Spotless** | a dead import, trailing whitespace, a final newline | `./mvnw spotless:apply` | the `lint` target |
| **Error Prone** | a code defect: `equals`, `Locale`, `String.split` | by reading the code | the BUILD |

What a machine can fix does not need to break a build; what requires somebody to read, does.

**THERE IS NO FORMATTER ON THE JAVA SIDE, and the absence was measured.** 227 files, 4-space indentation,
**ten** lines over 120 columns: the code is already formatted. A `google-java-format` or a `palantir`
would rewrite all 227 to impose its own ruler, rewrapping the long Javadoc and taking `git blame` with it.

**This is NOT contradicted by Prettier on the JavaScript side**, and the difference is the starting state:
there, 43 of the 51 TS files contradicted the declared convention, so there was something to fix; here the
227 already follow it. Prettier does not speak Java either — which is why what describes the 4-space ruler
on that side is `.editorconfig`'s `[*.{java,xml}]` block, and nothing else.

**There is also no `importOrder`, for the opposite reason.** It was turned on, measured and turned off:
the import group order **is not consistent** in this code — some files start with `dev`, others with
`java`, others with `org`. With no convention to preserve, the rule would not be fixing anything, it would
be PICKING one and rewriting almost everything to impose it.

What was left cost **5 lines**: five unused imports, in three files.

**Spotless has NO `<executions>`**, and that has two effects at once: `package` and `test` do not pay for
it, and `@nx/maven` does not start inferring a target per mojo execution — which is the
`nx-build-state.json` trap.

**The Java `lint` target lives in `apps/posts-api` and covers the WHOLE REACTOR.** It is the same shape as
`test-unit`: `-pl` does not work in this reactor, so every Java-side target runs from the root. Having the
SAME NAME as the ESLint targets is what makes `pnpm lint` cover the monorepo in one invocation.

**The THREE Error Prone traps, and all three were paid for here:**

1. **A child's `annotationProcessorPaths` REPLACES the parent's, it does not add.** `apps/posts-api`
   (MapStruct) and `axon-native-support/deployment` (the extension's processor) declare their own, so they
   inherited the `-Xplugin:ErrorProne` from `compilerArgs` and lost the jar that implements it:
   `plug-in not found: ErrorProne`, in the middle of the reactor. The fix is
   `combine.children="append"` on both.
2. **JDK 16+ requires `add-exports`/`add-opens` for javac's internals**, and they belong to the process
   that RUNS javac — which is why they are in `.mvn/jvm.config` and not in the pom. Without them the
   compiler dies before compiling the first file.
3. **`XDcompilePolicy=simple` and `should-stop=ifError=FLOW` are not tuning**: without them javac runs the
   plugin under an incompatible policy and Error Prone does not even load.

**THREE CHECKS TURNED OFF, and all three are about Javadoc.** Of the 17 findings from the first full
reactor, **8 were theirs**: `InvalidParam` (a false positive — in `Post.java` it flags `{@code authors}`
as the `author` parameter misspelled, when `authors` there is the TABLE name), `EscapedEntity` (flags
`<b>` inside `{@code}`, which is how the prose here was written) and `MissingSummary` (requires a summary
sentence; the Javadoc here opened with the decision's context). A lint that shouts where there is no
defect is a lint you learn to ignore ENTIRELY. **These three notes are kept for the record: the Javadoc
they were about no longer exists — see *Comments: do not write them* at the top of this file.**

**What was left are 9 warnings, all about CODE**, and they stay as warnings on purpose — making them
errors would break the build today, and the decision to fix each one belongs to whoever knows the intent:

```
6  MissingOverride         an implementation with no @Override
2  StringCaseLocaleUsage   toLowerCase() with no Locale — breaks in Turkish, and one of them is Email
1  StringSplitter          String.split(String) has surprising behaviour
```

## Conventions

- **No comments.** See *Comments: do not write them* at the top of this file. The *why* of a decision
  goes into this document or into the README, never into the code.
- The README is the architecture decisions document — when a decision changes, update it along with the
  code. It is written in English, as is every document in this repository.
- Commit messages in English, conventional-commits style (`feat:`, `chore:`).
- Named constructors instead of a public `new`: `PostId.of(...)` / `PostId.newId()`,
  `PostVersion.initial()` / `next()`, `Post.create(...)`,
  `AppendingDomainEventPublisher.appendingTo(...)`.
- MapStruct is the default mapper in every layer; hand mapping only when MapStruct cannot do it
  (`PostViewMapper` needs `expression = "java(...)"` because the entity has `title()` accessors, not
  `getTitle()`; `UserViewMapper` is by hand because the destination depends on the runtime type). No
  `@Mapper` declares `componentModel`: `pom.xml` passes
  `-Amapstruct.defaultComponentModel=jakarta-cdi` to all of them.
- **Constructor injection, with no `@Inject`**: ArC uses the single constructor with parameters. That is
  what keeps the application classes identical to the Spring version's.
- The message's name on the wire comes from the annotation (`@Command(namespace, name, version)`), not
  from the class — moving or renaming the Java class does not change the contract; touching the
  annotation does.

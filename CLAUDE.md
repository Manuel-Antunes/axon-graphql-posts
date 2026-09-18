# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Forma do repositório

Monorepo de dois apps sobre módulos compartilhados. A regra que decide onde tudo mora:

> **`libs/` tem domínio e infraestrutura. `apps/` tem aplicação e apresentação.**

A linha não é entre serviços, é entre **camadas** — e é isso que torna um módulo reutilizável. Domínio é
regra e infraestrutura é como a regra persiste: as duas são do módulo (`posts`, `users`), e mais de um
app as importa. Aplicação é fluxo — qual command existe, qual query responde o quê — e fluxo é de quem o
executa.

```
libs/platform          domain/shared + infrastructure/{axon,time}
libs/users             domain/user/**        + infrastructure/persistence/user
libs/posts             domain/{post,tag}/**  + infrastructure/persistence/post
libs/axon-channels     a integração Axon ↔ channels (outbox + ingestão)
libs/axon-native-support  a extensão de build para GraalVM native

apps/posts-api         application/** + interfaces/{graphql,messaging}/** + infrastructure/security
apps/tagging           o serviço de tagueamento: application/** + interfaces/messaging/**
```

O que isso resolve: `apps/tagging` importa `libs/posts` e ganha o `Post`, os eventos, as regras e os
repositórios. **Não** ganha o GraphQL, a projeção nem os command handlers do outro app — que o Axon
descobre em build time e ligaria contra tabelas que ele não tem.

`apps/posts-api` é o único com `infrastructure/`: `CurrentUser`/`SecurityProducer` são OIDC e HTTP, que
só ele tem — e `CurrentUser` depende de `UserProvisioning`, que é aplicação. Na lib, viraria ciclo.

## Comandos

Em dev e em teste **não é preciso subir nada**: o Dev Services do Quarkus levanta Postgres e Keycloak
(com o realm importado) sozinho. Basta o Docker ligado.

**`-pl <módulo>` NÃO funciona para os goals de BUILD**, com ou sem `-am`: o
`quarkus-extension-maven-plugin` do `axon-native-support` valida que o artefato de deployment está no
reator, e um build parcial o deixa de fora — `Deployment artifact ... is missing the following
dependencies`. Para compilar, empacotar ou testar, rode da raiz e filtre com `-Dtest=`.

**Para `quarkus:dev` o `-pl` funciona, e é o jeito certo.** O goal não constrói a extensão — ele a
resolve do `~/.m2` e descobre as libs pelo workspace do reator —, então a validação nem roda. Sem `-pl`
o Maven percorreria os oito módulos em série para subir duas aplicações. É o que `pnpm dev` usa.

```bash
pnpm dev                       # as DUAS aplicações em dev mode, em paralelo — ver a seção abaixo
./mvnw install -DskipTests -pl '!apps/posts-api,!apps/tagging'   # as libs no ~/.m2 (ver abaixo)
./mvnw quarkus:dev -pl apps/posts-api   # uma aplicação só
./mvnw test                    # suíte inteira — EXIGE Docker
./mvnw package                 # build + testes
./docker/e2e/run.sh            # a saga entre DOIS processos, com broker de verdade
./mvnw test -Dtest=PostTest                                       # uma classe
./mvnw test -Dtest=PostLifecycleE2ETest#aNewPostArrivesAlreadyTaggedAtVersionTwo   # um método
./mvnw test -Dtest='*E2ETest'                                     # só os ponta a ponta
open target/jacoco-report/index.html   # cobertura — o quarkus-jacoco roda junto com `test`
open http://localhost:3001     # Grafana do Dev Services: traces, logs e métricas DOS DOIS serviços
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

### `pnpm dev`: o nx roda os processos, o Maven resolve os módulos

`pnpm dev` = `nx run-many --target serve`. O target `serve` de cada app é um `nx:run-commands`
declarado em `apps/*/project.json`, e tudo que ele faz é `./mvnw quarkus:dev -pl apps/<app> -Ddebug=<porta>`.
O nx aqui é **só o executor paralelo de dois processos contínuos**; quem resolve dependência entre módulos
continua sendo o reator do Maven.

**As libs precisam estar instaladas no `~/.m2`, e o `pnpm dev` não as instala.** Com `-pl` e sem `-am`, o
Maven resolve `axonposts-platform` e companhia do repositório local — então código novo numa lib (e agora
há código lá: `AxonMetrics`) só chega às aplicações depois de um
`./mvnw install -DskipTests -pl '!apps/posts-api,!apps/tagging'`. O filtro é por exclusão e não por
enumeração: mantém os DOIS módulos do `axon-native-support` no reator, que é o que a validação da extensão
exige, não precisa ser editado quando uma lib nova entra, e pular as aplicações evita o `quarkus:build`
delas — que é justamente o passo intermitente documentado mais abaixo.

Provado de ponta a ponta: as duas aplicações sobem juntas, o Dev Services dá **um Postgres para cada uma**
(event store próprio, como o desenho exige) e **um RabbitMQ, um Keycloak e um LGTM para as duas**, e a
saga atravessa — o post nasce na versão 1 sem tag e chega à 2 com a `Untagged` decidida pelo outro
processo, num único trace com os dois `service.name` dentro (`http://localhost:3001`, ver
*Observabilidade* mais abaixo).

Duas colisões entre os dois processos, e as duas foram observadas de verdade:

1. **A porta do debugger.** O `quarkus:dev` abre JDWP na 5005 por default, e os dois disputam. Daí o
   `-Ddebug=5005` e `-Ddebug=5006` nos `project.json`. Sem isso o segundo a subir morre com
   `transport error 202: bind failed: Address already in use` — e é INTERMITENTE, porque depende de quem
   chegou primeiro: três execuções passaram antes de a quarta falhar.
2. **A descoberta dos Dev Services COMPARTILHADOS é uma corrida, e esta ainda está aberta.** Container
   compartilhado (RabbitMQ, LGTM, Keycloak) é achado por LABEL: quem sobe primeiro cria, quem chega
   depois reusa. Partindo juntos, os dois podem criar antes de o outro estar rotulado — e foi o que
   aconteceu numa execução: **dois** RabbitMQ (cada serviço num broker, a saga MUDA), e o `posts-api`
   morrendo em
   `Bind for 0.0.0.0:3001 failed: port is already allocated` ao tentar criar um segundo LGTM.
   O `grafana-port` fixo transforma a falha silenciosa (duas stacks, telemetria partida) numa falha
   alta — o que é melhor, mas não é conserto.
   **Contorno que funciona, medido:** subir em série, `apps/tagging` primeiro e o `posts-api` depois de
   ele estar no ar. Aí a topologia sai certa toda vez. Consertar de verdade é fazer os serviços
   compartilhados existirem ANTES das aplicações — o candidato é o Dev Services de Compose, que este
   projeto já tem no classpath (`compose` aparece nas *Installed features* dos dois apps) e não usa.

**NÃO usar o target `quarkus:dev` que o `@nx/maven` infere.** Ele não funciona, por duas razões
independentes, as duas medidas na versão 23.2.1 (a mais recente) e nenhuma delas configurável:

1. **O plugin decompõe o ciclo de vida do Maven em um target por execução de mojo**, então `package` roda
   `jar:jar@default-jar` sozinho. Como o mesmo plugin também restaura `target/nx-build-state.json` — que
   grava `mainArtifact.file` — o mojo encontra o artefato JÁ anexado ao projeto e aborta com
   `You have to use a classifier to attach supplemental artifacts to the project instead of replacing
   them`. Passa uma vez depois de um `clean` e falha em TODAS as seguintes; apagar o
   `nx-build-state.json` conserta aquela execução e a próxima o regrava. Como todo target inferido
   depende de `^install`, qualquer target do plugin cai nisso. Os targets `*-ci` rodam o mesmo mojo e
   têm o mesmo defeito.
2. **Os goals rodam num Maven RESIDENTE, em processo**, e o `quarkus:dev` precisa de um CLI de verdade:
   ali ele morre com `Cannot invoke "String.toLowerCase(java.util.Locale)" because "version" is null`.
   O mesmo goal pelo `./mvnw` sobe normalmente.

Duas linhas saíram do `nx.json` junto, e as duas eram armadilha: o `targetDefaults.build` apontava para
um target que **não existe** neste workspace (o `@nx/maven` infere fases, não `build`), e o
`targetDefaults.test` sobrescrevia o `dependsOn` inferido por esse mesmo `build` inexistente —
`targetDefaults` tem precedência sobre target inferido por plugin, então `nx test` rodava o surefire
**sem compilar nada antes**, calado. Quem roda a suíte é `./mvnw test`, da raiz, como sempre.

`quarkus:dev` recarrega sozinho na próxima requisição depois de uma classe mudar.
O event store é **persistente** desde que a saga passou a ser coreografada: recarga não apaga mais nada.
Um post criado antes da recarga segue respondendo em `post(id:)` e passa a dar `NOT_FOUND` no
`updatePost`, que reidrata o agregado do stream.

**O BUILD DO `posts-api` É INTERMITENTE, e a causa não está no projeto.** Em cerca de metade das
execuções IDÊNTICAS o augmentation do Quarkus não encontra classes que estão em `target/classes`:

```
Unsatisfied dependency for type ...PostViewMapper
Producer method return type not found in index: PostInputMapper
Could not load class with name: ...FindAllPostsQueryTest      (e com ele os 149 testes)
```

Em todas as vezes os `.class` existem, estão corretos e (quando gerados) devidamente anotados. **Basta
repetir o comando.** Só apareceu quando a camada de aplicação veio para o app, trazendo o processador de
anotação do MapStruct com ela — em `libs/` isso nunca aconteceu.

**Onde dói e onde não:** `./mvnw clean test` passa (o `test` não roda `quarkus:build`). Quem falha é o
`package` — e portanto o `docker/e2e/run.sh`, que empacota antes de subir as aplicações.

Descartados por medição: estado sujo em `target/`, snapshots instalados no `~/.m2`, índice Jandex
desatualizado nas libs, `quarkus.arc.exclude-types`, teste nomeando classe gerada, opções do processador
na execução vs no plugin, `<proc>none</proc>` no round de teste, `useIncrementalCompilation=false`,
índice Jandex no app, `quarkus.builder.parallel=false` (o augmentation sequencial não conserta) e
produtores de bean escritos à mão em vez do `componentModel` — com eles a falha deixa de ser intermitente
e passa a ser **determinística** (`Producer method return type not found in index`), o que é pior. Daí a
configuração atual ser a convencional.

**Suspeita principal: o JDK.** A JVM aqui é a **25**, e o Quarkus 3.39 não a suporta (`release` é 21).
Não foi possível confirmar nesta máquina — só há JDK 25, 17 e 8 instalados, e 17 não compila `release 21`.
O próximo passo é rodar num JDK 21.

**Duas regras que ficaram do episódio**, e as duas valem por si:

1. **As opções do processador ficam no nível do PLUGIN**, não numa `<execution>`. Presas ao
   `default-compile`, o round de teste regerava os impls sem elas — sem `@ApplicationScoped` — e o que
   sobrava em `target/` dependia de quem escreveu por último.
2. **Nenhum teste nomeia uma classe gerada** (`*MapperImpl`) nem depende do bean dela. Quem precisar de
   um mapper num teste de unidade usa um dublo local; quem exercita o mapper de verdade é a suíte ponta a
   ponta, pela borda GraphQL.

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
subscriptions sobre WebSocket. Event store **persistente no Postgres**, com token store; Keycloak é o
provedor de identidade e a aplicação é apenas resource server. **Dois serviços** conversam por RabbitMQ
numa saga coreografada.

**Uma tag por evento, e isso é do framework.** O Axon 5.3.1 tem exatamente dois `EventStorageEngine`:
`InMemoryEventStorageEngine`, com DCB completo, e `AggregateBasedJpaEventStorageEngine`, que é o modo de
compatibilidade com o Axon 4 — uma tag por evento, e a query de sourcing filtra só por
`aggregateIdentifier` (o `aggregateType` nem entra). DCB de verdade em armazenamento relacional não
existe fora do Axon Server. Foi a troca: durabilidade custou o segundo `@EventTag` dos eventos de post.

É a conversão de um projeto Spring Boot — o README é o documento dessa conversão, decisão por decisão.
**Ao mudar uma decisão, atualizá-lo junto.**

### O ciclo de vida de um post tem DUAS fases

`PostPreCreated` = o post existe. `PostCreated` = o post está **completo** (tem a primeira tag) e
visível. Nasce na versão 1, chega à 2.

Existem duas fases porque a primeira tag deixou de ser decidida aqui: quem decide é **outro serviço**, e
a mensagem atravessa um broker. Fingir que criar e publicar são o mesmo instante exigiria esperar o
vizinho dentro da transação de escrita.

```
@Mutation createPost
  → commandGateway.send(CreatePost)
     → Post.create(...)  → PostPreCreated          [v1, sem tag]   → posts.save(post)
  → a mutation responde v1                          ~~~ RabbitMQ: posts.PostPreCreated.<postId> ~~~

                                            apps/tagging
                                              → ChannelEventInbox APENDA no event store dele
                                              → CompleteOnPostPreCreated → CompletePostWithDefaultTag
                                              → Post.complete(...) → PostCreated  [v2, com a tag]
  ~~~ RabbitMQ: posts.PostCreated.<postId> ~~~

  → ChannelEventInbox APENDA no event store daqui (lendo o stream antes, pela sequência)
  → PostCreatedEventHandler materializa a linha e emite onPostCreated no commit da transação
```

Nenhum dos dois serviços nomeia o outro: um publica `posts.PostPreCreated` e escuta `posts.PostCreated`,
o outro faz o inverso. Trocar o serviço de tagueamento é trocar quem responde àquela routing key.

**Em teste a decisão é dublada em processo** (`InProcessTagAssignment`, removido do build em dev/prod
pelo `@IfBuildProperty`), porque consistência eventual faz mensagem em voo cruzar a fronteira do
`truncate` entre testes. O caminho real é coberto por `docker/e2e/run.sh`, fora do Surefire.

### A integração Axon ↔ channels, nas duas direções

- **saída**: todo evento apendado é oferecido, depois do commit, aos **outboxes deste serviço**, por um
  `MessageDispatchInterceptor`. Genérico — nenhum tipo de evento é citado em código. A routing key sai do
  `@Event` + `@EventTag`: `namespace.Name.tagDoAgregado`.
- **entrada**: toda mensagem recebida é **apendada no event store local**, e é o store — não a fila —
  que alimenta os event processors. O broker é transporte; o Axon funciona como em qualquer aplicação
  sem mensageria, com token, replay e durabilidade.

**UM CANAL POR DESTINO, e a saída deixou de ter um hub.** Era um canal só, `axon-events`, por onde todo
evento passava — um ponto central numa saga que se diz coreografada, e a razão pela qual "parte em Kafka,
parte em RabbitMQ" não era exprimível: conector é atributo do canal, e só havia um canal.

#### A regra que decide onde cada coisa é declarada

> **O código diz O QUÊ sai. A configuração diz PARA ONDE.**

Houve uma versão intermediária com o seletor no `application.properties`
(`axonposts.messaging.outbox.<canal>.events=posts.*`), escrita para espelhar o `routing-keys` da entrada.
A simetria era aparente: na **entrada** o seletor é mesmo configuração, porque anda junto com nome de
fila e binding, que mudam por ambiente; na **saída** não muda por ambiente nunca — o que um serviço
publica é contrato dele, e contrato em `.properties` se altera sem passar por revisão de código.

E houve uma versão com uma `interface AxonOutbox` de três métodos, implementada por um bean em cada
serviço. Ela dizia os mesmos dois fatos em uma classe, com o nome do canal escrito duas vezes e sem nada
conferindo. O qualifier diz o mesmo em duas linhas, e a conferência passou a existir.

#### A saída, peça por peça

| peça | onde | o que decide |
|---|---|---|
| `EventAddress` | lib | lê o evento UMA vez: nome qualificado, namespace, id e chave de ordenação |
| `OutboxRouting` | lib | qual outbox recebe qual evento, por namespace; valida a fiação |
| `@AxonOutbox` | **qualifier da lib, usado na aplicação** | o canal e os **namespaces** que saem por ele |
| `ChannelAddressing` | lib, uma por conector | como aquele broker endereça (routing key, record key) |

**OUTBOX NOVO = DUAS COISAS:**

1. um produtor de `Emitter` em `infrastructure/outbox/` da aplicação — uma declaração, os dois fatos:

```java
static final String CHANNEL = "post-events-out";

@Produces @Singleton
@AxonOutbox(channel = CHANNEL, namespaces = "posts")
Emitter<AxonEventEnvelope> postEvents(@Channel(CHANNEL) Emitter<AxonEventEnvelope> channel) {
    return channel;
}
```

2. o bloco `mp.messaging.outgoing.<canal>.*`: conector, exchange/tópico. Nada sobre *o que* sai.

**Três coisas do CDI que decidiram essa forma, e as três foram medidas:**

- **`@AxonOutbox` não pode ir no campo injetado**, ao lado do `@Channel`. Qualifier num ponto de injeção
  exige um bean com *todos* os qualifiers dali, e o emitter de `@Channel` é um bean sintético do Quarkus
  que só tem o `@Channel`. A lib também não pode oferecer esse bean: um produtor que casasse com qualquer
  canal precisaria de `@Channel` com `value()` `@Nonbinding`, e ele é **binding** — é o que distingue um
  canal do outro. Num produtor a colisão some.
- **O nome do canal aparece duas vezes** porque não há de onde lê-lo uma vez só: o ArC devolve
  `Bean#getInjectionPoints()` **vazio** para produtores (medido: `injectionPoints=[]`), então o `@Channel`
  do parâmetro é invisível em runtime. O que impede a divergência é `OutboxRouting`, que confere o emitter
  produzido contra o que o `ChannelRegistry` tem sob aquele nome.
- **A lib coleta com `@AxonOutbox Instance<Object>`**, e não `Instance<Emitter<…>>`: o Quarkus valida todo
  ponto de injeção cujo tipo requerido seja `Emitter` e exige `@Channel` nele —
  `Invalid emitter injection - @Channel is required for parameter 'outboxes'`. `Object` escapa da
  validação; o elenco é conferido na coleta.

Produtor declarando canal que o SmallRye não ligou **derruba a resolução da tabela**, com o nome do canal
no erro. O inverso — bloco de canal sem produtor — não é detectável, porque nem todo canal outgoing
precisa ser um outbox do Axon; o sinal dele é o `has no downstream` do SmallRye na partida.

Evento que casa com vários outboxes sai em todos — é o que mantém "tudo num barramento de auditoria e só
os posts no broker" exprimível com dois beans. Evento que não casa com nenhum não sai, e isso é o desenho:
o event store continua sendo o log durável, e o que não foi publicado pode ser republicado.

**O limite conhecido:** a granularidade é o namespace, então não dá para mandar `posts.PostCreated` a um
destino e `posts.PostUpdated` a outro. O dia em que for preciso, o lugar de resolver é a porta
`AxonOutbox` — um método a mais —, não um arquivo de propriedades.

**Protocolo novo = uma `ChannelAddressing` a mais**, declarando o `connector()` que ela atende
(`smallrye-kafka`, `smallrye-pulsar`…). Ela **não** substitui a de RabbitMQ: as duas convivem, e quem
escolhe entre elas é o `mp.messaging.outgoing.<canal>.connector` daquele canal. Conector sem
`ChannelAddressing` derruba a resolução — sem endereçamento a mensagem sairia sem routing key e o
exchange a descartaria sem uma linha no log.

**A chave de ordenação NÃO é mais configurada.** Havia `axonposts.messaging.ordering-tag-keys=postId,…`
nos dois serviços, e os dois `application.properties` já admitiam por escrito que a lista não desempatava
nada: o `AggregateBasedJpaEventStorageEngine` aceita **uma tag por evento**. Hoje a chave é a tag do
evento, lida do evento (`EventAddress`), e duas tags produzem `WARN` em vez de escolha alfabética calada.
Quem trava as três regras é `OutboxRoutingTest`.

Três guardas independentes contra execução duplicada, e cada uma cobre o que a outra não cobre: a
**marca de origem** na metadata descarta o eco do próprio serviço (e corta o laço de reenvio); o
**inbox** (`axon_message_inbox`) descarta reentrega, no mesmo commit do append; e o **agregado** descarta
a decisão repetida (`Post.isComplete()`), que é a única que sobrevive a um inbox limpo.

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
   **Uma exceção, estreita e declarada**: evento que chega de OUTRO serviço não tem command local atrás
   dele, então quem o recebe materializa a projeção (`PostCreatedEventHandler`). É o papel clássico de
   uma projeção em CQRS; o que era incomum aqui era o command acumular esse papel, o que só funcionava
   enquanto tudo era local.
4. **Porta de entrada é APRESENTAÇÃO, venha de onde vier.** `interfaces/graphql` para HTTP/WebSocket/SSE
   e `interfaces/messaging` para as filas. O critério não é o transporte, é a DIREÇÃO: adaptador de saída
   (o outbox, os repositórios, o provedor de identidade) é infraestrutura; o que traz algo de fora para
   dentro é apresentação. Um `@Incoming` é um endereço, como um `@GraphQLApi` é um caminho.
   Um listener, portanto, não alcança repositório nem decide regra: entrega a mensagem ao mecanismo de
   ingestão e sai, como um resolver entrega ao command gateway.
5. **A apresentação não alcança `domain` nem `infrastructure` da própria aplicação.** Os `@GraphQLApi` falam com o gateway de
   command/query e com a porta `application.auth.AuthenticatedUser` (implementada por
   `infrastructure.security.CurrentUser`). Nada de repositório de domínio num resolver.
6. **Nada de pacote por papel na raiz.** Não existe mais `dto/`, `mapper/` nem `exceptions/` soltos: cada
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
- **Os ids são escalares NO FIO**: `PostId`, `TagId` e `UserId` levam `@JsonValue` + `@JsonCreator`. Sem
  isso um record de um componente sai como objeto (`{"postId":{"value":"abc"}}`) e quem consome do outro
  lado precisa modelar um invólucro que só existe aqui dentro. Enquanto o event store era em memória nada
  era serializado e ninguém notou; no primeiro serviço que desserializou o payload, a saga morreu com
  `MismatchedInputException: Cannot deserialize value of type String from Object value`.
- O tipo do id da entidade **não** está numa anotação: ele é o primeiro argumento de
  `EventSourcedEntityModule.autodetected(PostId.class, Post.class)`, e quem o fornece é o mapa de
  `libs/platform`, em `infrastructure/axon/EventSourcedEntities` — na PLATAFORMA e não num app, porque
  os dois serviços precisam dele. No starter do Spring era o `idType` do `@EventSourced`, que
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
- **`migrate-at-start` é `false`, e não é preferência.** `AxonExtension.init` é um recorder de
  RUNTIME_INIT que resolve o event storage engine e toca o EntityManager — construindo a persistence
  unit ANTES de o Flyway ter a vez. Com `validate` contra banco vazio a aplicação morre com
  `missing table [accounts]`, e adiar dentro do `ComponentBuilder` não resolve (a lambda é chamada de
  dentro do próprio init). Quem cria o schema: em dev/teste, `db/init/schema.sql`, gerado das migrations
  pelo `maven-antrun-plugin` e executado pelo Dev Services no `initdb`; no compose e em produção, os
  serviços `flyway-*`. É o que produção faria de qualquer jeito, e o gate do `validate` continua valendo
  porque o script é gerado das próprias migrations.
- `baseline-on-migrate` é `false` de propósito: banco não-vazio sem histórico é banco que alguém criou
  por fora.
- Índices que nenhuma anotação JPA expressa vivem só no SQL — o principal é `uk_users_email_active`:
  e-mail único **entre os ativos**, porque um leitor encerrado e o autor que o substituiu convivem com o
  mesmo e-mail.
- Exclusão lógica por `@SQLDelete` + `@SQLRestriction`. Efeito colateral com teste próprio: apagar a conta
  **esconde os posts do autor**, porque `Post.author` é `@ManyToOne(optional = false)` contra uma linha
  filtrada.
- **Um arquivo por agregado na persistência**, em `infrastructure/persistence/<agregado>/`. Eram dois
  (adapter + repositório Panache) porque `PanacheRepositoryBase.findById(Id)` devolve a entidade e a porta
  do domínio devolve `Optional` — mesma assinatura, retornos incompatíveis. Recebendo o `EntityManager`
  por construtor, o conflito desaparece e sobra um arquivo. O pacote é escopado por agregado
  (`.../post`, `.../user`) porque um `.../panache` comum seria **split package** entre os dois módulos.
- **`merge`, nunca `persist`.** A entidade vem reconstituída dos eventos pelo Axon: é sempre *detached*,
  exista a linha ou não.

### GraphQL

- **Schema code-first.** Não há `.graphqls`; o SDL é gerado e servido em `/graphql/schema.graphql`. O
  `schema.graphql` da raiz é uma cópia versionada dele (nada o lê em runtime); atualizar com
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
- **O update chega ao assinante depois do commit, e quem faz isso é o Axon.** O
  `SimpleQueryBus.emitUpdate` chama `runAfterCommitOrImmediately`: bufferiza os updates num recurso do
  `ProcessingContext`, registra **um** `runOnAfterCommit` e entrega o lote junto; sem contexto, ou com ele
  já commitado, entrega na hora. Um `@EventHandler` escreve `emitter.emit(...)` e mais nada.

  **A condição para isso funcionar é a unidade de trabalho do Axon ser DONA da transação.** O
  `quarkus-axon-transaction` faz *begin-or-join*: se já houver transação JTA aberta, ela junta — e aí o
  after-commit do Axon dispara com a transação ainda aberta, o assinante lê o banco noutra thread dentro
  dela, e a transação aborta:

  ```
  ARJUNA012125: TwoPhaseCoordinator.beforeCompletion - failed ... ConcurrentModificationException
  ARJUNA012108: CheckedAction::check - atomic action ... aborting with 2 threads active!
  This statement has been closed.
  ```

  Por isso **`ChannelEventIngestion.ingest` não leva `@Transactional`**: a linha do inbox e o append vão
  dentro da mesma `unitOfWorkFactory().create("axon-inbox")`, que abre a transação e a commita. A
  atomicidade é a mesma; o que muda é quem é o dono. Medido nos dois sentidos com `docker/e2e/run.sh`:
  **11 de 12** com a anotação, **12 de 12** sem ela — e nenhum teste do Surefire pega a diferença, porque
  em teste o tagueamento é dublado em processo e a ingestão não roda. Quem trava é
  `AxonWiringTest.theIngestionOwnsItsOwnTransaction`, que confere a ausência da anotação.

  **Houve duas tentativas de resolver isso por fora, e as duas estão registradas porque as duas
  pareciam certas.** Uma sincronização JTA escrita à mão dentro do `PostCreatedEventHandler` — que punha
  infraestrutura na aplicação e morria no interceptador de métricas (`isStarted()` ainda `true` em
  `AFTER_COMMIT` → `ProcessingContext is already in phase AFTER_COMMIT`, levantada **antes** da emissão).
  E um decorador de `QueryBus` na plataforma, que funcionava e eram 280 linhas para refazer, no eixo do
  JTA, o que o framework já fazia no eixo dele. As duas sumiram quando a fronteira da transação passou a
  bater com a da unidade de trabalho. **Não era uma roda faltando: era a nossa roda girando no eixo
  errado.**
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
4. `findAllById` na porta do repositório + o método no adapter de `infrastructure/persistence/` + o
   duplo em memória de `support/`.

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
2. Nenhuma linha de event store — quem escolhe é o POM. Com `quarkus-axon-jpa-eventstore` e
   `quarkus-axon-tokenstore-jpa` no classpath a extensão troca os defaults em memória pelos de JPA, e as
   tabelas vêm da V2.
   **Os dois módulos declaram cada um uma classe `QuarkusAxonEntityManagerProvider`**, as duas
   `@ApplicationScoped` e nenhuma `@DefaultBean` — ter os dois sem excluir uma derruba a partida com
   `AmbiguousResolutionException`. Daí a linha de `quarkus.arc.exclude-types` no `posts-api`; o
   `apps/tagging` não precisa dela porque não tem token store (não tem processor streaming).

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

### Observabilidade: OpenTelemetry em TODA aplicação

**REGRA: aplicação nova nasce instrumentada.** `quarkus-opentelemetry` mais o
`quarkus-observability-devservices-lgtm` em `provided`, e as mesmas linhas de `quarkus.otel` que os
dois apps já têm. Sinal que existe num serviço e não no outro dá um trace pela metade, e um trace pela
metade é pior que nenhum: **a lacuna parece latência**. Foi exatamente o que aconteceu enquanto só o
`posts-api` exportava — o publish aparecia e depois vinha um silêncio de duração desconhecida, que era
o `tagging` decidindo a tag sem nada registrar.

O que a instrumentação custa em código: **nada**. Traces de HTTP, JDBC e do conector de mensageria são
automáticos, e o elo entre os processos sai de graça porque `tracing.enabled` já é `true` por default
nos dois lados do conector do RabbitMQ. A saga inteira é **um trace só**, medido:

```
quarkus-axon-graphql-posts   POST /graphql                                SERVER     322 ms
quarkus-axon-graphql-posts     GraphQL                                    INTERNAL   309 ms
quarkus-axon-graphql-posts     axonposts.events publish                   PRODUCER   (×3)
axonposts-tagging              axonposts.tagging.post-precreated receive  CONSUMER     2 ms
axonposts-tagging                axonposts.events publish                 PRODUCER
quarkus-axon-graphql-posts     axonposts.posts-api.post-completed receive CONSUMER
```

Quatro coisas que valem por si, e as três primeiras falham em silêncio:

1. **`quarkus.application.name` é o `service.name` do Grafana.** Sem ele todo trace chega como
   `unknown_service` — e com dois serviços no mesmo trace isso apaga justamente a informação que o
   trace distribuído tem para dar: em qual lado o tempo foi gasto.
2. **Logs e métricas são `false` por default no Quarkus.** Só traces vêm ligados, então
   `quarkus.otel.logs.enabled` e `quarkus.otel.metrics.enabled` são o que faz o sinal EXISTIR. Não são
   afinamento.
3. **O container do LGTM é COMPARTILHADO** (`quarkus.observability.lgtm.shared` é `true` por default,
   `service-name` é `lgtm`): uma stack, um Grafana, os dois `service.name` dentro. Quem o SOBE é quem
   partir primeiro — e no `pnpm dev` os dois partem juntos. Daí
   `quarkus.observability.lgtm.grafana-port=3001` estar declarada nos DOIS apps: **a duplicação é
   necessária**, porque sem ela a URL da Grafana passaria a depender de quem ganhou a corrida.
   Verificado com as duas no ar: um container só, e a corrida não produz um segundo.
4. **`quarkus-opentelemetry` NÃO traz porta HTTP** — depende de `quarkus-vertx`, não de
   `quarkus-vertx-http`. É o que permite instrumentar o `tagging` sem lhe dar um endpoint nem fazê-lo
   disputar o 8080 com o outro app no `pnpm dev`.

Em **teste** a observabilidade está desligada nos dois (`%test.quarkus.observability.enabled=false` +
`%test.quarkus.otel.sdk.disabled=true`), e as duas razões são medidas: subir Loki+Grafana+Tempo+Mimir
por suíte custa memória de Docker que esta máquina não tem sobrando, e com o SDK ligado sem coletor todo
teste paga tentativa de exportação e enche o log de falha de conexão. `sdk.disabled` desliga a
instrumentação inteira, não só o exportador. O `provided` do devservice não o tira do classpath de
teste — por isso as linhas, e não a ausência da dependência, é que o desligam.

**O ponto cego em SPANS, e por que ele não se fecha hoje:** o que o Axon faz por dentro do
command/event bus não gera span — nem a extensão de Quarkus os publica, nem o Axon 5 tem tracing. A
documentação de tracing do Axon é explícita: *"The Distributed Tracing feature is not yet available in
Axon Framework 5.0. It will be reintroduced in Axon Framework soon."* O `SpanFactory`, o
`OpenTelemetrySpanFactory` e o artefato `axon-tracing-opentelemetry` são do Axon 4, e o
`axon-framework-bom` 5.3.1 — o que este projeto importa — **não tem artefato de tracing nenhum**. A
extensão de Quarkus até declara o gancho (`AxonTracingConfigurer`), e não há o que plugar nele. Então o
append no event store, o `@EventSourcingHandler` e a decisão de domínio ficam DENTRO do span do
`receive`, como um bloco opaco. Fechar isso hoje é escrever os spans à mão, num interceptador de
mensagem; não foi feito.

**É por isso que as MÉTRICAS do Axon não são enfeite** — elas são o único sinal do que acontece ali
dentro, e vêm de `libs/platform`, em `infrastructure/axon/AxonMetrics`. Está na PLATAFORMA pela mesma
razão que o `EventSourcedEntities`: os dois serviços precisam, e nenhum tem nada de próprio a dizer.
Medido, com as duas aplicações no ar:

```
post_projection_latency{processorName="post-projection", service_name="quarkus-axon-graphql-posts"}  58
tag_decision_latency   {processorName="tag-decision",    service_name="axonposts-tagging"}          391
```

Isso é o ATRASO de cada processor, e é a pergunta que trace nenhum responde: um trace conta uma
requisição que já passou, e aqui o que importa é o que ainda não passou. Junto vêm contador, timer com
buckets, percentil e capacidade de `CommandBus`, `QueryBus` e `EventStore`, com o nome do processor como
TAG (`use-dimensions`), o que permite comparar os dois lados no mesmo gráfico.

**Por que escrito à mão, e NÃO com o `quarkus-axon-metrics`.** A extensão existe, na versão exata da
nossa (`2.0.0-alpha6`), e faz exatamente as duas linhas de `AxonMetrics.configure`. Mas arrasta
`quarkus-micrometer`, que depende de **`quarkus-vertx-http`, e não em escopo opcional** — o que daria
porta HTTP a quem importasse a plataforma, inclusive ao `apps/tagging`, que não tem nem quer uma (ele
disputaria o 8080 com o outro app no `pnpm dev`). O que a extensão precisa de verdade é um
`MeterRegistry`, e o `OpenTelemetryMeterRegistry` é um sobre o bean `OpenTelemetry` que já existe — num
JAR, não numa extensão. Dois JARs (`axon-metrics-micrometer`, `opentelemetry-micrometer-1.5`) e uma
classe, e a métrica sai pelo **mesmo OTLP** que o trace e o log.
Isto também é o que dispensou o `quarkus-micrometer-opentelemetry` no `posts-api` — extensão em
**Preview** no Quarkus 3.39 que chegou a entrar aqui e saiu quando a configuração subiu para a
plataforma. Os dois serviços usam agora exatamente o mesmo mecanismo.

`AxonMetrics` também **funciona por ausência**: substitui o `NoMetricsConfigurer` da extensão, que é
`@DefaultBean`. Apagá-la não quebra compilação — as métricas somem dos DOIS serviços, em silêncio.

## O segundo serviço (`apps/tagging`)

Um microserviço Axon completo, **sem uma linha de HTTP**: reage a `posts.PostPreCreated`, decide a
primeira tag e publica `posts.PostCreated`. Tem event store próprio (banco próprio), fila própria e
agregado nenhum — ele trabalha com o `Post` de verdade, importado de `libs/posts`.

- **não tem agregado próprio.** Houve um `TagAssignment`, e era invenção: a pergunta que importa é "este
  post já está completo?", e o `Post` responde. Entidade para guardar o que outra entidade já sabe é
  estado duplicado, e estado duplicado diverge;
- **não redeclara eventos.** Importar `libs/posts` é o ponto de o domínio ser uma lib. Um evento de
  domínio escrito duas vezes é a mesma regra em dois lugares, e o primeiro campo novo as separa em
  silêncio;
- **não tem read model**, e é por isso que `quarkus.hibernate-orm.packages=org.axonframework`: as
  entidades JPA vêm no classpath com o domínio, e sem essa restrição o `validate` exigiria `posts`,
  `tags`, `users` e `authors` num serviço que não usa nenhuma. Ele reidrata o `Post` do EVENT STORE e
  aplica regra; nada disso passa por JPA;
- **não decide qual é a tag padrão.** Nome e identidade são do domínio (`Tag.DEFAULT_NAME` e
  `Tag.DEFAULT_ID`, este derivado daquele por `UUID.nameUUIDFromBytes`). É função pura: o mesmo id em
  todo nó, toda reinicialização e todo serviço. Importa porque **dois** lugares atribuem a tag padrão —
  este serviço em produção e o dublê em processo na suíte do outro app — e os dois têm de chegar ao mesmo
  id; com a regra no domínio isso é consequência, não coincidência mantida à mão.

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
- **Entre PROCESSOS** (`docker/e2e/run.sh`): sobe a infraestrutura, roda as migrations fora do processo,
  empacota, sobe as DUAS aplicações e afirma a saga inteira — inclusive que o event store de cada serviço
  tem exatamente os eventos esperados (é o que pegaria um laço de reenvio, como contagem crescendo) e que
  reentregar a mesma mensagem não produz uma segunda decisão. Fica fora do Surefire de propósito: o que
  ele prova é o que um `@QuarkusTest` não consegue montar — dois processos, dois event stores, um broker.
- **Ponta a ponta** (`e2e/*`): Dev Services sobem Postgres e Keycloak; uma única aplicação é compartilhada
  por todas as classes. Cada método começa com `truncate ... cascade` **incluindo as tabelas do
  Axon**: com o event store persistente, limpar só o read model deixa estado incoerente — a linha da tag
  some, o stream do agregado Tag continua lá, e o `CreateTag` seguinte falha contra um agregado que existe
  no store e não na projeção. Uma subclasse que acrescente `@TestProfile` ganha aplicação própria e a suíte paga
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

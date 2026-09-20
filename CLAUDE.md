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
libs/axon-aws          o endereçamento de saída em SNS e SQS (uma ChannelAddressing por conector)
libs/axon-lambda       a entrada quando o transporte é o event source mapping do Lambda
libs/axon-native-support  a extensão de build para GraalVM native

apps/posts-api         application/** + interfaces/{graphql,messaging}/** + infrastructure/security
apps/tagging           o serviço de tagueamento: application/** + interfaces/messaging/**
apps/web               o CLIENTE: Next.js + Apollo, o único módulo JavaScript do monorepo
```

`apps/web` não é Maven — é um pacote do workspace pnpm, e por isso não aparece no `<modules>` do pom.
Ele não implementa regra nenhuma: consome a API pela borda GraphQL, como qualquer cliente faria. A
regra de camadas acima não se aplica a ele; a dele está em `apps/web/README.md`.

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
pnpm dev                       # os DOIS backends E o cliente web, em paralelo — ver a seção abaixo
pnpm --filter @axonposts/web dev   # só o cliente, em http://localhost:3000
./infra/scripts/package.sh     # os QUATRO zips de Lambda (alvos do Nx) — ver *AWS Lambda*
./mvnw install -DskipTests -pl '!apps/posts-api,!apps/tagging'   # as libs no ~/.m2 (ver abaixo)
./mvnw quarkus:dev -pl apps/posts-api   # uma aplicação só
./mvnw test                    # suíte inteira — EXIGE Docker
pnpm test                      # o NÍVEL DE BAIXO, pelo Nx: 94 testes, ~26s (ver *Testes*)
pnpm test:e2e                  # os DOIS níveis pesados, em série — ver *Testes*
pnpm lint                      # ESLint nos pacotes JS + Spotless nos 8 módulos Java
pnpm lint:fix                  # conserta os DOIS lados de uma vez (ver *O LINT*)
./mvnw package                 # build + testes
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
`package` — e portanto o alvo `build` de `apps/posts-api-e2e`, que empacota antes de subir as aplicações.

Descartados por medição: estado sujo em `target/`, snapshots instalados no `~/.m2`, índice Jandex
desatualizado nas libs, `quarkus.arc.exclude-types`, teste nomeando classe gerada, opções do processador
na execução vs no plugin, `<proc>none</proc>` no round de teste, `useIncrementalCompilation=false`,
índice Jandex no app, `quarkus.builder.parallel=false` (o augmentation sequencial não conserta) e
produtores de bean escritos à mão em vez do `componentModel` — com eles a falha deixa de ser intermitente
e passa a ser **determinística** (`Producer method return type not found in index`), o que é pior. Daí a
configuração atual ser a convencional.

**Suspeita principal: o JDK — e ela foi TESTADA E REFUTADA.** A JVM desta máquina é a **25**, que o
Quarkus 3.39 não suporta (`release` é 21), e o próximo passo registrado era rodar num JDK 21. Feito,
com um Temurin 21.0.12 baixado só para a medição: **3 empacotamentos, 3 falhas**, com a MESMA
exceção (`Unsatisfied dependency ... PostViewMapper`). No mesmo período, o JDK 25 deu **0 sucessos em
6**. A versão da JVM não é a variável.

Duas observações novas do mesmo episódio, e as duas são pistas melhores que a anterior:

- **a falha é RÁPIDA — ~4,7s no módulo**, sem recompilar. O augmentation roda contra um
  `target/classes` que já existe e não enxerga os impls que estão lá;
- **a taxa não é estável no tempo.** O documento registrava "cerca de metade"; numa janela de uma
  hora foram ~15 falhas seguidas, inclusive com `clean`. Seja o que for, tem estado, e o estado não é
  o `target/` (ver a medição do `clean` na seção do `apps/posts-api-e2e`).

Quem depender de um empacotamento verde hoje — o alvo `build` de `apps/posts-api-e2e` e o
`sst deploy` — repete o comando. **Isto continua aberto**, e o próximo passo
deixou de ser o JDK.

**O BYTECODE FOI CONFERIDO, e é a pista que sobra.** Não é o fonte gerado que está errado nem o
`.class` que falta: `javap -v` nos dois impls mostra `RuntimeVisibleAnnotations` com
`Ljakarta/enterprise/context/ApplicationScoped;`, em classes recém-compiladas, no mesmo minuto da
falha. Classe presente, anotada e fresca — e o `ArcProcessor#validate` diz `Unsatisfied dependency`.
O que não enxerga é o ÍNDICE, e é aí que a próxima investigação tem de começar.

**E o estado incremental do `target/` É gatilho, pelo menos para o `test`.** Depois de uma série de
`package` falhados, `./mvnw test` passou a falhar também — e `./mvnw clean test` voltou a passar, em
33s. São dois caminhos de augmentation diferentes (o `test` não roda `quarkus:build`), e só o do
`test` se recupera com `clean`: para o `package`, a medição de três execuções com `clean` deu o mesmo
1 em 3 de sem ele. **Se a suíte começar a falhar sem que ninguém tenha mexido no código, `clean` é a
primeira coisa a tentar.**

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
  → PostCreatedProjection materializa a linha, DENTRO da transação do append
  → PostCreatedEventHandler emite onPostCreated — noutro processor, em TODO container (ver abaixo)
```

Nenhum dos dois serviços nomeia o outro: um publica `posts.PostPreCreated` e escuta `posts.PostCreated`,
o outro faz o inverso. Trocar o serviço de tagueamento é trocar quem responde àquela routing key.

**Em teste a decisão é dublada em processo** (`InProcessTagAssignment`, removido do build em dev/prod
pelo `@IfBuildProperty`), porque consistência eventual faz mensagem em voo cruzar a fronteira do
`truncate` entre testes. O caminho real é coberto por `apps/posts-api-e2e`, fora do Surefire.

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
   `application.<agregado>.event`, um arquivo por responsabilidade, e a classe leva o nome do evento:
   `PostCreatedEventHandler`, `PostUpdatedEventHandler`. É a mesma forma da versão Spring — o
   `QueryUpdateEmitter` injetado por parâmetro, um `emit` e mais nada.
3. **O command decide e salva; o evento notifica e orquestra.** Event handlers não escrevem no banco — um
   emite para as subscriptions, o outro despacha os commands que dão sequência.
   **Uma exceção, estreita e declarada**: evento que chega de OUTRO serviço não tem command local atrás
   dele, então quem o recebe materializa a projeção. É o papel clássico de uma projeção em CQRS; o que
   era incomum aqui era o command acumular esse papel, o que só funcionava enquanto tudo era local.
   **A exceção tem pacote próprio** — `application.post.projection` —, e não por gosto de simetria: é o
   pacote que escolhe o processor, e projetar precisa de uma entrega que notificar não precisa. Ver a
   seção *O pacote escolhe a ENTREGA*, logo abaixo.
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

### O pacote de um event handler escolhe a ENTREGA dele

Um `@EventHandler` não diz em que processor roda: quem diz é o **pacote**, numa linha de
`application.properties`. E o processor não é afinamento — ele decide **quantas vezes** a reação
acontece, **onde** ela acontece e **o que** acontece quando ela falha. Duas reações ao mesmo evento
podem precisar de respostas opostas para essas três perguntas, e quando precisam, elas não cabem na
mesma classe.

É o caso de `PostCreated`:

|  | projetar (`application.post.projection`) | notificar (`application.post.event`) |
|---|---|---|
| quantas vezes | uma | em **todo** container |
| onde | na transação do append | fora dela |
| se ninguém estiver ouvindo | grava assim mesmo | não há o que fazer |
| se falhar | aborta o append | avisa e segue |
| processor | subscribing | pooled streaming, token em memória, HEAD |

As classes dos dois lados são `@EventHandler` comuns — nenhuma delas sabe em que processor está, e
nenhuma tem uma linha de leitura de evento. **A configuração é a diferença inteira.**

E as duas colunas se sustentam:

- **projetar tem de ser subscribing** porque só ali o handler roda na transação de quem apendou. É daí
  que vem a garantia que o resto usa sem saber — *quem enxerga o evento no store enxerga a linha* —, e é
  por isso que o handler que notifica pode ler o banco sem correr atrás da escrita. Num processor com
  token em memória, um container congelado entre invocações (que em Lambda é o estado normal) levaria a
  materialização com ele;
- **notificar tem de ser streaming** porque um `emit` só alcança os assinantes do próprio processo, e em
  Lambda quem segura a conexão SSE nunca é quem atende a mutation: a invocação dele não retornou.

**Consequência ao escrever código novo**: um handler que grava vai para `projection`; um que avisa, para
`event`. Errar o pacote não quebra compilação nem teste — muda a semântica de entrega em silêncio.

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
  atomicidade é a mesma; o que muda é quem é o dono. Medido nos dois sentidos com `pnpm test:e2e`:
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

1. `quarkus.axon.subscribingprocessor.namespaces` e `quarkus.axon.pooledprocessor.<nome>.namespaces` —
   quais pacotes rodam em qual processor. O valor é o nome do pacote porque a extensão lê `@Namespace` da
   *classe* e cai no pacote como default (por isso o `@Namespace` do `package-info.java` saiu: ali não
   tinha mais efeito).
   **Handler novo num pacote já listado: nada a fazer. Handler num PACOTE NOVO: mais um item numa das
   duas.** Quem fica de fora não dá erro — vai para um `PooledStreamingEventProcessor` **anônimo**,
   assíncrono e com token store JPA, e as duas propriedades que o pacote dele precisava deixam de valer
   em silêncio. Quem a pega é
   `AxonWiringTest.everyPackageWithAnEventHandlerIsAssignedToAProcessor`.
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

## AWS Lambda (`infra/aws/`)

Um segundo alvo de implantação, **aditivo**: nenhum arquivo que existia antes dele foi alterado. As
mesmas duas aplicações, empacotadas por perfil Maven, viram **seis funções**; o RabbitMQ vira um topic
SNS FIFO com três filas SQS FIFO (mais três DLQ) assinando por filter policy; e a identidade é um user
pool do **Cognito**, com os três usuários do realm semeados.

**O KEYCLOAK CONTINUA SENDO TUDO EM DEV E TESTE** — o Dev Services sobe o container, importa
`docker/keycloak/realm-axon-posts.json` e os 155 testes usam aquele realm. O Cognito vale só na AWS,
e a troca saiu porque a aplicação é apenas resource server: o Keycloak custava Fargate + ALB + ECR
(~US$ 25/mês) para entregar o que o Cognito entrega no free tier.

O preço são **quatro linhas** no `application-lambda.properties` (issuer, client id, audience e
`quarkus.oidc.roles.role-claim-path=cognito:groups`). A última é a única divergência de
COMPORTAMENTO, e falha em silêncio: sem ela toda mutation de escrita responde `FORBIDDEN`, e nenhum
teste pega, porque em teste o emissor é outro.

**E o bearer é o ID TOKEN, não o access token.** O access token do Cognito não traz `email`, e
`UserProvisioning` chama `Email.of(identity.email())`. Pôr `email` nele exige o trigger V2_0, que a
AWS só oferece nos planos Essentials/Plus; o tier Lite só tem V1_0, que customiza o ID token. O que
mantém isso seguro é `quarkus.oidc.token.audience` conferindo o `aud`. Ver `infra/aws/cognito.ts`.
Consequência prática: o token de teste NÃO sai de `/oauth2/token` (o Cognito não aceita
`grant_type=password` ali) — sai de `aws cognito-idp initiate-auth`.

```bash
pnpm add -D sst                            # uma vez
./infra/scripts/package.sh                 # os quatro zips em infra/dist/ (atalho para o Nx)
npx sst deploy --stage dev                 # ...e as migrations rodam AQUI DENTRO
./infra/scripts/e2e.sh                     # a saga inteira, afirmada contra a stack
npx sst remove --stage dev                 # NÃO é opcional: ~US$ 0,13/hora

npx nx run "dev.manuelantunes:quarkus-axon-graphql-posts:lambda-http"      # atrás do API Gateway
npx nx run "dev.manuelantunes:quarkus-axon-graphql-posts:lambda-sqs"       # movido pela fila de volta
npx nx run "dev.manuelantunes:quarkus-axon-graphql-posts:lambda-stream"    # a Function URL que streama
npx nx run "dev.manuelantunes:axonposts-tagging:lambda"                    # o MESMO zip serve as duas
npx nx run "dev.manuelantunes:axonposts-tagging:lambda:jvm"                # sem binário nativo
npx nx run-many -t lambda-http,lambda-sqs,lambda-stream,lambda -c native --parallel=1   # os quatro
```

**SEIS funções de TRÊS zips**, e as duas últimas são as de fila com outra variável de ambiente:
`quarkus.lambda.handler` é configuração de RUNTIME, então `QUARKUS_LAMBDA_HANDLER=flyway-migrate`
transforma o mesmo artefato na função que cria o schema. **`migrate-at-start` continua `false`** — em
Lambda isso deixa de ser contorno e vira a única escolha correta: migration na partida de uma função
que escala para N ambientes seria N tentativas concorrentes, cada cold start esperando o lock do
Flyway de outro dentro do timeout de uma invocação. A ordem é: deploy, invocar as duas migrações,
testar.

**Quatro coisas que o SST 4.17.1 faz diferente do que se supõe**, todas medidas contra a versão
instalada: (1) `sst.aws.Function` **não suporta Java** — os runtimes são node, go, rust, python e
container, então as seis funções são `aws.lambda.Function` do provider Pulumi cru, que o SST expõe
como o global `aws`; (2) o código vai por **S3**, porque os zips têm 59–72 MB e o upload direto para
em 50 MB — e sem `sourceCodeHash` trocar o conteúdo na mesma chave **não atualiza a função**, e o
deploy publica o artefato antigo dizendo que deu certo; (3) `dlq` exige o par `{ queue, retry }`, e a
DLQ de uma fila FIFO também é FIFO; (4) `rawMessageDelivery` não é opção da subscription — vai por
`transform.subscription`. E no `image` do `Service`, o `dockerfile` é relativo ao **context**; e
caminho de arquivo na config é resolvido a partir de `.sst/platform/` e não da raiz, então o
`FileAsset` precisa de `$cli.paths.root`.

**`✓ Complete` do `sst deploy` NÃO quer dizer que deu certo.** O deploy que tropeçou nesse caminho
imprimiu `✓ Complete` na tela — com o URL do Keycloak — e deixou de criar as SEIS funções, o API
Gateway e os três objetos no S3. O erro estava só em `.sst/log/pulumi.log` (`3 errors`). Recurso que
não aparece depois de um deploy "bem-sucedido": é esse arquivo que responde, e `npx sst diff`
confirma, porque ele passa a não enxergar o que falhou ao registrar.

**Os três perfis escrevem em `target/function.zip`** — o segundo apaga o primeiro. Por isso o script
copia para `dist/` entre eles, e por isso não há como empacotar os três numa invocação só.

**A troca de protocolo não é código.** O `@AxonOutbox` continua dizendo `channel = "post-events-out"`,
`namespaces = "posts"` — porque o que um serviço publica é contrato dele — e quem troca RabbitMQ por
SNS é uma linha de `application-lambda.properties`. É exatamente o que a porta `ChannelAddressing`
existia para comprar.

**`quarkus.profile=lambda,prod` tem de valer nos DOIS momentos**, e as duas entradas importam:
build time (vem do perfil Maven) e runtime (`QUARKUS_PROFILE` na função). Com `lambda` sozinho, as
linhas `%prod.` do `application.properties` deixam de valer **em silêncio** e a função sobe apontando
para o datasource de desenvolvimento. Só em build time, a função ignora o
`application-lambda.properties` e tenta falar com um RabbitMQ que não existe.

**Por que DOIS módulos novos, e quem decidiu:** o build.
`quarkus-amazon-lambda-http` **traz** o processador do `quarkus-amazon-lambda`, que varre o índice
atrás de `RequestHandler` e recusa o que achar (`Multiple handler classes`). Não basta a função de
HTTP não usar o handler — ele não pode estar no classpath dela. Daí `libs/axon-aws` (endereçamento de
saída, nas três funções) e `libs/axon-lambda` (o handler, só nas de fila).

**Os `@Incoming` que já existem continuam sendo a porta de entrada.** O canal passa a
`smallrye-in-memory` e o handler do Lambda empurra o registro para dentro dele — então
`PostPreCreatedListener`, `PostChangesListener` e `PostCompletionListener` rodam sem uma linha
alterada, com o `@Blocking(ordered = false)` e a unidade de trabalho do Axon que já estão medidos ali.
O conector in-memory é, portanto, **dependência de produção**, e tem um segundo papel: na função de
API Gateway ele é o objeto nulo do canal `post-completed-in`, que está declarado sem perfil e não pode
ser removido por arquivo de perfil, só sobrescrito.

**FIFO não é afinamento.** `MessageGroupId` é a tag do agregado (o `EventAddress.orderingKey()`, que
na routing key não desempatava nada). Em fila standard esta saga não funciona pior — ela quebra, com
`duplicate key ... uk_aggregateevententry_aggregate`, de forma intermitente e proporcional à carga.

**A infraestrutura é SST**, e o `sst.config.ts` da raiz não a contém: ele faz `app()` e um
`await import("./infra/aws")` dentro de `run()` — dinâmico porque os módulos criam recursos no topo do
arquivo, e estaticamente seriam avaliados antes de `app()` rodar.

```
infra/dist/      os três zips (gerados)
infra/scripts/   package.sh (atalho para os alvos do Nx), build-env.sh, migrate.sh, discover.sh, e2e.sh
infra/aws/
  index.ts       a fachada: ordem de carga e outputs. Não cria nada.
  support/       as DEFINIÇÕES: ArtifactStore, QuarkusFunction/QueueWorker/Migrator, HttpApi.
                 NADA aqui cria recurso ao ser importado.
  network/ data/ messaging/ identity/    a infraestrutura de base
  compute/       as seis funções e o API Gateway; platform.ts é onde support/ encontra os recursos
  edge/          o Router: UMA distribuição do CloudFront na frente do site e do subgraph
  web/           o cliente Next.js, atrás do router
```

**A seta aponta sempre para o mesmo lado**: quem define não conhece quem instancia. `compute/platform.ts`
é o único ponto onde os dois lados se encontram, e é por isso que ele existe separado — sem ele,
`support/functions.ts` teria de importar `network` e `role`, e um helper que importa infraestrutura
deixa de ser helper.

**Tudo que tem satélite é um `ComponentResource`**: `ArtifactStore` (bucket + um objeto por artefato),
`ExecutionRole` (papel + política), `QuarkusFunction` e as subclasses `QueueWorker` (+ event source
mapping) e `Migrator` (+ a invocação), e `HttpApi` (API + integração + rota + stage + permissão). Isso
dá ciclo de vida junto, URN própria por peça e uma árvore de deploy que descreve o sistema.

**AS MIGRATIONS RODAM SOZINHAS NO DEPLOY.** `Migrator` cria a função e a `aws.lambda.Invocation` que a
chama, com `input: Date.now().toString()` para rodar a cada vez (Flyway é idempotente) e `if (!$dev)`
porque em `sst dev` não há artefato publicado. Migration que falha vira DEPLOY que falha.

**Caminho de arquivo na config usa `$asset()`**, que resolve relativo à raiz do app. `$cli.paths` é
`@internal` e caminho relativo cru quebra — ver a armadilha logo abaixo.

**Três coisas que falham em SILÊNCIO no provisionamento**, e as três estão em `infra/aws/`:
`RawMessageDelivery=true` em cada subscription (sem ele o corpo é o envelope do SNS e a ingestão morre
com `UnrecognizedPropertyException: Type`); `ReportBatchItemFailures` em cada event source mapping
(sem ele a AWS ignora o `SQSBatchResponse` e o lote volta inteiro); e `AXONPOSTS_LAMBDA_SQS_CHANNEL`
em cada função de fila.

**A SUBSCRIPTION NO LAMBDA — as TRÊS camadas, e as TRÊS estão resolvidas.** Importa separá-las porque
cada uma tem causa e conserto próprios, e consertar uma não faz as outras desaparecerem:

1. **O handler não streama, e não é configuração.** No bytecode da 3.39.2:
   `LambdaHttpHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>` — o
   tipo de retorno É a resposta inteira. O `NettyResponseHandler` acumula cada `HttpContent` num
   `ByteArrayOutputStream` e só completa o `CompletableFuture` no fim. Zero ocorrências de
   `vnd.awslambda`, `RESPONSE_STREAM` ou `HttpResponseStream` no JAR.

   **E o `RequestStreamHandler` do `quarkus-amazon-lambda` também não resolve — mas chega perto.** O
   `AbstractLambdaPollLoop` (em `quarkus-amazon-lambda-common`) faz, no ramo de stream,
   `responseStream(url).getOutputStream()` e passa ESSE stream ao handler: os bytes escritos vão para a
   conexão com a Runtime API, não para um buffer intermediário. O que falta está em `responseStream`,
   que tem exatamente cinco instruções — `openConnection`, `User-Agent`, `setDoOutput(true)`,
   `setRequestMethod("POST")`, `return`:
   <ul>
     <li>sem `setChunkedStreamingMode`, o `HttpURLConnection` do JDK <b>bufferiza tudo</b> para
         calcular o `Content-Length`. Nada sai antes do `close()`;</li>
     <li>sem `Content-Type: application/vnd.awslambda.http-integration-response`, a AWS não lê prelúdio
         nenhum — não há como definir status nem cabeçalhos.</li>
   </ul>
   O método é `protected`, mas quem o herda é `AmazonLambdaRecorder$1`, anônima dentro do recorder:
   trocar as duas linhas significa forkar a extensão ou sombrear a classe no classpath.
2. **O transporte na frente não suporta streaming.** O API Gateway não o oferece em modo nenhum:
   response streaming na AWS existe só em Function URL com `InvokeMode: RESPONSE_STREAM`.

**AS DUAS PRIMEIRAS FORAM RESOLVIDAS — e não por um fork.** A saída foi inverter o problema:
`StreamingFunction` (`infra/aws/support/functions.ts`) empacota a aplicação com o perfil
`-Plambda-stream`, que <b>não acrescenta extensão de Lambda nenhuma</b> — ela é o servidor HTTP que
sempre foi. Quem a transforma numa função é o <b>AWS Lambda Web Adapter</b>, uma layer oficial que
roda como extensão, espera o health check e traduz cada invocação numa requisição para `localhost`.
Com `AWS_LWA_INVOKE_MODE=response_stream` e Function URL em `RESPONSE_STREAM`, o que o Vert.x escreve
sai conforme escreve.

MEDIDO contra a stack: os comentários de keep-alive do `SseStream` chegaram às 21:48:58 e 21:49:13 —
<b>15 segundos de intervalo, exatamente o `axonposts.graphql.sse.keep-alive`</b>. A conexão fica de pé
e os bytes chegam progressivamente. Pelo API Gateway, nenhum byte chega em 30 s.

A única dependência que o perfil acrescenta é `axonposts-axon-aws`, e não tem nada a ver com Lambda:
são os conectores que o `application-lambda.properties` nomeia. Sem ela o build falha em BUILD TIME
com `The channel 'post-events-out' is configured with an unknown connector (smallrye-sns)`.
3. **E a fonte era EM PROCESSO — a que sobreviveu aos outros dois consertos.** Medido na Function URL
   com o streaming já funcionando: assinar `onPostUpdated`, criar um post e editá-lo não entregou
   evento nenhum — a mutation cai noutro container, porque o que segura a conexão está ocupado com uma
   invocação que não retornou.

**A TERCEIRA FOI RESOLVIDA POR CONFIGURAÇÃO, e o conserto não tem uma linha de leitura de evento.**
Quem avisa os assinantes continua sendo `PostCreatedEventHandler` e `PostUpdatedEventHandler` — as
mesmas duas classes da versão Spring, em `application.post.event`, com o `QueryUpdateEmitter` injetado
por parâmetro e um `emit` no corpo. Elas não sabem em que processor rodam. **O que mudou foi o processor
do pacote delas**: `application.post.event` está declarado como <b>pooled streaming</b> em
`application.properties`, com <b>token store em memória</b> e posição inicial no <b>HEAD</b>. A
consequência é a solução inteira:

- **streaming** — o processor lê o EVENT STORE, que é compartilhado. Ele enxerga o que qualquer
  container apendou. (O `AggregateBasedJpaEventStorageEngine` suporta isso: ele tem
  `stream(StreamingCondition)` com `GapAwareTrackingToken`, gaps e lotes — verificado no bytecode.)
- **token em memória** — cada container tem o próprio cursor. Com o token store JPA, um container
  reclamaria o segmento e os outros não veriam nada: é o oposto de um fan-out, onde TODO container
  precisa ver TODO evento.
- **HEAD** — quem sobe agora quer o que vier a partir de agora, não o histórico reemitido.

Quem lê, mantém o cursor, faz o lote e trata falha é o Axon, com o `EventStorageEngine` e o
`TokenStore` que a aplicação já configura.

**O que teve de sair de lá foi a PROJEÇÃO, não o emit.** Materializar a linha de um `PostCreated` que
chegou de outro serviço precisa do oposto: uma vez, na transação do append, abortando o append se
falhar. Num processor com token em memória, um container congelado entre invocações — que em Lambda é
o estado normal — levaria a materialização com ele. Por isso ela mora em `application.post.projection`,
que é subscribing. Ver *O pacote de um event handler escolhe a ENTREGA dele*.
<p>
E a ordem, que antes era cuidado escrito à mão (reconciliar antes de emitir, na mesma função), passou a
vir da transação: o processor streaming só enxerga o evento **depois** do commit que gravou a linha.

**E isso exigiu DESFAZER uma exclusão de bean que escondia um defeito maior.** O
`PooledEventProcessingConfigurer` estava em `quarkus.arc.exclude-types`, com o argumento de que
processor pooled "não é o que este projeto quer em lugar nenhum". O efeito real não era desligar um
acidente, era desligar a CAPACIDADE: enquanto ele esteve fora, um namespace esquecido em
`subscribingprocessor.namespaces` não virava um processor assíncrono — virava <b>nenhum processor</b>, e
os handlers dele nunca rodavam, sem um aviso em lugar nenhum. O que protege contra o acidente original
agora é `AxonWiringTest.everyPackageWithAnEventHandlerIsAssignedToAProcessor`, que exige que todo pacote
com `@EventHandler` esteja declarado no subscribing OU num pooled nomeado.

**MEDIDO NA FUNCTION URL**, com a subscription aberta num container e a mutation atendida em outro:

```
event: next
data: {"data":{"onPostUpdated":{"id":"e91dad31-…","title":"editado — deve chegar na subscription","version":2}}}
```

### O BINÁRIO NATIVO — e os quatro defeitos que só ele revela

O cold start da JVM era de **14,8 s**, e com o Lambda Web Adapter ele é cobrado como tempo de
invocação (o boot acontece DENTRO do handler, então não há `Init Duration` no REPORT). O binário
nativo resolve isso, e `libs/axon-native-support` existe exatamente para que ele funcione.

**Medido na stack, a mesma função, o mesmo código:**

| | JVM | nativo |
|---|---|---|
| cold start (health 200) | 14,8 s | **2,8 s** |
| `createPost` quente | 0,70–0,89 s | **0,50–0,57 s** |
| abrir uma subscription | 26 s | **5 s** |
| memória usada (REPORT) | 413 MB | **187 MB** |
| zip | 70 MB | 56 MB |

Ele ganha nos DOIS eixos. A leitura intermediária de que "native é mais lento quente" era medição de
um processo morrendo — `curl -w %{time_total}` mede o tempo até a resposta, e uma resposta de erro
também tem tempo.

**COMO SE CONSTRÓI:** um alvo do Nx, na configuração `native` (o default) ou `native-container`.

`native` compila com a GraalVM **da máquina**; `native-container`, dentro do builder image do
Mandrel. A diferença não é conveniência: `native-image` gera um executável do SISTEMA onde roda, e o
Lambda precisa de ELF/Linux — então **de um Mac, só o `native-container` produz um binário que o
Lambda executa**. O local serve para rodar e depurar a aplicação nativa aqui, e serve inteiro num
Linux com a GraalVM instalada. **A nota de que o container precisa de ~12 GiB no Docker ficou
desatualizada**: os quatro artefatos foram construídos com o Docker em **8 GiB**, com o
`native-image` enxergando 8,23 GB e `-J-Xmx10g`. O `exit 137` no `[1/8] Initializing` continua
sendo o sintoma de faltar memória, e ele não menciona memória em lugar nenhum — mas 8 GiB bastam
hoje. O `build-env.sh` avisa abaixo de 12 GiB em vez de falhar, o que continua certo.

**E num Mac com o Xcode quebrado o build local falha sem nomear o Xcode.** Medido: `xcode-select -p`
aponta para o `Xcode.app`, o `cc` que vem dali nem carrega (`dlopen(@rpath/libxcodebuildLoader.dylib):
Symbol not found: _XPCTypeBool`, exit 72), e o `native-image` morre em ~20s com `Unable to detect
supported DARWIN native software development toolchain`. Os Command Line Tools são uma instalação
independente e funcionam; quem os põe no jogo é o `infra/scripts/build-env.sh`, com as DUAS coisas
que a receita exige — o PATH (é dali que o `native-image` tira o `cc`) e o `-isysroot`, porque o
`native-image` **não** repassa `SDKROOT` nem `DEVELOPER_DIR` ao compilador. A troca só acontece
quando o `cc` do sistema está quebrado.

**AS QUATRO FALHAS, e o que cada uma ensina.** Nenhuma aparece na JVM; três das quatro só aparecem
em RUNTIME:

1. **`org.LatencyUtils` ausente** — `io.micrometer.core.instrument.AbstractTimer` a referencia e o
   Micrometer a declara OPCIONAL. Quem normalmente a traz é o `quarkus-micrometer`, que este projeto
   não usa de propósito. Falha em BUILD, no `[2/8] Performing analysis` — a única das quatro que o
   compilador pega. Conserto: declarar a dependência em `libs/platform`.
2. **`AnnotationBasedEntityIdResolver` não registrado** — a reflexão do Axon tem DOIS níveis, e o
   `AxonNativeImageProcessor` só cobria o primeiro: registrava as `*Definition` e não o que elas
   INSTANCIAM. A aplicação sobe o bastante para o health responder 200 e **morre a cada requisição**
   com `Runtime exited with error: exit status 1`, que o Lambda reporta sem causa. Conserto:
   `AXON_DEFAULT_IMPLEMENTATIONS` no mesmo processor.
3. **As classes-base do Relay** — `PostConnection` é uma subclasse VAZIA de `Connection<N, E>`, e um
   método herdado não entra no registro da subclasse. O cliente recebe `"System error"` com
   `path: ["posts","edges"]` e o servidor **não loga uma linha**. O que denuncia é `pageInfo` e
   `edges` falharem juntos enquanto `__typename` e o SDL respondem: o problema é o OBJETO, não um
   campo. Conserto: `@RegisterForReflection` em `Connection` e `Edge`.
4. **A precedência de configuração muda entre JVM e nativo.** `application-lambda.properties` diz
   `quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL}` sem prefixo; `application.properties` diz
   `%prod.quarkus.oidc.auth-server-url=...localhost:8081...`. Na JVM a primeira vence; em nativo, a
   segunda — e a função sobe apontando para um Keycloak que não existe. Público responde, autenticado
   dá 500. **Não é o arquivo que deixa de carregar**: `axonposts.graphql.sse.keep-alive=2s`, do mesmo
   arquivo, vale (medido: keep-alives a cada 2 s). É a disputa entre uma propriedade COM perfil e uma
   SEM. Conserto: `QUARKUS_OIDC_AUTH_SERVER_URL` como variável de ambiente em `environment.ts` —
   ordinal 300 ganha de qualquer arquivo, nos dois empacotamentos.

**AS SEIS FUNÇÕES SÃO NATIVAS.** As três empacotadas pelas extensões `quarkus-amazon-lambda*`
produzem um `function.zip` com um `bootstrap` nativo no lugar do handler Java — por isso
`runtime: provided.al2023`. A de streaming é a única em `java21`: lá quem executa é o `run.sh` pela
layer do Web Adapter, e esse gancho é do runtime GERENCIADO; o sandbox traz uma JVM que nunca roda.

**A saga fecha em ~6 s** — era ~21 s com o `tagging` em JVM e ~50 s com tudo em JVM.

**MAIS DUAS FALHAS, e as duas só apareceram quando o SEGUNDO serviço virou binário:**

5. **`apps/tagging` não declarava `axon-native-support`.** Só o `posts-api` a tinha. O binário é
   gerado, sobe, e morre na PARTIDA com `No suitable constructor found for entity of type
   [AnnotationBasedEventSourcedEntityFactoryDefinition]` — a primeira das falhas que o Javadoc daquela
   extensão descreve. **Serviço que usa Axon e compila nativo declara a extensão**, e agora o pom diz
   isso por escrito.
6. **A reflexão de uma MENSAGEM não para na classe dela.** `PostCreatedEvent` é `@Event` e era
   registrado; o `AssignedTag` que ele carrega dentro de `List<AssignedTag>` é um record ANINHADO, sem
   anotação, e ficava de fora. O `tagging` consumia `PostPreCreated` (record PLANO, que serializa
   bem), decidia a tag e não conseguia publicar o `PostCreated`:

   ```
   ConversionException: Exception when trying to convert object of type
     'dev.manuelantunes.axonposts.domain.post.event.PostCreatedEvent' to 'byte[]'
   ```

   **O que tornava isso difícil de achar**: a saga parava na versão 1, o contador `Errors` do Lambda
   ficava em ZERO (a exceção é tratada pelo interceptador), as filas ficavam VAZIAS e o SNS mostrava
   `NumberOfMessagesPublished` positivo com zero falhas. Tudo apontava para o lugar errado. Quem
   respondeu foi comparar as métricas: `TaggingDecide` com 7 invocações e 0 erros, `PostsApiInbox` com
   NENHUMA — o elo quebrado estava no meio.
   <p>
   O conserto é uma regra, não uma lista: `AxonNativeImageProcessor` agora registra o **fecho
   transitivo** dos tipos que compõem cada mensagem, parando no que o índice Jandex não conhece
   (`String`, `Instant` e o resto do JDK não precisam).

### A TELEMETRIA: o coletor do OpenTelemetry como layer, e o Better Stack no fim

Toda função carrega a layer oficial do coletor (`opentelemetry-collector-arm64-0_23_0`), que lê
`infra/lambda/collector.yaml` do próprio zip e reexporta para o Better Stack. A aplicação continua
exportando OTLP para `localhost` — ela não sabe qual é o destino, e é isso que faz trocar de backend
ser uma mudança de infraestrutura.

**Por que um coletor, e não o exportador da aplicação falando direto.** Um Lambda é CONGELADO quando
o handler retorna. Um exportador em processo perde o que ainda não saiu — e o que ainda não saiu é o
fim da requisição. A layer é uma EXTENSÃO: recebe o gancho de fim de invocação e faz o flush antes do
congelamento. É a explicação do que o `CLAUDE.md` já registrava — log não chegando enquanto o trace
chegava.

**A LAYER COBRE UM DOS DOIS SALTOS, E O DEFEITO ESTAVA NO OUTRO.** O caminho é
`aplicação --(1) OTLP p/ localhost--> coletor (layer) --(2) HTTPS--> Better Stack`. A layer recebe o
gancho de fim de invocação e despeja o que **já está dentro dela** — o salto (2). Quem decide quando
acontece o salto (1) é o `BatchSpanProcessor` DENTRO do processo, numa thread que dispara a cada 5 s
(1 s para logs), e o Lambda é congelado antes disso.

Medido, com os binários nativos: `TaggingDecide` 438 ms, `PostsApiInbox` 233 ms, `TaggingReplicate`
195 ms — as três funções de fila com **zero spans e zero logs** no backend, enquanto o coletor subia,
rodava e fazia flush sem um único erro, de um buffer vazio. Provado nos dois sentidos: depois de uma
saga sem telemetria nenhuma, invocar a função com um lote VAZIO fez aparecer o span da invocação
anterior, **com o carimbo de tempo original**. O dado não estava perdido — estava congelado do lado
de cá do salto (1).

**Só apareceu com o nativo**, e a razão é boa: na JVM o cold start de ~15 s acontecia DENTRO do
handler e o lote disparava no meio dele. A telemetria chegava por uma janela acidental que a lentidão
abria (o `tagging` chegou a ter 166 logs). O nativo sobe em 0,6 s e a janela fechou.

**O conserto é `TelemetryFlush`, em `libs/axon-lambda`**: um `forceFlush` dos três providers no fim do
handler de SQS e no da migração. Depois dele, sem acordar nada: `axonposts-tagging` com spans e 65
logs, e a saga num trace só.

**A ALTERNATIVA ÓBVIA FOI TENTADA E QUEBRA A SAGA.** A extensão tem `quarkus.otel.simple`
(`OTelBuildConfig#simple`), que troca o processador em lote pelo `SimpleSpanProcessorWithBatchShutdown`
— cada span sai na hora, e o lote passa a ser do `batch` do coletor. É a divisão de responsabilidade
certa, foi implantada, e **a saga parou na versão 1**:

```
ARJUNA012094: Commit of action ... invoked while multiple threads active within it.
ARJUNA012107: CheckedAction::check - atomic action ... commiting with 2 threads active!
Caused by: java.sql.SQLException: Enlisted connection used without active transaction
```

Exportar no `onEnd` é exportar DENTRO da transação, e o exportador do Quarkus despacha num worker do
Vert.x que entra na mesma transação. É a MESMA família de falha que a seção de subscriptions já
registra para o `@Transactional` na ingestão — lá "aborting with 2 threads active", aqui "commiting".
É também o que torna o flush explícito a escolha certa e não a que sobrou: ele roda **depois** do
commit, na thread do handler, sem transação ativa.

**O que ele NÃO resolve: métricas.** Elas não têm processador — têm leitor periódico
(`MetricsRuntimeConfig` só expõe `exportInterval()`); o `forceFlush` do `SdkMeterProvider` entra junto,
mas o que estiver fora do intervalo de coleta continua saindo na invocação seguinte.

**O flush NÃO entra nas funções de HTTP e de streaming**: a primeira é invocada em sequência (o lote de
uma requisição sai na seguinte) e a segunda mantém o processo vivo — foram as duas únicas que
exportaram durante todo o episódio.

**O `collector.yaml` não tem `batch`** — ele segura dados esperando encher um lote, e um lote pela
metade morre com o congelamento.

**Mas TEM `decouple`, e essa linha é uma reversão que a medição impôs.** A versão anterior o excluía
com o argumento de que ele "é a mesma aposta que o batch com outro nome". Medido na stack: **14
falhas em 5 minutos, e só na função de streaming**, todas `context canceled`. Isso não é rede — é a
INVOCAÇÃO TERMINANDO com o export em voo. Exportar dentro do ciclo da invocação só funciona quando a
invocação dura mais que o export, e a função de streaming é justamente a que responde rápido e a que
produz mais spans: sem `decouple`, o trace dela era o que mais se perdia.
<p>
Junto vieram `timeout: 30s` e `retry_on_failure` no exportador, e o número saiu de
`net/http: TLS handshake timeout` no log: o primeiro export de um container novo paga DNS mais
handshake TLS saindo de um VPC, por NAT, e o que se perdia era o trace do cold start — o mais
interessante de todos.
<p>
**Depois**: `no more retries left` = **0** nas quatro funções. O que restou no log são tentativas
(`dial tcp ... i/o timeout`) que a retry resolve — falha visível, dado entregue.

**As credenciais vêm do `.env` da raiz**, que o SST carrega sozinho; `support/functions.ts` as repassa
a toda função e FALHA o deploy se faltarem — um coletor sem destino sobe, não reclama, e some com a
telemetria em silêncio. Medido depois do deploy: zero `Exporting failed` nas três funções.

### A PROPAGAÇÃO DO TRACE ENTRE OS SERVIÇOS

Com o RabbitMQ a saga inteira era **um trace só**, de graça: o `tracing.enabled` do conector já vinha
ligado dos dois lados. Na AWS isso regrediu por uma assimetria que este documento já registrava —
`smallrye-reactive-messaging-aws-sqs` traz um instrumentador e injeta; **`aws-sns` 4.37.0 não tem
pacote de tracing nenhum** —, e a saída deste sistema é SNS.

**A saída passou a injetar à mão**, em `AwsEventAttributes.inject`: o `traceparent` do W3C entra no
mapa de atributos, ao lado de `axon-routing-key` e companhia. É o único lugar por onde passam os
atributos das DUAS saídas (SNS e SQS), e para o fio o `traceparent` é um atributo como os outros.

**A entrada extrai**, em `SqsChannelIngress`: não há conector para instrumentar (o Lambda entrega o
`SQSEvent` direto ao handler), então o contexto é lido dos atributos e ativado com `makeCurrent`.

**MEDIDO na stack**, no log do publish:

```
sns → grupo=939a4535-… atributos={axon-message-name=PostPreCreated, …,
      traceparent=00-7fc419aec2bb7739d0472b62d998f8d1-7c487761ccc959e1-03}
```

**A IDA ESTÁ FECHADA, e foi confirmada no backend** — não só no log do publish. Um trace único, com
os dois serviços dentro:

```
quarkus-axon-graphql-posts   POST                         server
quarkus-axon-graphql-posts   GraphQL                      internal
axonposts-tagging            post-precreated-in receive   consumer
```

Quem cria o span do lado de lá é `SqsChannelIngress`: não há conector para instrumentar (o Lambda
entrega o `SQSEvent` direto ao handler), então ele extrai o contexto dos atributos e abre um CONSUMER
à mão. Sem esse span o `tagging` não aparecia em trace nenhum — e vale lembrar que ele ficou invisível
por um SEGUNDO motivo, independente deste: o lote congelado, que a seção da telemetria descreve.

**MEDIDO, com uma raiz injetada à mão no proxy** (fazendo o papel do navegador): a ida fecha
`navegador → web → posts-api → GraphQL:Criar → tagging`, tudo sob o mesmo trace e com o aninhamento
certo. A VOLTA não:

```
post-precreated-in receive   tagging     e090ff06…   o trace do navegador   tem pai
post-completed-in receive    posts-api   92d47d4b…   TRACE NOVO             (raiz)
post-changes-in receive      tagging     8bddc39d…   -                      tem pai
```

A terceira linha é a que fecha o diagnóstico: a réplica do `updatePost` TEM pai, então não é o
SNS/SQS que perde o contexto — ele o carrega bem quando quem publica é o `posts-api` a partir da
thread da requisição HTTP. Quebra só quando o publish vem DEPOIS de uma ingestão.

**O QUE AINDA NÃO ATRAVESSA, e a causa é conhecida.** O `apps/tagging` recebe o contexto, mas o
`PostCreated` que ele publica de volta sai **sem** `traceparent`. O `Context` do OpenTelemetry é
thread-local, e a ingestão entrega a mensagem a um canal in-memory que roda o trabalho em OUTRA
thread (`source.runOnVertxContext(true)`): o `makeCurrent` vale na thread que espera o ack, não na
que faz o append e o publish. O conserto é carregar o contexto na METADATA da mensagem e restaurá-lo
em `ChannelEventIngestion` — infraestrutura, sem tocar nos listeners, que por regra só entregam e
saem.

### OS SPANS DE GRAPHQL

Eram uma linha, e não código. O SmallRye traz um `TracingService` em `smallrye-graphql-cdi` que fala
a API do OpenTelemetry direto; ele é um `EventingService` descoberto por ServiceLoader e **gateado
por `quarkus.smallrye-graphql.tracing.enabled`**. Desligado, nada acontece e nada avisa.

E o registro para o binário nativo vem junto: o `SmallRyeGraphQLProcessor` do Quarkus tem um
`activateTracing` que emite o `ServiceProviderBuildItem` quando a propriedade está ligada. Houve aqui
um `@RegisterForReflection` à mão mais um `quarkus.native.resources.includes`; **os dois saíram**
quando isso foi conferido no bytecode da extensão — o que a plataforma já faz não precisa ser
reescrito.

### O CLIENTE WEB TAMBÉM EXPORTA

`apps/web/src/instrumentation.ts` é o gancho do Next, e ele é só um `if`: o SDK do Node depende de
`async_hooks` e de patch de módulo, então importá-lo no topo quebraria o BUILD do bundle de edge.
`instrumentation.node.ts` liga o `@vercel/otel` com `fetch` e a instrumentação de GraphQL;
`instrumentation.edge.ts` liga só `fetch` — `@opentelemetry/instrumentation-graphql` não roda em
edge, e deixá-la lá é um build quebrado esperando a primeira rota de edge.

A função do Next carrega a MESMA layer do coletor, com o `collector.yaml` injetado por
`transform.server` (o `server` do componente não expõe `copyFiles`). Ela exporta para `localhost:4318`
— HTTP, que é o que o `@vercel/otel` fala; as funções Java usam 4317 (gRPC), e o mesmo `collector.yaml`
abre as duas portas.

**O que isso acrescenta**: o salto que faltava. Uma operação do navegador atravessa DUAS funções — o
proxy de `/api/graphql` e o `posts-api` — e só a segunda aparecia.

**E a instrumentação de `fetch` precisa de `propagateContextUrls`, que começa VAZIO.** No
`@vercel/otel` o default é `[]` (fora as URLs de deploy da Vercel): ela cria o span da chamada — ele
aparece no trace — e **não injeta o `traceparent`**. O resultado é enganoso, porque o salto está
desenhado e mesmo assim o serviço do outro lado abre um trace novo. Medido antes: 317 spans de
`axonposts-web` e 127 de `quarkus-axon-graphql-posts` na mesma hora, e ZERO traces com os dois. Depois
da linha, o `POST /graphql` do `posts-api` passou a chegar com PAI REMOTO.

**Limite conhecido, e é o mesmo da seção da telemetria**: a função do Next também é congelada ao
retornar, e o `@vercel/otel` também usa processador em lote. Os spans dela chegam, mas **atrasados de
uma invocação** — num site com tráfego isso não se nota, e num ambiente parado sim. O
`TelemetryFlush` não a alcança: ele é Java, e ali quem exporta é o SDK de Node.

### O BUILD É UM ALVO DO NX, E O ALVO VIVE NO GRAFO DE RECURSOS

Declarar uma função é declarar o build dela. `QuarkusFunction` recebe em `code` ou um
{@code QuarkusBuild} — artefato, **comando de build**, **caminho do zip**, bucket e fontes — ou o
`code` de outra função, porque são **seis funções e quatro artefatos**, e três delas compartilham zip
com outra.

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

**Quem constrói é um alvo do Nx — a mesma decisão que o `apps/web` já tinha.** Eram ~250 linhas de
`infra/scripts/package.sh` fazendo à mão o que uma ferramenta de monorepo faz: escolher o que
reconstruir, guardar o resultado, e ser o mesmo comando na máquina, em CI e no deploy. Hoje o script
tem 76 linhas e **não sabe construir nada**: ele traduz as flags antigas para os alvos e roda os
quatro em série.

| alvo | perfil do Maven | zip |
|---|---|---|
| `lambda-http` (posts-api) | `-Plambda-http` | `infra/dist/posts-api-http.zip` |
| `lambda-sqs` (posts-api) | `-Plambda-sqs` | `infra/dist/posts-api-sqs.zip` |
| `lambda-stream` (posts-api) | `-Plambda-stream` | `infra/dist/posts-api-stream.zip` |
| `lambda` (tagging) | `-Plambda` | `infra/dist/tagging.zip` |

**Um alvo por ARTEFATO, e não um com o artefato em `--args`.** O Nx interpola `{args.x}` no comando,
mas em `outputs` ele só interpola `{options.x}` — então um alvo genérico teria o caminho do zip
dependendo de um argumento da linha de comando, e rodá-lo sem o argumento produziria
`infra/dist/.zip` em silêncio. Com um alvo por artefato o `outputs` é literal e o cache tem uma
entrada estável por artefato.

**O NOME NÃO PODE SER `package`.** O `@nx/maven` infere 132 alvos para cada app, um por fase do ciclo
de vida e um por execução de mojo — e `package` é um deles, com `dependsOn: ["^install"]`. Um alvo de
mesmo nome no `project.json` não substitui o inferido: ele se FUNDE com ele, e herdaria aquele
`dependsOn` — que é exatamente a armadilha do `nx-build-state.json` já documentada mais acima. Daí
`lambda-*`, que são os nomes dos perfis do Maven e não colidem com fase nenhuma.

**As configurações são as DUAS maneiras de compilar nativo, mais a de JVM:**

| configuração | o que passa ao Maven | o que produz |
|---|---|---|
| `native` (default) | `-Dnative` | binário da GraalVM **desta máquina** |
| `native-container` | `-Dnative -Pnative-container` | binário ELF/Linux, no builder do Mandrel |
| `jvm` | nada | o `quarkus-app`/`function.zip` de sempre |

No `apps/tagging` o gatilho é `-Dnative.tagging`, e isso é do desenho: como `-pl` não funciona neste
reator, um gatilho compartilhado faria aquele módulo compilar um binário em toda invocação que pede
native para o outro app. A configuração faz parte do comando escrito no componente do SST, que é onde se lê qual
empacotamento cada função recebe.

**O QUE VAI PARA A AWS É `:native-container`, e o default `:native` NÃO serve.** `native-image`
gera um executável do SISTEMA onde roda e não cruza: num Mac o `cc` tem alvo
`arm64-apple-darwin`, o `bootstrap` sai **Mach-O**, e o `provided.al2023` só executa ELF/Linux.
O modo de falhar é o pior que há — o deploy publica o zip e imprime `✓ Complete`, e a função
morre na invocação. O default continua sendo `native` porque quem constrói na máquina quase
sempre quer rodar a aplicação ali.

**E há um argumento de passagem**: `--extraFlags='...'` entra no comando do Maven e — por ser uma
opção do alvo — **entra no hash**, então o cache continua correto quando alguém experimenta uma flag.

**O cache, medido:** a primeira execução leva o que o Maven levar; a segunda, **96 ms**. Apagar o zip
e rodar de novo o **restaura do cache** em vez de reconstruir — que é o caso do clone novo e o do
`sst refresh`. E `touch` num fonte **não** invalida nada: o Nx compara CONTEÚDO, não data.

Os `inputs` são o named input `quarkusLambda`, no `nx.json`, e o que ele diz é o que importa:
`production` do próprio app (que já exclui `src/test/**` e `*.md`), mais `libs/**/pom.xml`,
`libs/**/src/main/**`, o `mvnw`, o `.mvn/`, o `collector.yaml` e o `build-env.sh`. Conferido com o
inspetor de hash do próprio Nx: **207 arquivos** para o `posts-api`, **118** para o `tagging`, e
ZERO vindos de `target/`, de `src/test/` ou de `.DS_Store` — o Nx monta o mapa de arquivos
respeitando o `.gitignore`, então os dois modos de falhar que custaram ~25 min de rebuild num deploy
deixaram de ser possíveis. E os dois apps ficam isolados: mexer no `apps/tagging` não invalida
artefato nenhum do `posts-api`.

**Três mecanismos, e cada um cobre o que o outro não cobre:**

- **`triggers`** com o fingerprint dos FONTES decide se o COMANDO roda. É dos fontes e não do zip
  porque na primeira vez o zip não existe — ele é produto do recurso, não insumo;
- **o cache do Nx** decide se rodar o comando RECONSTRÓI alguma coisa. É o que substituiu a checagem
  de mtime do script, e ganha nos três pontos em que aquela doía: compara conteúdo, respeita o
  `.gitignore` e conhece o grafo;
- **`assetPaths`** lê o zip DEPOIS do build e o entrega ao S3 como asset do Pulumi. A doc é explícita:
  *"a list of path globs to read after the command completes"*. **Eles não decidem se o build roda** —
  isso é `triggers`.

**Dois detalhes que custaram tempo de verdade:**

- os quatro builds são **serializados** por `dependsOn` encadeado, porque os perfis do Maven escrevem
  todos em `target/function.zip` e dois em paralelo se apagam. É a mesma necessidade que faz o
  `siteBuilder` do próprio SST usar um semáforo de 1. **O Nx não sabe disso**: rodar os alvos à mão
  com `run-many` exige `--parallel=1`, que é o que o `package.sh` passa;
- o `build-env.sh` existe porque duas coisas que o build exige são DINÂMICAS e não cabem numa linha
  de `env` no `project.json`: achar um JDK ≥ 21 (o shell desta máquina traz um 17, que não compila
  `release 21`) e conferir a toolchain nativa. Deixaram de ser conveniência quando o build passou a
  ser disparado pelo `sst deploy`, onde quem escolhe o ambiente é o CLI.

**O laço de desenvolvimento que tornou isso viável**: rodar o binário num container local
(`arm64v8/ubuntu` + o `application` montado) contra um Postgres de Dev Services. Cada ciclo
build→deploy→teste custa 11 minutos; build→container custa 7 e responde a mesma pergunta. Duas das
quatro falhas foram achadas assim. O que NÃO dá para testar assim é o que depende do ambiente do
Lambda — `AWS_REGION` (sem ela o publish no SNS falha com `Unable to load region`) e o DNS, que na
bridge do Docker devolve só IPv6.

**E há um efeito colateral que custou duas falhas de teste, porque ele não está no caminho da
subscription.** A estatística do Hibernate é da `SessionFactory` — ela conta os `PreparedStatement` de
TODAS as threads. Com um processor streaming lendo a linha de cada post que passa pelo event store, a
PRIMEIRA medição de `BatchLoadingE2ETest` e de `FederationEntitiesE2ETest` cai em cima da varredura
dele e a segunda não: medido, 11 statements para 1 post contra 4 para 5 posts. O teste acusava um N+1
inexistente e, pior, passaria a esconder um N+1 de verdade sob o ruído. Quem conserta é
`AbstractGraphQlE2ETest.statisticsOfAQuietDatabase()`, que abre a janela de medição só depois de duas
amostras iguais da contagem. **Medição que conta statements de uma aplicação com processor assíncrono
precisa esperar o silêncio** — e é isso que o controle provou: as mesmas duas classes passam 2/2 sem
nenhum processor pooled e falham 3/3 com ele.

**E há uma quarta razão, que é ARITMÉTICA e não arquitetura.** Uma subscription é uma conexão longa, e
o Lambda cobra e limita exatamente isso: o ambiente é CONGELADO entre invocações (um assinante parado
não recebe keep-alive nem escrita) e a conexão só existe enquanto o handler não retorna. Segurar um
assinante é, portanto, manter uma invocação viva — teto de 15 min, cobrada por duração.

Nesta função (arm64, 2048 MB): 2 GB × 900 s = 1800 GB-s × US$ 0,0000133334 = **US$ 0,024 por
assinante a cada 15 min**, ou **US$ 0,096/hora por assinante conectado**. Uma task Fargate de
0,25 vCPU / 0,5 GB custa US$ 0,0123/hora e atende todos eles. <b>UM assinante em Lambda custa oito
vezes a máquina inteira que serviria todos</b> — e ainda cai a cada 15 minutos.

**O que NÃO atravessa, medido e não deduzido:**

- **subscriptions, por WebSocket ou SSE.** O JAR do `quarkus-amazon-lambda-http` 3.39.2 não tem uma
  ocorrência de `vnd.awslambda.http-integration-response` nem de `RESPONSE_STREAM`: a resposta é um
  `APIGatewayV2HTTPResponse` montado inteiro em memória. E o `RequestStreamHandler` do
  `quarkus-amazon-lambda` **não é** response streaming — ele dá `InputStream`/`OutputStream` sobre o
  payload da invocação, bufferizado. Mas o transporte é a metade menor: `SimpleQueryBus.emitUpdate` é
  **em processo**, e quem apenda o `PostCreated` é outra função. Não há de onde emitir, e reconectar
  não resolve porque não é a conexão que falta, é a fonte;
- **o trace único.** Na entrada não há conector para instrumentar; na saída,
  `smallrye-reactive-messaging-aws-sns` 4.37.0 não tem pacote de tracing (o de SQS tem). Vale a regra
  que já está escrita mais abaixo: um trace pela metade é pior que nenhum, porque a lacuna parece
  latência. Quem responde por enquanto é o `AxonMetrics`.

**RODADO NA CONTA DE VERDADE** (`us-east-1`, via `./infra/aws/e2e.sh`): a saga fecha — post nasce na
versão 1 sem tag, volta na 2 com `Untagged` em ~50s, atualiza para a 3; as seis filas (três de
trabalho, três de DLQ) ficam vazias; `_entities` responde sem token. Os ~50s são quase todos cold
start de duas JVMs de 72 MB numa VPC — é o número que justifica o binário nativo.

E as duas afirmações sobre subscriptions foram confirmadas na stack, não só no JAR: `Accept:
text/event-stream` fica **25 segundos sem receber um byte** (nem o keep-alive de 15s chega) e o
upgrade de WebSocket morre no load balancer com **HTTP 400**.

**Três coisas que só apareceram na AWS**, e as três falham de forma enganosa:

1. **A função de migração não subia — pelo problema que ela existe para resolver.** O recorder do Axon
   toca o EntityManager na PARTIDA, o `validate` roda contra banco vazio e a aplicação morre com
   `missing table [accounts]` antes de qualquer handler existir. A saída é
   `QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=none` **só nessa função**; as outras quatro
   mantêm o `validate` e continuam recusando subir se entidade e schema divergirem.
2. **O atributo do conector SNS é `topic.arn`, com PONTO.** Com hífen é ignorado em silêncio
   (`SRMSG19504: Topic arn ... : null`) e a primeira publicação morre com
   `InvalidParameterException: TopicArn or TargetArn ... no value for required parameter`, que chega
   ao cliente GraphQL como `System error`/`invalid-parameter` sem mencionar configuração. O nome foi
   lido do BYTECODE do conector; ele também usa `group.id` e `email.subject`.
3. **O `iss` do Keycloak vem em minúsculas** — o DNS do ALB tem maiúsculas, mas o `iss` é montado do
   cabeçalho `Host`. DNS é insensível a caixa; `iss` não é.

**E a troca do Keycloak pelo Cognito cobrou o que o `V1__initial_schema.sql` tinha prometido.** A
primeira execução falhou com `usuário … já tem conta em KEYCLOAK` — não um bug, a regra de domínio: o
`sub` do Cognito não é o do Keycloak, e sem `identity_provider` no token os dois viravam o mesmo
provedor. O conserto foi o caminho que aquela migration já documentava: `COGNITO` no enum
`AuthProvider`, a `V6__cognito_provider.sql` refazendo o `ck_accounts_provider`, e um trigger
*pre token generation* **V1_0** pondo `identity_provider: "cognito"` no ID token. Resultado melhor que
"voltou a funcionar": a mesma pessoa atravessou a troca de emissor sem virar dois usuários —
`account linking: … de COGNITO ligada ao usuário …`, uma linha em `users` e duas em `accounts`.
**ENUM `AuthProvider` NOVO = MIGRATION NOVA**, e agora há precedente.

**Variável de ambiente sem default derruba a partida, inclusive de quem não serve HTTP.**
`quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL}` não tem default (a `%prod.` que ela substitui tinha),
e dar a variável só à função de API matou as outras duas do `posts-api` na partida: a extensão OIDC
inicializa com a APLICAÇÃO, não com a primeira requisição. Por isso o OIDC mora em `postsEnv`.

E uma de ferramenta: **`sst outputs` não existe no 4.17.1** (o comando imprime o help) — os outputs
só saem no `sst deploy`. Daí `infra/aws/discover.sh`, que pergunta à AWS pelos prefixos dos nomes.

O documento dessa migração, decisão por decisão, é `infra/aws/README.md`.


## O cliente de teste (`apps/web`)

Um Next.js (App Router) com Apollo Client, **aditivo como o resto**: nenhum arquivo de aplicação Java
foi alterado para ele existir. Sete páginas, uma por fluxo, e cada uma existe para provar uma coisa —
a tabela completa e as decisões estão em `apps/web/README.md`. O que precisa ser sabido daqui:

**Organização de arquivos é por FEATURE; atomic design é por COMPOSIÇÃO.** Não há pasta `atoms/` nem
`organisms/`: há a rota e o que ela precisa (`app/<feature>/_components`, `_hooks`), mais
`app/_components` para o que atravessa features e `components/ui` para as primitivas do shadcn. A
escala atômica continua existindo — ela só não é uma pasta.

**As props vêm de FRAGMENTOS, e isso é verificado pelo compilador.** O `client-preset` do
graphql-codegen gera *fragment masking*: o componente declara o fragmento de que precisa e recebe
`FragmentType<typeof Fragmento>`, um tipo opaco. Ler um campo fora do fragmento **não compila**, mesmo
que a query da página o tenha trazido. A demonstração está no par `PostCard_post` / `PostArticle_post`
— o card **não pede `content`**. Componente novo = fragmento novo no mesmo arquivo; a página só
espalha (`...PostList_connection`) e não enumera campo nenhum.

**A QUERY É DA PÁGINA, e ela é prefetchada no servidor.** Cada página com query tem um `query.ts` ao
lado do `page.tsx`; o server component a passa ao `PreloadQuery` de
`@apollo/client-integration-nextjs` e o componente de cliente lê o MESMO documento com
`useSuspenseQuery`. O resultado atravessa pelo stream do React — o HTML já chega preenchido e a
hidratação não refaz a requisição. As variáveis são constantes compartilhadas (`FEED_PAGE_SIZE`,
`LIVE_SNAPSHOT_SIZE`): servidor e cliente pedindo tamanhos diferentes não dá erro, dá uma segunda
requisição, e só a aba de rede conta. `errorPolicy: "all"` dos dois lados, porque com o default um
erro vira exceção no render do RSC e a rota inteira cai. Duas páginas não têm prefetch, e as duas
dizem o porquê no próprio arquivo: `/saga` mede um post que ainda não existe, e subscription não
termina.

**`possibleTypes` é gerado, não escrito.** `me` devolve a interface `User` e `_entities` a união
`_Entity`; sem o mapa, o casamento de `... on Author` no cache é heurístico e aplica o fragmento ao
tipo errado em silêncio. Quem o gera é o plugin `fragment-matcher` (`codegen.ts`), e quem o consome é
`lib/apollo/cache.ts`.

**A autenticação é por SERVER ACTION.** `app/actions/auth.ts` é a única porta para o Cognito: a senha
vai para o servidor do Next, que chama `InitiateAuth` e guarda os dois tokens em cookies `httpOnly`. O
navegador nunca vê o refresh token, e o ID token só chega a ele em memória. O bearer é o **ID token**
pela razão já documentada em `infra/aws/identity/index.ts`.

**O NAVEGADOR SÓ CONHECE `/api/graphql`** — o proxy da própria aplicação
(`app/api/graphql/route.ts`). Três coisas que isso resolve: o ID token **para de existir no
navegador** (quem o põe no header é o proxy, lendo o cookie `httpOnly`; `lib/apollo/token.ts` e o
`auth-link` sumiram, e o `Session` que desce para o cliente não tem mais o campo); não há CORS no
caminho; e existe um lugar só onde o streaming pode ser resolvido. O SERVIDOR não passa pelo proxy —
o `PreloadQuery` fala direto com a API, porque abrir uma conexão HTTP para si mesma custaria mais uma
invocação e latência.

**Subscriptions: o proxy REPASSA, e mais nada.** `/api/graphql` faz um `fetch` para o upstream e
devolve `upstream.body` — o corpo da resposta, como veio. Ele não interpreta o protocolo, não monta
frame nenhum e não sabe o que é uma subscription. A semântica é do domínio, servida pelo `posts-api`:
`onPostCreated` quando o post ALCANÇA a versão 2, `onPostUpdated` a cada mudança daí para a frente.

**Houve uma EMULAÇÃO aqui, e ela morreu de causa boa.** Enquanto o upstream era o API Gateway — que
monta a resposta inteira em memória —, o stream não abria, e o proxy consultava em laço recortando a
seleção do cliente para uma query `post(id:)` com alias. Deixou de ter função quando
`NEXT_PUBLIC_GRAPHQL_URL` passou a apontar para a Function URL com response streaming: o repasse
sempre funciona, e um caminho alternativo que nunca executa é um caminho que ninguém conserta. Saíram
com ela o `subscription-probe.ts`, o frame de anúncio de modo e o distintivo "stream real / emulado"
da página `/live`.

**O QUE SEGURA ESSE REPASSE É UM NÚMERO DE CDN**, e ele não está no código do proxy. O CloudFront
corta a origem que ficar calada por mais que o `OriginReadTimeout`, e um `sst.aws.Nextjs` SOLTO cria
a distribuição dele com **20 s literais** (`ssr-site.ts:996`) — conferido na conta. Um cold start do
`posts-api` leva 15–26 s (cronometrado duas vezes) e nesse intervalo o proxy não tem byte para
repassar: metade dos cold starts virava 504. O proxy já teve keep-alives só para preencher esse
silêncio. **Proxy calado NÃO é proxy parado — mas o CDN não sabe a diferença.**

Quem resolve é o `sst.aws.Router` de `infra/aws/edge/`: com o site ROTEADO, o SST espelha o `timeout`
do servidor na metadata da rota (`ssr-site.ts:1857`), e o número deixa de ser escrito duas vezes. **60
s é o teto**, e não por gosto: é o máximo do CloudFront para origem escolhida dinamicamente, e passar
disso faz a borda recusar a origem — uma tentativa com 360 s derrubou o site inteiro com 502.

**MEDIDO na stack depois da troca**: uma subscription pelo proxy levou **26 s até o primeiro byte** e
entregou o evento normalmente. Com os 20 s de antes, essa conexão teria sido cortada — é a prova de
que o teto era o gargalo, e não o proxy.

**E há um efeito que nenhuma configuração conserta: CADA ASSINANTE CONCORRENTE PAGA UM COLD START.**
Uma conexão SSE segura a invocação enquanto estiver aberta, e um container do Lambda atende uma
invocação por vez — então o segundo assinante simultâneo cai obrigatoriamente num container novo.
Medido: duas conexões abertas juntas levaram 27 s, com a função já quente. É a mesma aritmética que
torna `warm: 1` insuficiente aqui: ele aquece UM container, e o segundo assinante não o encontra
livre. O que remove isso é concorrência provisionada (≈US$ 21/mês para um container de 2 GB) ou o
binário nativo — o `libs/axon-native-support` existe para isso.

**CORS é do API GATEWAY, não da aplicação** (`infra/aws/support/http-api.ts`). Assim o preflight não
acorda uma JVM de 72 MB. `allowOrigins: ["*"]` porque liberar a URL do site criaria dependência
circular com o componente que precisa da URL da API. Em dev o Quarkus não tem CORS ligado: use
`QUARKUS_HTTP_CORS=true QUARKUS_HTTP_CORS_ORIGINS='http://localhost:3000'` no `quarkus:dev`.

**O build é do Nx, o empacotamento é do OpenNext, a publicação é do SST.** O alvo
`web:open-next-build` depende de `build`, e é ele que o `sst.aws.Nextjs` chama (`buildCommand`) — uma
definição só de como se constrói o site, com cache. Duas linhas do `next.config.ts` existem por causa
disso e nenhuma é afinamento: `outputFileTracingRoot` apontando a raiz do monorepo (sem ela o bundle
sai sem os pacotes que o pnpm deixou em symlink, e o erro só aparece em runtime) e `output:
"standalone"` ligado por `INFRA_PROVIDER=aws` (que o `environment` do componente injeta no build).

**Armadilhas já pagas**, e as primeiras falham em silêncio:

0. **NÃO deixe `next dev` rodando durante um `sst deploy`.** O `open-next.config.ts` tem
   `buildCommand: "exit 0"` — ele EMPACOTA `.next`, não o constrói —, e o dev server reescreve esse
   diretório continuamente. O que sobe vira um build de desenvolvimento: o HTML referencia
   `/_next/static/chunks/main-app.js` (sem hash, nome que só o dev usa), o S3 responde 403, a página
   não hidrata, o formulário cai no POST nativo e a server action morre com
   `TypeError: a[d] is not a function` no `webpack-runtime`. Nenhuma dessas mensagens aponta para a
   causa.
1. **Login: toda navegação SUAVE para `/feed` quebrava o cabeçalho.** O layout lê o cookie, e o Next
   faz prefetch dos `<Link>` visíveis — o payload ANÔNIMO do layout já está no Router Cache quando o
   login acontece. Em `next dev` (sem prefetch) nada aparece. Foram TRÊS fontes de navegação suave,
   removidas uma a uma: o `redirect()` da própria ação; o `revalidatePath("/", "layout")` nela (que
   revalida `/login`); e — a que sobreviveu às outras duas — o fato de **o Next re-renderizar a rota
   atual depois de toda server action**, o que disparava o `redirect` que havia no `page.tsx` de
   `/login`. Desenho final: a página de login não redireciona (quem tem sessão vê um cartão), e o
   formulário chama `signIn` à mão — não por `useActionState` — para `window.location.assign` rodar
   no mesmo tick da resposta. Sessão nova é documento novo.
2. **`Query.posts` é ordem de criação CRESCENTE.** `posts(first: 10)` são os dez mais ANTIGOS; um post
   criado agora entra no fim. O painel de tempo real ficou mudo por isso. Sem `last`/`before` no
   schema, `/live` pede a página inteira (teto 100) e olha a cauda — acima de 100 posts os mais novos
   somem, e o conserto honesto é paginação reversa na API.
3. **`relayStylePagination()` sem `keyArgs` funde TODA leitura de `posts`** — a de `/live`
   (`first: 100`) com a do feed (`first: 6`), e o feed voltava com cem cards. `keyArgs: ["first"]`
   separa as duas sem quebrar o "carregar mais".
4. `@graphql-typed-document-node/core` precisa ser dependência DIRETA (o pnpm não expõe transitiva, e
   sem ela todo `graphql()` vira `unknown`); o `Button` do shadcn *base-nova* é Base UI e usa
   `render={<Link/>}`, não `asChild`; e `secure: true` no cookie mata o login em `next dev`, porque a
   origem é http.

```bash
pnpm --filter @axonposts/web dev        # http://localhost:3000
pnpm --filter @axonposts/web codegen    # regenera src/gql/ (roda junto com o build)
pnpm --filter @axonposts/web schema:pull GRAPHQL_URL   # atualiza apps/web/schema.graphql
npx nx run web:open-next-build          # o que o SST chama no deploy
```

## Testes

Surefire roda tudo em `./mvnw test`, inclusive os `*E2ETest` — **Docker precisa estar de pé**.

### TRÊS NÍVEIS, DOIS COMANDOS — e quem os conhece é o Nx, não o `package.json`

`pnpm test` era `./mvnw test` e passou a ser `nx run-many -t test-unit`. A troca não é de ferramenta:
é de QUEM SABE o que cada nível é. Um `./mvnw test` no script da raiz não tem como ser cacheado, não
tem como ser afetado por um diff, e não tem onde um segundo tipo de teste entrar.

| comando | alvo do Nx | quem declara | o que roda |
|---|---|---|---|
| `pnpm test` | `test-unit` | `apps/posts-api` | domínio, `*CommandTest`, fiação e schema — 94 testes, ~26s |
| `pnpm test:e2e` | `test-e2e` | `apps/posts-api` | os `*E2ETest`, com Dev Services, num processo |
| `pnpm test:e2e` | `test-e2e` | `apps/posts-api-e2e` | a saga entre DOIS processos, com broker de verdade |

**Os dois níveis pesados têm o MESMO NOME DE ALVO de propósito.** É o que faz `nx run-many -t test-e2e`
rodar os dois numa esteira só, e é a razão de a separação ser por alvo e não por script: um nível novo
que provisione coisas entra declarando `test-e2e`, sem que ninguém edite um comando.

**`--parallel=1` no `test:e2e` NÃO é afinamento.** Os perfis do Maven escrevem todos em `target/`, e o
alvo `build` de `apps/posts-api-e2e` empacota enquanto o `test-e2e` do `posts-api` está rodando o
Surefire no mesmo diretório. É a mesma necessidade que serializa os quatro `lambda-*`.

**Os nomes NÃO podem ser `test` e `e2e`.** `test`, `test-ci`, `integration-test` e `verify` são alvos
INFERIDOS pelo `@nx/maven` nos projetos Maven — e um alvo de mesmo nome no `project.json` não
substitui o inferido: ele se FUNDE com ele e herda o `dependsOn: ["^install"]`, que é a armadilha do
`nx-build-state.json` documentada mais acima. Conferido: `test-unit` e `test-e2e` saem com
`dependsOn: null`. É o mesmo motivo de os alvos de empacotamento se chamarem `lambda-*`.

**`-Dtest='!*E2ETest'` sozinho NÃO basta, e isto foi medido.** `-Dtest=` **sobrescreve os includes
default do Surefire**, então a exclusão passa a arrastar os `*NativeIT` — que são
`@QuarkusIntegrationTest`, precisam do binário nativo e falham com três erros. O filtro é
`!*E2ETest,!*IT`. Junto vem `-Dsurefire.failIfNoSpecifiedTests=false`, para os módulos do reator que
não têm teste nenhum.

### `apps/posts-api-e2e`: a saga como APP, e não como script

O que era `docker/e2e/run.sh` é hoje um app de teste do Nx — Vitest e TypeScript, porque o teste da
saga **já era** TypeScript (`saga-choreography.mjs`), e o que estava em shell era só a provisão. Os
dois arquivos FORAM REMOVIDOS quando o app passou verde: a mesma saga afirmada em dois lugares é a
mesma regra em dois lugares, e o primeiro ajuste as separa em silêncio.

Ele declara `implicitDependencies` nos DOIS serviços, e é isso que o põe no grafo: mexer em
`apps/tagging` invalida o artefato dele, e um `nx affected` o alcança sem ninguém listar nada.

```
apps/posts-api-e2e/
  src/specs/     as SPECS, com sufixo `.e2e.spec.ts` — o sufixo diz o NÍVEL
  src/support/   o MECANISMO: a stack, os dois processos, o event store, o broker, a borda
  src/global-setup.ts
```

**A spec leva `.e2e.spec.ts` e mora em `src/specs/`**, e as duas coisas dizem a mesma: o `include` do
Vitest é `src/specs/**/*.e2e.spec.ts`, então `src/support/` fica de fora do padrão de propósito — o
que há lá é mecanismo, e mecanismo não é spec. Um nível novo (`*.integration.spec.ts`, digamos) entra
como um `include` a mais, sem mover nada.

**EMPACOTAR saiu do teste.** O script fazia `clean package` "para medir o que um build do zero
produz"; aqui isso é o alvo `build`, do qual o `test-e2e` depende, e quem garante o mesmo é o hash do
CONTEÚDO das fontes que o Nx calcula. **E o `clean` não volta**, porque ele foi MEDIDO de novo
neste recorte: seis empacotamentos idênticos, três com `clean` e três sem, deram **1 sucesso em 3 dos
dois lados** — a intermitência do augmentation é a mesma com ou sem ele. É a confirmação
independente do que a seção do `posts-api` já dizia ao descartar "estado sujo em `target/`". **UM alvo e não dois**, porque `-pl` não funciona neste reator:
um `./mvnw package` produz os `quarkus-app` dos dois serviços, e dois alvos rodariam o reator inteiro
duas vezes disputando `target/`.

**A prontidão é uma estratégia, não um `if`** (`support/service.ts`). O `posts-api` tem `/q/health`;
o `tagging` **não tem porta nenhuma** — `quarkus-opentelemetry` depende de `quarkus-vertx` e não de
`quarkus-vertx-http`, que é o que permite instrumentá-lo sem lhe dar um endpoint. O único sinal de que
ele subiu é a linha no log dele. Duas respostas para a mesma pergunta é o que faz de `HttpHealth` e
`LogLine` duas implementações de `Readiness`.

**`fileParallelism: false` e `singleFork`**: os testes disputariam o MESMO event store e o mesmo
broker. Paralelismo aqui não acelera nada — ele muda o que está sendo medido. E `retry: 0`, pelo mesmo
motivo: um teste que só passa na segunda tentativa esconde exatamente o que este app existe para medir.

**O `globalSetup` roda NOUTRO PROCESSO**, então o objeto que ele cria não atravessa para os testes.
Não é problema: `ChoreographyStack` é uma fachada sem estado sobre o Docker e o HTTP, e o arquivo de
teste constrói a dele olhando para a mesma stack. O que não atravessa é o processo filho de cada
serviço — por isso quem os derruba é o `teardown` de lá.


**O `vitest` roda ATRÁS do `build-env.sh`, e não é para construir nada.** O script garante um JDK ≥ 21
e exporta `JAVA_HOME`; `support/service.ts` honra essa variável em vez de chamar o `java` do PATH. A
razão foi MEDIDA e o modo de falhar é o pior que há: o shell desta máquina traz um JDK 17, e um
`quarkus-run.jar` compilado com `release 21` sai nele com **código 1 e log vazio** — sem
`UnsupportedClassVersionError`, sem uma linha em stderr. O sintoma é indistinguível de "a aplicação
morreu na partida", e a espera de prontidão levava 120s para dizer nada.

Duas consequências ficaram no código, e as duas valem por si:

1. **a busca pelo JDK não é reescrita em TypeScript.** Quem a faz é o `build-env.sh`, que já a fazia
   para o build — duas buscas divergiriam, e a que estivesse errada seria a que ninguém olha;
2. **`Service` delata a MORTE PRECOCE.** Um `exit` do processo interrompe a espera na hora, com o
   código de saída e — quando o log está vazio — a frase que aponta para o JDK. Como o script faz
   `cd` para a raiz, o `vitest` recebe `--root apps/posts-api-e2e` em vez de `cwd`.

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
  decide diferente — o `TransactionManager`, o tipo do id de cada entidade, e a cobertura das
  propriedades de processor (`subscribingprocessor.namespaces` mais os `pooledprocessor.<nome>`). As duas primeiras valem por **ausência** de `@DefaultBean` (sumir não
  quebra compilação); a terceira varre o `BeanManager` atrás de `@EventHandler` e falha se algum pacote
  ficou de fora das DUAS listas — a do subscribing e a de cada pooled nomeado. Quem fica de fora não dá
  erro: cai num pooled anônimo, com token store JPA, e a entrega que aquele pacote precisava deixa de
  valer em silêncio.
- **Federação** (`FederationSchemaTest`, `FederationEntitiesE2ETest`): o primeiro lê o `_service { sdl }`
  — que é o que o `rover` leria, e onde `@key`/`@shareable` aparecem, coisa que a introspecção não mostra.
  O segundo chama o `_entities` de verdade: é o único lugar onde um argumento renomeado, um `@Id` a mais
  ou um `@NonNull` no elemento da lista falham. Inclui o custo, pela mesma propriedade do
  `BatchLoadingE2ETest`: N representações precisam custar os mesmos statements que 1.
- **Entre PROCESSOS** (`apps/posts-api-e2e`, via `pnpm test:e2e`): sobe a infraestrutura, roda as migrations fora do processo,
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

## A esteira (`tools/github/` e `.github/workflows/`)

**A REGRA: uma action principal, composta de subactions.** As subactions fazem uma coisa cada; os
workflows não conhecem nenhuma delas, só a principal. Trocar como se roda um teste é mexer num
arquivo, e nenhum workflow sabe que mudou.

```
tools/github/
  setup/        Node, pnpm, JDK e os dois caches (~/.m2 e .nx/cache)
  test/         `pnpm test` + os relatórios do Surefire como artefato
  test-e2e/     `pnpm test:e2e` + relatórios E OS LOGS DAS DUAS APLICAÇÕES
  web/          lint, typecheck e build do cliente, num `run-many` só
  deploy-sst/   o `sst deploy` e o GitHub Deployment  ← a subaction que já existia
  ci/           A PRINCIPAL da integração: `setup` + as checagens pedidas
  deploy/       A PRINCIPAL do deploy: `setup` + credenciais da AWS + `deploy-sst`
.github/workflows/
  ci.yml        três jobs, os três chamando `ci` com um `checks` diferente
  deploy.yml    workflow_dispatch com o stage
```

**A action `ci` tem um input `checks`, e ele existe por um motivo estrutural.** Uma action composta
roda num job só — então uma principal que fizesse as três checagens em sequência poria o lint do
cliente web atrás de um nível que sobe Postgres, Keycloak, RabbitMQ e duas JVMs. Com o `checks`, o
MESMO ponto de entrada serve a um job por checagem: paralelismo no workflow, e a preparação do
ambiente declarada em um lugar só. `checks: all` também funciona, e é o que faz sentido num gancho
local.

O casamento é com vírgulas nas pontas (`,${checks},`) e não com `contains` cru: sem elas, `test`
casaria dentro de `test-e2e` e o nível de baixo rodaria junto com o de cima, calado.

**O JDK da esteira é o 21, e não o 25.** O `release` do projeto é 21, e este documento registra o
augmentation do Quarkus 3.39 falhando de forma INTERMITENTE sob a JVM 25 — que ele não suporta. Numa
esteira, intermitência é pior que lentidão.

**O `test-e2e` derruba o compose ao fim (`down -v`), e o local não.** Na máquina os containers ficam
de pé de propósito, para a próxima execução não pagar a subida; num runner efêmero isso não vale
nada, e um volume sobrevivente entre jobs valeria menos ainda.

**Não há passo de empacotamento antes do `deploy`.** Cada função declara o alvo do Nx que a empacota
(`QuarkusBuild.buildCommand`, em `infra/aws/support/functions.ts`) e o `triggers` do Pulumi decide se
o comando roda. Um `package.sh` antes do deploy construiria FORA do grafo, e o SST reconstruiria
mesmo assim.

**As credenciais do Better Stack são obrigatórias no deploy**, e vão pelo `GITHUB_ENV` e não por um
`env:` no passo: assim todo passo seguinte as enxerga, inclusive os de dentro do `deploy-sst`. Na
máquina elas vêm do `.env` da raiz, que o SST carrega sozinho; num runner não há `.env`, e
`requiredEnv` FALHA o deploy — de propósito, porque um coletor sem destino sobe, não reclama, e some
com a telemetria em silêncio.

Segredos que o `deploy.yml` espera: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `BETTER_STACK_URL`
e `BETTER_STACK_API_KEY`.

### O LINT — e as DUAS armadilhas de um monorepo que é quase todo Java

**CADA SISTEMA TEM O SEU**, e a herança é um import. `eslint.base.config.mjs` na raiz é o que todos
compartilham; cada projeto tem um `eslint.config.mjs` que começa por `...baseConfig` e acrescenta só o
que é dele. `pnpm lint` é `nx run-many -t lint` e **não lista ninguém** — quem tem o alvo é quem tem a
config.

| projeto | config | o que acrescenta |
|---|---|---|
| `web` | `apps/web/eslint.config.mjs` | o preset do Next |
| `posts-api-e2e` | `apps/posts-api-e2e/eslint.config.mjs` | as regras do Vitest |
| `infra` | `infra/eslint.config.mjs` | as duas exceções do SST |
| `dev.manuelantunes:quarkus-axon-graphql-posts` | — | Spotless, nos OITO módulos Maven |

**A forma é do Nx, e ela existe por um motivo mecânico**: em flat config o ESLint usa **UM** arquivo,
o mais próximo do diretório de onde ele roda, e cada alvo `lint` roda com `cwd` no próprio projeto.
**Não há herança automática entre arquivos de config** — a herança é o `import baseConfig`.

O que fica na BASE é o que não pode divergir: o plugin do Nx, os ignores globais, o
`@nx/enforce-module-boundaries` (que lê o grafo do workspace INTEIRO, então escrevê-lo duas vezes
seria a mesma regra em dois lugares) e as três regras de TypeScript que descrevem como este monorepo
escreve código.

**`infra` é projeto do Nx por UMA razão: ter o próprio lint.** São 22 arquivos TypeScript que não
pertenciam a pacote nenhum do workspace, e por isso não eram lintados por nada. O `infra/project.json`
existe só para o `@nx/eslint/plugin` inferir o alvo a partir do `infra/eslint.config.mjs`.

**Ele custou DUAS exceções, e nas duas o SST está certo e a regra errada:**

- `triple-slash-reference` — eram **21 das 27** violações do diretório, a única sistemática. O
  `/// <reference path=".sst/platform/config.d.ts" />` é como o SST põe os tipos gerados dele em
  escopo (`$config`, `$app`, `sst.aws.*`, o global `aws`). Não há import equivalente porque não há
  módulo: é um arquivo de declarações. Trocar por `import` deixaria o arquivo SEM TIPO NENHUM;
- `no-empty-object-type` com `allowInterfaces: "with-single-extends"` — não desligada, CONFIGURADA.
  `interface MigratorArgs extends Omit<QuarkusFunctionArgs, "timeout" | "memory"> {}` é o padrão de
  dar nome a um tipo derivado numa API pública. A predecessora depreciada `no-empty-interface` saiu,
  porque acusava as mesmas linhas uma segunda vez.

Sobraram **2 avisos**, os dois no código de infraestrutura: um `userGroup` atribuído e nunca usado
(`identity/index.ts`) e um `!` (`support/functions.ts`). Ficaram como AVISO de propósito — são
decisões de quem escreveu a infra, não do lint.

**NÃO HÁ alvo `lint` na raiz, e a ausência é o desenho.** O projeto `root` do Nx é o **reator Maven**
(o `@nx/maven` reivindica a raiz), não um projeto de JavaScript — o `nx show project root` traz as 50
fases do ciclo de vida. `nx run-many -t lint` resolve os projetos sozinho; um alvo na raiz seria uma
lista escrita à mão para dizer o que o grafo já sabe.

A consequência, e ela é real: **o `sst.config.ts` não é lintado por alvo nenhum.** Ele tem de morar na
raiz porque é onde o CLI do SST o procura. O `eslint.config.mjs` da raiz continua existindo — é o que
o editor resolve e o que um `npx eslint sst.config.ts` usa —, e ele traz as exceções daquele arquivo,
incluindo `enforce-module-boundaries` desligada: o `await import("./infra/aws")` É um import relativo
para dentro de outro projeto, e tem de ser, porque os módulos de `infra/` criam recursos no topo e
importá-los estaticamente os avaliaria antes de `app()` rodar.

**O plugin EXCLUI os módulos Maven, por escrito.** Ele já não inferiria o alvo para um projeto sem
um `.ts`/`.js` sequer, mas o `exclude` do `nx.json` diz isso de propósito: uma garantia implícita é
uma garantia que ninguém lê antes de quebrar.

**O `includedScripts: []` no bloco `nx` da RAIZ conserta uma recursão, e ela foi observada.** A raiz
é um projeto Nx (`"nx": { "name": "root" }`), então cada script do `package.json` dela vira um alvo —
inclusive `lint`, que É `nx run-many -t lint`. O resultado é `root:lint -> root:lint`, e o Nx o
detecta e falha a esteira inteira. `includedScripts: []` diz o que esses scripts são: portas de
entrada para gente, não alvos. Vale para `dev`, `test` e `test:e2e` pela mesma razão — os quatro são
invólucros de `nx run-many`.

**O `enforce-module-boundaries` precisa de um `allow` para a própria config.** `apps/web/eslint.config.mjs`
IMPORTA a da raiz — um import relativo que atravessa a fronteira do projeto, que é exatamente o que a
regra proíbe. Sem a exceção, compor as configs em vez de duplicá-las seria um erro de lint.

As `depConstraints` são uma só e permissiva, e isso é deliberado: hoje não há lib JS compartilhada
neste monorepo — o domínio compartilhado é Java, e quem o separa é o reator do Maven. Uma matriz de
`scope:`/`type:` seria fronteira desenhada contra dependência que não existe, e regra que nunca
dispara é regra que ninguém mantém. Quando nascer a primeira lib JS, o lugar de apertar é essa lista.

**`apps/posts-api-e2e` tem config PRÓPRIA**, e o que ela acrescenta não é estilo. As regras do
`@vitest/eslint-plugin` ligadas ali pegam os jeitos de uma suíte MENTIR que nenhum compilador vê —
`it` sem `expect`, `it.only` esquecido, título repetido, `expect` fora de um teste. Num app cujo
trabalho inteiro é afirmar coisas sobre dois processos e um broker, uma suíte que mente é pior que
suíte nenhuma: ela fica verde enquanto a saga não fecha. E `src/support/` fica de fora dessas regras
pela mesma razão que fica de fora do `include` do Vitest — ali é mecanismo, não spec.

A regra `no-standalone-expect` pegou uma de verdade na primeira execução: havia um `expect` dentro do
`beforeAll`, que falha como erro de HOOK e não nomeia o que se esperava. O conserto não foi calar a
regra — foi `PostsApi.subscribe` passar a RECUSAR um status diferente de 200, porque uma subscription
que não abriu não é uma subscription.

#### `consistent-type-definitions` é AUTO-CORRIGÍVEL e o auto-conserto QUEBROU o build

A regra da base exige `interface` no lugar de `type` para tipo de objeto, e o `--fix` converteu 11
declarações de uma vez. Uma delas não podia ser convertida, e o compilador é quem disse:

```
entities-probe.tsx(65,39): error TS2352: Conversion of type 'Representation[]' to type
'Record<string, unknown>[]' may be a mistake because neither type sufficiently overlaps
```

**Um alias de tipo de objeto ganha ÍNDICE IMPLÍCITO; uma interface não.** É por isso que
`Representation[]` deixava de ser atribuível a `Record<string, unknown>[]` — que é como as
representações chegam ao `_entities`. O arquivo voltou a `type`, com `eslint-disable-next-line` e a
razão escrita ao lado.

A lição é sobre a ORDEM, e ela vale para qualquer `--fix`: **rodar o typecheck depois de um conserto
automático não é zelo, é parte do conserto.** Aqui o lint ficou verde e o build quebrou.

#### `pnpm lint` confere, `pnpm lint:fix` conserta — e `--fix` NÃO é a interface

`lint:fix` é `nx run-many -t lint -c fix --skip-nx-cache`: uma configuração `fix` em cada um dos três
alvos, `eslint . --fix` nos dois de JavaScript e `spotless:apply` no do Java.

**`pnpm lint --fix` foi desativado de propósito, e o motivo é o de sempre aqui: ele funcionava PELA
METADE.** O `forwardAllArgs` do `nx:run-commands` é `true` por default, então a flag era repassada
crua — o ESLint a entendia e consertava, o `./mvnw` não e respondia com a tela de ajuda dele mais um
código de saída 1. Com `forwardAllArgs: false` nos três, `--fix` passa a ser ignorado em toda parte,
uniformemente, e quem conserta é `lint:fix`.

**A configuração `fix` dos alvos de ESLint repete o comando em vez de usar a opção `args`**, e isso
também foi medido: `forwardAllArgs: false` bloqueia `args` junto, então a configuração rodava sem o
`--fix` e **dizia que tinha passado**. Travado por um teste manual com violação plantada dos dois
lados — `let` que devia ser `const` no TypeScript e espaço no fim da linha no Java.

#### A indentação do TypeScript é 2, e quem manda é o `.editorconfig`

Ele está na raiz, diz `indent_size = 2` para todos os arquivos, e `apps/web` o segue. Os arquivos de
`apps/posts-api-e2e` nasceram com 4 e foram reindentados. **Não há Prettier**, aqui nem em `apps/web`:
o `.editorconfig` já declara a convenção, e acrescentar um formatter só para o app de teste deixaria
um pacote JS formatado e o outro não.

A régua do Java continua sendo 4 — o `[*]` do `.editorconfig` não descreve o que os 227 arquivos Java
fazem, e não é ele que decide lá. Ver logo abaixo por que não há formatter do lado Java.

### O LADO JAVA: Spotless para o arrumável, Error Prone para o defeito

O `CLAUDE.md` dizia que "não há plugin de lint/format configurado" e que o gate era o compilador.
Continua sendo — só que agora o compilador sabe mais.

**A divisão é essa, e ela decide onde cada coisa falha:**

| | o que pega | como se conserta | onde falha |
|---|---|---|---|
| **Spotless** | import morto, espaço no fim da linha, newline final | `./mvnw spotless:apply` | o alvo `lint` |
| **Error Prone** | defeito de código: `equals`, `Locale`, `String.split` | lendo o código | o BUILD |

O que é arrumável por máquina não precisa derrubar um build; o que exige alguém ler, precisa.

**NÃO HÁ FORMATTER, e a ausência foi medida.** 227 arquivos, indentação de 4 espaços, **dez** linhas
acima de 120 colunas: o código já está formatado. Um `google-java-format` ou um `palantir` reescreveria
os 227 para impor a régua dele, requebrando o Javadoc longo em português e levando o `git blame` junto.

**Também não há `importOrder`, pela razão oposta.** Ela foi ligada, medida e desligada: a ordem dos
grupos de import **não é consistente** neste código — uns arquivos começam por `dev`, outros por
`java`, outros por `org`. Não existindo convenção a preservar, a regra não estaria arrumando nada,
estaria ESCOLHENDO uma e reescrevendo quase tudo para impô-la.

O que sobrou custou **5 linhas**: cinco imports não usados, em três arquivos.

**O Spotless NÃO tem `<executions>`**, e são dois efeitos de uma vez: `package` e `test` não pagam por
ele, e o `@nx/maven` não passa a inferir um alvo por execução de mojo — que é a armadilha do
`nx-build-state.json`.

**O alvo `lint` do Java mora em `apps/posts-api` e cobre o REATOR INTEIRO.** É a mesma forma do
`test-unit`: `-pl` não funciona neste reator, então todo alvo do lado Java roda da raiz. Ter o MESMO
NOME dos alvos de ESLint é o que faz `pnpm lint` cobrir o monorepo numa invocação.

**As TRÊS armadilhas do Error Prone, e as três foram pagas aqui:**

1. **`annotationProcessorPaths` de um filho SUBSTITUI o do pai, não soma.** `apps/posts-api` (MapStruct)
   e `axon-native-support/deployment` (o processador da extensão) declaram o seu, então herdavam o
   `-Xplugin:ErrorProne` dos `compilerArgs` e perdiam o jar que o implementa: `plug-in not found:
   ErrorProne`, no meio do reator. O conserto é `combine.children="append"` nos dois.
2. **O JDK 16+ exige `add-exports`/`add-opens` para os internos do javac**, e eles são do processo que
   RODA o javac — por isso estão em `.mvn/jvm.config` e não no pom. Sem eles o compilador morre antes
   de compilar o primeiro arquivo.
3. **`XDcompilePolicy=simple` e `should-stop=ifError=FLOW` não são afinamento**: sem eles o javac roda
   o plugin numa política incompatível e o Error Prone nem carrega.

**TRÊS CHECAGENS DESLIGADAS, e as três são de Javadoc.** Dos 17 achados do primeiro reator inteiro,
**8 eram delas**: `InvalidParam` (falso positivo — em `Post.java` ele acusa `{@code authors}` de ser o
parâmetro `author` escrito errado, quando `authors` ali é o nome da TABELA), `EscapedEntity` (acusa
`<b>` dentro de `{@code}`, que é como a prosa daqui é escrita) e `MissingSummary` (exige frase-resumo;
os Javadoc daqui abrem com o contexto da decisão). Um lint que grita onde não há defeito é um lint que
se aprende a ignorar INTEIRO.

**O que sobrou são 9 avisos, todos sobre CÓDIGO**, e eles seguem como aviso de propósito — virar erro
quebraria o build hoje, e a decisão de consertar cada um é de quem conhece a intenção:

```
6  MissingOverride         implementação sem @Override
2  StringCaseLocaleUsage   toLowerCase() sem Locale — quebra em turco, e um deles é o Email
1  StringSplitter          String.split(String) tem comportamento surpreendente
```

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

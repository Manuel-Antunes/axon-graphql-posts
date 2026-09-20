# A mesma saga, em Lambda

Seis funções, um topic, três filas (mais três DLQ) e um user pool do Cognito. O código de domínio, de
aplicação e de apresentação é **exatamente o mesmo** — nenhum arquivo que existia antes desta migração
foi alterado.

```
                      ┌──────────────────────────────────────────────┐
  API Gateway ───────►│ PostsApi            posts-api -Plambda-http   │
                      │ POST /graphql, SDL, _service, _entities, OIDC │
                      └───────────────┬──────────────────────────────┘
                                      │ posts.PostPreCreated / Updated / Deleted / Restored
                                      ▼
                        ╔═════════════════════════════╗
                        ║  SNS FIFO  axonposts-events ║   ← o exchange topic, traduzido
                        ╚══╤═══════════╤═══════════╤══╝
      filter: PostPreCreated│  PostUpdated│         │PostCreated
                            │  PostDeleted│         │
                            │ PostRestored│         │
                            ▼             ▼         ▼
                   ┌────────────┐ ┌────────────┐ ┌──────────────┐
                   │ SQS FIFO   │ │ SQS FIFO   │ │ SQS FIFO     │
                   │ precreated │ │ changes    │ │ completed    │
                   └─────┬──────┘ └─────┬──────┘ └──────┬───────┘
                         ▼              ▼               ▼
                 TaggingDecide   TaggingReplicate   PostsApiInbox
                  tagging.zip      tagging.zip      posts-api-sqs.zip
                         │
                         └─ posts.PostCreated ─► de volta ao topic
```

## Como ler isto, se você conhece a versão em RabbitMQ

Não é um desenho novo. É `axonposts.events` peça por peça:

| RabbitMQ | AWS | onde está declarado |
|---|---|---|
| exchange topic `axonposts.events` | topic SNS FIFO | `messaging.ts` |
| binding de cada fila | filter policy da subscription | `messaging.ts` |
| routing key `posts.PostCreated.<id>` | atributo `axon-message-name` | `AwsEventAttributes` |
| fila por fatia do fluxo | fila SQS FIFO por fatia | `messaging.ts` |
| `@Incoming` de cada canal | **o mesmo `@Incoming`** | inalterado |
| `@AxonOutbox(namespaces="posts")` | **o mesmo `@AxonOutbox`** | inalterado |

A troca de protocolo custou **uma linha de `.properties` por canal**, e foi isso que o
`ChannelAddressing` existia para comprar — está escrito no Javadoc dele desde antes: *"Protocolo novo
= uma `ChannelAddressing` a mais"*.

## FIFO não é afinamento

`apps/tagging` **escreve** no stream do `Post` — é ele que apenda o `PostCreated` que completa o post.
Num event store em *aggregate mode* a posição de um append vem de ter lido o stream antes, então um
`PostUpdated` que ultrapasse o `PostPreCreated` do mesmo post faz o append seguinte cair em
`duplicate key value violates unique constraint "uk_aggregateevententry_aggregate"`.

O `MessageGroupId` é o id do agregado — e ele **já existia**: é o `EventAddress.orderingKey()`, que
era o terceiro segmento da routing key e lá não ordenava nada (o próprio `application.properties`
admitia isso por escrito). Aqui ele passa a ser o que faz a ordem existir. Posts diferentes seguem em
paralelo; dois eventos do mesmo post, não.

Numa fila **standard** esta saga não funciona. Não "funciona pior": quebra, de forma intermitente e
proporcional à carga.

## Os arquivos

```
sst.config.ts              na RAIZ porque é onde o CLI o procura — e não contém infraestrutura:
                           app() mais um `await import("./infra/aws")` dentro de run()
infra/dist/                os quatro zips (gerados pelos alvos do Nx, ignorados pelo git)
infra/scripts/             package.sh (atalho para os alvos), build-env.sh, migrate.sh,
                           discover.sh, e2e.sh
infra/aws/
  index.ts                 a fachada: ordem de carga e outputs. Não cria nada.
  support/                 as DEFINIÇÕES — classes e tipos. Nada aqui cria recurso ao ser importado.
    functions.ts             QuarkusFunction, QueueWorker, Migrator, StreamingFunction
                             + Artifact, o objeto de valor que diz onde o zip está
    http-api.ts              HttpApi
  network/                 o VPC
  data/                    os DOIS event stores
  messaging/               topic.ts, queues.ts, routing.ts — o "exchange" traduzido
  identity/                o user pool do Cognito + o trigger que emite `identity_provider`
  compute/                 as SEIS funções e o API Gateway
    platform.ts              onde `support/` encontra os recursos: papel, rede e artefatos
    role.ts, environment.ts, api.ts, workers.ts, migrations.ts
```

A seta aponta sempre para o mesmo lado: **quem define não conhece quem instancia**, e quem instancia
infraestrutura de base não conhece quem a consome. `compute/platform.ts` é o único ponto onde os dois
lados se encontram — e é por isso que ele existe separado.

### Componentes, e não recursos soltos

Tudo que tem satélites é um `ComponentResource`:

| componente | filhos |
|---|---|
| `ExecutionRole` | o papel e a política de mensageria |
| `QuarkusFunction` | a `aws.lambda.Function`, o `command:local:Command` que a constrói e o objeto S3 |
| `QueueWorker` (estende) | \+ o event source mapping |
| `Migrator` (estende) | \+ a `aws.lambda.Invocation` que roda a migration |
| `HttpApi` | a API, a integração, a rota, o stage e a permissão |

Três coisas concretas que isso compra: **ciclo de vida junto** (o componente é o dono; os filhos
nascem e morrem com ele), **uma URN própria** por peça — estado e endereço no Pulumi, referenciável e
substituível como unidade — e uma **árvore de deploy que descreve o sistema**:

```
Created  PostsApi axonposts:aws:QuarkusFunction → posts-api-httpBuild command:local:Command
Created  PostsApi axonposts:aws:QuarkusFunction → posts-api-httpCode aws:s3:BucketObjectv2
Created  TaggingMigrate axonposts:aws:QuarkusFunction → TaggingMigrateInvocation aws:lambda:Invocation
```

em vez de uma lista plana de nomes sem relação aparente.

### As migrations rodam sozinhas

`Migrator` cria a função **e** a `aws.lambda.Invocation` que a chama durante o `sst deploy`. O `input`
com o instante atual é o que a faz rodar a cada vez — Flyway é idempotente, então repetir custa uma
consulta ao histórico. O ganho é que **migration que falha vira deploy que falha**, em vez de uma
função esquecida e um `missing table [accounts]` na primeira requisição, que foi exatamente como isto
começou.

`if (!$dev)` porque em `sst dev` não há artefato publicado para invocar.

`infra/scripts/migrate.sh` continua existindo para reexecutar à mão quando se quiser.

### As seis funções, de três zips

| função | zip | o que a aciona |
|---|---|---|
| `PostsApi` | `posts-api-http` | API Gateway (`$default`) |
| `PostsApiInbox` | `posts-api-sqs` | fila `PostsApiCompleted` |
| `TaggingDecide` | `tagging` | fila `TaggingPrecreated` |
| `TaggingReplicate` | `tagging` | fila `TaggingChanges` |
| `PostsMigrate` | `posts-api-sqs` | invocação manual (`QUARKUS_LAMBDA_HANDLER=flyway-migrate`) |
| `TaggingMigrate` | `tagging` | invocação manual (idem) |

As duas últimas são o mesmo artefato das de fila com outra variável de ambiente, porque
`quarkus.lambda.handler` é configuração de **runtime**. Um zip a menos para construir — e nenhuma
chance de as migrations empacotadas divergirem das que a aplicação valida com
`schema-management.strategy=validate`.

O `import` dentro de `run()` é dinâmico de propósito: os módulos de `infra/` criam recursos no topo do
arquivo, e um `import` estático na raiz os avaliaria antes de `app()` ter rodado.

## Construir e subir

```bash
pnpm add -D sst                        # uma vez
npx sst deploy --stage dev             # CONSTRÓI os quatro zips, sobe tudo e roda as migrations
./infra/scripts/e2e.sh                 # a saga inteira, afirmada
npx sst remove --stage dev             # derruba tudo
```

**O build não é um passo separado** — cada função declara em `code` o alvo do Nx que a constrói e o
zip que ele produz, e o `sst deploy` o dispara. Construir à mão continua possível, e é o mesmo alvo:

```bash
npx nx run "dev.manuelantunes:axonposts-tagging:lambda"          # nativo, GraalVM da máquina
npx nx run "dev.manuelantunes:axonposts-tagging:lambda:jvm"      # sem binário nativo
./infra/scripts/package.sh --native-container                    # os quatro, ELF/Linux
```

As configurações são três: `native` (default, a GraalVM DESTA máquina), `native-container` (o builder
image do Mandrel — **de um Mac, o único que produz um binário que o Lambda executa**) e `jvm`. O
cache é do Nx: o alvo declara `inputs` e `outputs`, então um build sem mudança devolve o zip do cache
em ~100 ms, e apagar o zip o restaura em vez de reconstruir.

Os scripts descobrem os endereços com `infra/scripts/discover.sh`, que pergunta à **AWS** — não ao
SST. O motivo é simples: `sst outputs` **não existe** no 4.17.1 (o comando imprime o help), e os
outputs só aparecem na saída do `sst deploy`. Perguntar à AWS pelos prefixos dos nomes evita inventar
um segundo lugar da verdade.

```bash
eval "$(./infra/scripts/discover.sh)"   # API, ISSUER, USER_POOL, CLIENT_ID, POSTS_MIGRATE, TAGGING_MIGRATE
```

**Custo**: NAT gateway e dois RDS `t4g.micro` — na ordem de **US$ 0,08/hora** depois que o ALB e a
task Fargate do Keycloak saíram (o Cognito cabe no free tier). Esta stack existe para ser derrubada; o
`sst remove` não é opcional.

### Quatro coisas que o SST 4.17.1 faz diferente do que se supõe

As quatro foram medidas contra a versão instalada, não deduzidas da documentação:

1. **`sst.aws.Function` não suporta Java.** Os runtimes do tipo são `nodejs*`, `go`, `rust`,
   `python3.*` e container. Por isso as seis funções são `aws.lambda.Function` do provider Pulumi
   cru, que o SST expõe como o global `aws`. O resto da stack continua sendo componentes do SST.
2. **O código vai por S3.** Os zips têm 59–72 MB e o upload direto do Lambda para em 50 MB (o limite
   que vale por S3 é o de 250 MB **descompactado**, e eles ocupam 68–83 MB). Daí o bucket e o
   `sourceCodeHash` — sem o hash, trocar o conteúdo com a mesma chave não atualiza a função e o
   deploy publica o artefato antigo dizendo que deu certo.
3. **`dlq` exige o par `{ queue, retry }`.** Passar só `retry` derruba a criação da fila com
   `Redrive policy does not contain mandatory attribute: deadLetterTargetArn`. E a DLQ de uma fila
   FIFO também tem de ser FIFO.
4. **`rawMessageDelivery` não é opção da subscription** — o `SnsTopicQueueSubscriberArgs` expõe
   `filter` e nada mais. Vai por `transform.subscription`, que chega ao `sns.TopicSubscription` do
   Pulumi.
5. **Caminho de arquivo na config é resolvido a partir de `.sst/platform/`, não da raiz.** O
   `readFileSync` funciona com caminho relativo (ele usa o cwd do processo), mas o `FileAsset` é
   resolvido pela *engine* do Pulumi, relativa ao diretório do programa — e `infra/aws/dist/x.zip`
   vira `.sst/platform/infra/aws/dist/x.zip`. Use `$cli.paths.root`.
6. **No `image` do `Service`, `dockerfile` é relativo ao `context`.** Escrever o caminho completo faz
   o build procurar `docker/keycloak/docker/keycloak/Dockerfile`.

### E uma armadilha que vale por si: `✓ Complete` não quer dizer que deu certo

O deploy que tropeçou no item 5 imprimiu **`✓ Complete`** na tela, com o URL do Keycloak, e deixou de
criar as seis funções, o API Gateway e os três objetos no S3. O erro estava só aqui:

```
$ tail .sst/log/pulumi.log
error: ... failed to compute asset hash for "source": failed to open asset file
'/…/.sst/platform/infra/aws/dist/posts-api-sqs.zip': no such file or directory
    pulumi:pulumi:Stack axonposts-dev  3 errors
```

**Quando um deploy "der certo" e um recurso não aparecer, é `.sst/log/pulumi.log` que responde** — e
`npx sst diff` confirma, porque ele passa a não enxergar o recurso que falhou ao registrar. Se você usa CDK, Terraform ou SAM, o que
importa traduzir são cinco coisas:

1. o topic é **FIFO** e as filas também (`ContentBasedDeduplication` desligado — a dedup id vem da
   mensagem);
2. cada subscription tem **`RawMessageDelivery = true`**. Sem isso o corpo que chega na fila é o
   envelope do SNS e o `AxonEventEnvelope` fica aninhado numa string: a ingestão morre com
   `UnrecognizedPropertyException: Type`, e os atributos não sobrevivem para a filter policy;
3. cada event source mapping tem **`FunctionResponseTypes: [ReportBatchItemFailures]}`**. Sem isso a
   AWS **ignora, sem avisar**, a lista que o `SqsChannelIngress` devolve, e o lote volta inteiro;
4. cada função tem `QUARKUS_PROFILE=lambda,prod` — as **duas** entradas (ver abaixo);
5. as funções de fila têm `AXONPOSTS_LAMBDA_SQS_CHANNEL` apontando o canal que elas servem — e as de
   MIGRAÇÃO também, embora nunca leiam fila nenhuma: o Quarkus valida todos os `@ConfigProperty`
   injetados na partida, e sem valor a função não sobe.

### A identidade: Cognito, e o Keycloak só em dev

A aplicação é **apenas resource server** — valida um JWT, lê as roles e deixa o `UserProvisioning`
criar o perfil na primeira requisição. Não há tela de login, fluxo de consentimento nem federação
social: nada que exigisse o Keycloak em particular.

O Keycloak esteve aqui e saiu. Ele custava uma task Fargate, um ALB e uma imagem no ECR — ordem de
**US$ 25/mês** — mais um processo a operar, para entregar o que o Cognito entrega dentro do free tier
e sem nada de pé.

**Em dev e teste nada mudou.** O Dev Services sobe o Keycloak e importa
`docker/keycloak/realm-axon-posts.json`; os 155 testes usam aquele realm. A troca vale só para a AWS,
e o preço dela é uma divergência real entre o que os testes provam e o que a produção executa,
concentrada em **quatro linhas** do `application-lambda.properties`:

```properties
quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL}
quarkus.oidc.client-id=${OIDC_CLIENT_ID}
quarkus.oidc.token.audience=${OIDC_CLIENT_ID}
quarkus.oidc.roles.role-claim-path=cognito:groups
```

A última é a única divergência de **comportamento**: o Cognito escreve os grupos em `cognito:groups`,
o Keycloak escreve as roles em `realm_access.roles`, que o Quarkus já conhece por default. É ela que
faz `@RolesAllowed(Role.AUTHOR_CLAIM)` continuar valendo sem uma alteração na aplicação — os grupos do
pool se chamam `author` e `user`, os mesmos literais do realm. Se ela sumir, toda mutation de escrita
passa a responder `FORBIDDEN`, e **nenhum teste pega isso**, porque em teste o emissor é outro.

Os três usuários do realm são semeados no pool com os mesmos e-mails, nomes e senhas
(`segredo123`) — inclusive `promovido@example.com`, que existe para exercitar a promoção
Reader → Author. Isso exige afrouxar a política de senha do Cognito, que por default pede maiúscula,
número e símbolo; num sistema de verdade essa é a primeira linha a apagar.

#### A decisão que custa explicar: o bearer é o ID token

Não é distração — é consequência de um fato do Cognito. O **access token** dele traz `sub`,
`username`, `cognito:groups`, `scope` e `client_id`, e **não traz `email`**. E
`UserProvisioning.linkOrCreate` chama `Email.of(identity.email())`: sem e-mail não há perfil a criar
nem conta a ligar.

Pôr `email` no access token exige o trigger *pre token generation* **V2_0**, e a documentação da AWS é
explícita: *"Event versions one, two, and three are available in the Essentials and Plus feature
plans"*. O tier Lite — o do free tier — só recebe V1_0, que customiza o **ID token**.

| caminho | `email` no token | custo | mexe na aplicação? |
|---|---|---|---|
| **ID token como bearer** ← escolhido | sim | free tier | não |
| access token + trigger V2_0 | sim | plano Essentials, por usuário ativo | não, mas + um Lambda |
| access token puro | **não** — quebra | free tier | exigiria mudar `UserProvisioning` |

O reparo honesto: o ID token é destinado ao **cliente**, não à API. O que torna isto seguro aqui é que
o `aud` dele é o client id e a aplicação o confere (`quarkus.oidc.token.audience`) — um token emitido
para outro client do mesmo pool não passa. No dia em que houver mais de um client, ou M2M, a segunda
linha da tabela deixa de ser opcional.

#### E o token não sai de um endpoint OAuth2

O `/oauth2/token` do Cognito aceita `authorization_code`, `client_credentials` e `refresh_token` —
**não** `password`. Senha vai pela API própria dele, e é por isso que o `e2e.sh` usa a AWS CLI:

```bash
aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH \
  --client-id "$CLIENT_ID" \
  --auth-parameters USERNAME=manuel@example.com,PASSWORD=segredo123 \
  --query 'AuthenticationResult.IdToken' --output text
```

## O que foi medido na conta de verdade

Rodado em `us-east-1`, conta 688533750478, com `./infra/aws/e2e.sh`:

```
==> 1. o schema é servido (e o subgraph se declara)      OK  (SDL com @key)
==> 2. query pública responde SEM token                  OK
==> 3. mutation SEM token é recusada                     OK  (UNAUTHORIZED, com mensagem)
==> 4. token do Keycloak                                 OK  (role author)
==> 5. createPost: nasce na versão 1, SEM tag            OK
==> 6. a saga fecha                                      OK  versão=2 tags=[Untagged]  após 51s
==> 7. a atualização também atravessa                    OK  versão=3
```

Os **51 segundos** do passo 6 são quase todos cold start: duas JVMs de ~72 MB subindo numa VPC (ENI +
Hibernate + Axon + OIDC). Com as funções quentes a volta cai para poucos segundos. É o número que
justifica o binário nativo — `libs/axon-native-support` existe exatamente para isso.

Depois da saga, **todas as seis filas (três de trabalho, três de DLQ) estavam vazias**: nada preso,
nenhuma reentrega, nenhuma mensagem-veneno. É o que se espera quando o `ReportBatchItemFailures` e o
`axon_message_inbox` estão ambos no lugar.

E a federação responde sem token, como a especificação exige:

```
$ _entities(representations: [{__typename:"Post", id:"579e27ff-…"}])
[{"id":"579e27ff-…","title":"título alterado na AWS","version":3,
  "tags":{"edges":[{"node":{"name":"Untagged"}}]}}]
```

### Três coisas que só apareceram na AWS

**1. A função de migração não subia — pelo problema que ela existe para resolver.**

```
AxonExtension.init -> JpaEventstoreConfigurer.configure -> getEntityManagerFactory
Caused by: SchemaManagementException: Schema validation: missing table [accounts]
Quarkus manual initialization failed
```

O recorder do Axon é RUNTIME_INIT e toca o EntityManager durante a PARTIDA, antes de qualquer handler
existir. Com `validate` contra banco vazio a aplicação morre ali — inclusive a função cujo único
trabalho seria criar as tabelas que faltam. A saída é
`QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=none` **só nessa função**: as outras quatro
continuam com `validate` e continuam recusando subir se entidade e schema divergirem.

**2. O atributo do SNS é `topic.arn`, com PONTO.** `topic-arn` com hífen é ignorado em silêncio:

```
SRMSG19504: Topic arn for channel post-events-out : null
InvalidParameterException: Invalid parameter: TopicArn or TargetArn Reason: no value for required
```

…que chega ao cliente GraphQL como `System error` / `invalid-parameter`, sem nenhuma menção a
configuração. O nome foi lido do bytecode do conector, não da documentação — ele também usa
`group.id`, `email.subject`, `sms.phoneNumber`.

**3. O `iss` do Keycloak vem em minúsculas.** O DNS do ALB é `KeycloakLoadBal-…`, mas o `iss` do token
é montado a partir do cabeçalho `Host`, que chega minúsculo. DNS é insensível a caixa; `iss` não é.

E uma quarta, de ferramenta: **`sst outputs` não existe no 4.17.1** — o comando imprime o help. Os
outputs só saem no `sst deploy`. Daí `infra/aws/discover.sh`, que pergunta à AWS.

## O que NÃO atravessa, e por quê

### Subscriptions — nem por WebSocket nem por SSE

Isto foi medido, não deduzido.


### E o Cognito trouxe uma quarta, que era o desenho cobrando o que prometeu

A primeira execução contra o Cognito falhou assim:

```
==> 5. createPost: nasce na versão 1, SEM tag
FALHOU: usuário d847b0d6-… já tem conta em KEYCLOAK   (code: BAD_REQUEST)
```

**Não era um bug — era a regra de domínio funcionando.** O `sub` que o Cognito emite não é o que o
Keycloak emitia para a mesma pessoa, e como o token do Cognito não traz `identity_provider`,
`AuthProvider.fromAlias(null)` classificava a identidade como `KEYCLOAK` também. Duas contas do mesmo
provedor para o mesmo usuário: `Authenticatable.link` recusa, e faz bem.

O conserto foi o que o `V1__initial_schema.sql` já tinha previsto por escrito — *"o CHECK do provider
vem do enum AuthProvider e é deliberado: acrescentar um provedor passa a exigir uma migration"*:

1. `COGNITO` no enum `AuthProvider` (+ `case "cognito"` no `fromAlias`);
2. `V6__cognito_provider.sql`, refazendo o `ck_accounts_provider`;
3. um trigger *pre token generation* **V1_0** (`infra/aws/cognito/identity-provider.mjs`) que põe
   `identity_provider: "cognito"` no ID token. V1_0 e não V2_0 porque é o ID token que é o bearer — e
   porque V1_0 é o que o tier Lite oferece.

E o resultado é melhor do que "voltou a funcionar". O log mostra a mesma pessoa atravessando a troca
de emissor sem virar dois usuários:

```
account linking: 44b8b418-… de KEYCLOAK ligada ao usuário d847b0d6-…
account linking: 44b8b418-… de COGNITO  ligada ao usuário d847b0d6-…
```

Uma linha em `users`, duas em `accounts`. É exatamente para isso que o account linking existe, e foi
preciso trocar o provedor de identidade em produção para exercitá-lo de verdade.

### Uma quinta, banal e cara: variável de ambiente sem default derruba a partida

`quarkus.oidc.auth-server-url=${OIDC_ISSUER_URL}` **não tem default**, ao contrário da linha `%prod.`
que ela substitui. Dei a variável só à função de API, e as outras duas do `posts-api` morreram na
partida:

```
ConfigurationException: 'quarkus.oidc.auth-server-url' property must be configured
Quarkus manual initialization failed
```

A extensão OIDC inicializa junto com a **aplicação**, não com a primeira requisição — então uma função
que nunca serve HTTP também precisa da configuração. Por isso o OIDC vive em `postsEnv`, que as três
compartilham.

**Confirmado na stack de verdade**, não só no JAR:

```
$ curl -N -X POST $API/graphql -H 'Accept: text/event-stream' \
       -d '{"query":"subscription { onPostCreated { id title } }"}'
curl: (28) Operation timed out after 25005 milliseconds with 0 bytes received

$ curl -i $API/graphql -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' …
HTTP/2 400
```

Vinte e cinco segundos, **zero bytes** — nem o keep-alive de 15 segundos que a porta de SSE emite
chega. E o upgrade de WebSocket morre no load balancer, antes da aplicação.

**O transporte.** `quarkus-amazon-lambda-http` 3.39.2 monta **um** `APIGatewayV2HTTPResponse` inteiro
em memória. Procurando no JAR pelos marcadores do protocolo de response streaming da AWS
(`vnd.awslambda.http-integration-response`, `RESPONSE_STREAM`, `Lambda-Runtime-Function-Response-Mode`)
não há **nenhuma** ocorrência — só `Transfer-Encoding`, num tratador que remove o cabeçalho. E o
`RequestStreamHandler` do `quarkus-amazon-lambda` não é o que o nome sugere: ele dá `InputStream`/
`OutputStream` sobre o **payload da invocação**, que é bufferizado e devolvido inteiro no retorno. Não
é streaming de resposta. Response streaming de verdade exige Function URL com
`InvokeMode=RESPONSE_STREAM` e um runtime que fale aquele protocolo — o API Gateway não o suporta em
nenhum modo.

**E o transporte era a metade menor do problema.** `SimpleQueryBus.emitUpdate` é **em processo**: ele
alcança os assinantes do container onde roda, e mais ninguém. Quem apenda o `PostCreated` que fecha a
saga é a função de FILA; quem segura a conexão do assinante é a de HTTP, e o Lambda roteia cada
requisição para um container qualquer. Mesmo com o stream aberto, não havia de onde vir um evento.

**AS DUAS FORAM RESOLVIDAS, e nenhuma delas com um fork.**

| o que estava quebrado | o que resolveu |
|---|---|
| o handler monta a resposta inteira em memória | **não usá-lo**: o perfil `-Plambda-stream` não acrescenta extensão de Lambda nenhuma, e o **AWS Lambda Web Adapter** (layer oficial) roda a aplicação Quarkus HTTP como ela é |
| o API Gateway não faz response streaming | **Function URL** com `InvokeMode: RESPONSE_STREAM` — é o que `StreamingFunction` cria |
| a fonte é em processo | **configuração do Axon**: o pacote dos handlers que notificam (`application.post.event`) roda num processor *pooled streaming*, com token store em memória e posição inicial no HEAD. Cada container tem o próprio cursor e lê o EVENT STORE, que é o único lugar que todos enxergam |

A terceira linha é a que importa entender, porque ela não tem código: `PostCreatedEventHandler` e
`PostUpdatedEventHandler` continuam sendo um `emit` e mais nada. O que mudou foi **quem os chama** —
em vez do append local, o processor que lê a tabela de eventos. Quem faz a leitura, o cursor, o lote e
o retry é o Axon, com o `EventStorageEngine` e o `TokenStore` que a aplicação já configura.

A projeção **não** foi junto, e a separação é deliberada: materializar uma linha precisa acontecer uma
vez, na transação do append, e um container congelado entre invocações levaria a materialização com
ele. Ela mora em `application.post.projection`, que é subscribing. Ver `CLAUDE.md`, *O pacote de um
event handler escolhe a ENTREGA dele*.

**MEDIDO na Function URL**, com a subscription aberta num container e a mutation atendida em outro:

```
event: next
data: {"data":{"onPostUpdated":{"id":"e91dad31-…","title":"editado …","version":2}}}
```

A função de API Gateway continua existindo ao lado, e por ela nada disso atravessa — o `curl` acima
continua valendo para ela. As duas convivem porque são empacotamentos diferentes do mesmo código, e
uma função só é cobrada quando roda.

### O trace distribuído

Com o RabbitMQ a saga inteira era **um trace só**, e de graça: o conector instrumenta os dois lados.
Aqui isso regride, e por dois motivos independentes:

- na **entrada** não há conector — o Lambda entrega o `SQSEvent` direto —, então o `traceparent` teria
  de ser extraído dos atributos da mensagem à mão;
- na **saída** depende do conector: `smallrye-reactive-messaging-aws-sqs` traz
  `SqsOpenTelemetryInstrumenter` e injeta; `smallrye-reactive-messaging-aws-sns` 4.37.0 **não tem
  pacote de tracing nenhum**.

Isto importa mais do que parece, e a razão está no `CLAUDE.md`: um trace pela metade é pior que
nenhum, porque **a lacuna parece latência**. Enquanto não houver propagação, quem responde "onde o
tempo foi gasto" são as métricas do `AxonMetrics`, que continuam funcionando porque não dependem de
trace.

### O tamanho, e o cold start

75 MB por zip, JVM. Para uma POC tudo bem; para uso real o caminho já está aberto neste projeto —
`libs/axon-native-support` existe justamente para o binário nativo funcionar, e um Lambda nativo em
`provided.al2023` é o que torna Quarkus + Lambda interessante em vez de apenas possível.

## As três decisões de empacotamento, e quem as decidiu

**Quatro funções e não duas.** Uma função tem UM handler. O `posts-api` tem duas portas de entrada de
naturezas diferentes (HTTP e fila) que só conviviam porque o processo era longo; o `tagging` tem duas
filas que existem justamente para ter falha, DLQ e concorrência separadas — juntá-las numa função
desfaria em runtime a separação comprada na topologia.

**`libs/axon-aws` e `libs/axon-lambda` são dois módulos**, e quem decidiu foi o build:

```
Build step AmazonLambdaProcessor#discover threw an exception
Caused by: Multiple handler classes. You have a custom handler class and the AWS Lambda HTTP
extension. Please remove one of them from your deployment.
```

`quarkus-amazon-lambda-http` **traz** o processador do `quarkus-amazon-lambda`, e ele varre o índice
inteiro atrás de `RequestHandler`. Não basta a função de HTTP não usar o handler — ele não pode estar
no classpath dela. A fronteira, então, não é "AWS" contra "não AWS": é por função. Endereçamento de
saída as três têm; o handler de entrada, só as de fila.

**O conector in-memory é dependência de produção**, e tem dois papéis: nas funções de fila é o que
deixa os `@Incoming` existentes continuarem sendo a porta de entrada; na de API Gateway é o objeto
nulo do canal `post-completed-in`, que está declarado sem perfil no `application.properties` e não
pode ser removido por um arquivo de perfil — só sobrescrito.

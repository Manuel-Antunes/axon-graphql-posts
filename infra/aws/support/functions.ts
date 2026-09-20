/// <reference path="../../../.sst/platform/config.d.ts" />

import { createHash } from "crypto";
import { readFileSync, readdirSync, statSync } from "fs";
import { join } from "path";

/**
 * O código das funções: do zip no disco até um objeto no S3 que o Lambda consegue ler.
 *
 * <h2>Por que S3, e não o zip direto</h2>
 * Porque os artefatos têm 59–72 MB e o upload direto do Lambda para em 50 MB. O limite que vale por
 * S3 é o de 250 MB DESCOMPACTADO, e eles ocupam 68–83 MB — cabem com folga.
 */

/**
 * Onde um artefato mora. É um objeto de VALOR — não cria nem possui recurso nenhum; só carrega o que
 * uma `aws.lambda.Function` precisa saber para apontar para o próprio código.
 */
export class Artifact {
  constructor(
    readonly bucket: $util.Output<string>,
    readonly key: $util.Output<string>,
    readonly hash: string,
  ) {}
}

/**
 * As funções Quarkus, como componentes.
 *
 * <h2>Por que `aws.lambda.Function` cru, e não `sst.aws.Function`</h2>
 * Porque o `sst.aws.Function` do SST 4.17.1 não suporta Java: os runtimes que o tipo aceita são
 * `nodejs*`, `go`, `rust`, `python3.*` e imagem de container — conferido no
 * `.sst/platform/src/components/aws/function.ts`. O SST expõe o provider do Pulumi como o global
 * `aws`, então nada se perde; o preço é escrever o que o componente dele daria de graça.
 *
 * <h2>Por que ComponentResource, e não uma função que devolve um objeto</h2>
 * Porque a repetição aqui não é de VALORES, é de ESTRUTURA: toda função deste sistema é o mesmo
 * runtime, a mesma arquitetura, a mesma rede e o mesmo papel, e algumas delas trazem recursos
 * satélite — um event source mapping, uma invocação. Um componente é o que permite dizer isso uma vez
 * e depois falar em {@link QueueWorker} e {@link Migrator} em vez de em quatro recursos soltos.
 * <p>
 * E dá o agrupamento na árvore do Pulumi: os satélites nascem com `parent`, então `sst deploy` mostra
 * `TaggingDecide → TaggingDecideMapping` em vez de dois nomes sem relação.
 */

/** O que NÃO muda entre as funções: onde elas correm e com que identidade. */
export interface LambdaPlatform {
  readonly role: $util.Input<string>;
  readonly subnetIds: $util.Input<string[]>;
  readonly securityGroupIds: $util.Input<string[]>;
}

/**
 * O CÓDIGO de uma função: ou ela o constrói, ou reaproveita o de outra.
 *
 * <h2>Por que os dois casos existem</h2>
 * Esta stack tem SEIS funções e QUATRO artefatos. `posts-api-sqs` serve a fila de volta E a migração;
 * `tagging` serve três funções. Elas não compartilham o zip por acaso — é o MESMO artefato com outra
 * variável de ambiente, porque `quarkus.lambda.handler` é configuração de runtime.
 * <p>
 * Um {@link QuarkusBuild} por função construiria o mesmo binário três vezes; esconder isso atrás de
 * memoização faria a URN do build depender da ordem de declaração. A união diz a verdade e o
 * compilador a verifica: quem constrói declara o build, quem compartilha aponta para o `code` dele.
 */
export type QuarkusCode = QuarkusBuild | Artifact;

/** O que constrói um artefato. Ver {@link QuarkusCode}. */
export interface QuarkusBuild {
  /**
   * O nome do artefato. Ele nomeia os recursos de build e a chave do objeto no S3 — não é caminho
   * nem comando, é a identidade da coisa construída.
   */
  readonly artifact: string;
  /**
   * COMO se constrói: um alvo do Nx, exatamente como o `buildCommand` do `sst.aws.Nextjs` em
   * `web/index.ts`.
   *
   * <h3>Por que um alvo do Nx e não um script</h3>
   * Pelo mesmo que o site: uma definição só. Quem constrói na máquina, em CI e no deploy roda o
   * MESMO alvo, com o mesmo cache — e o alvo declara `inputs` e `outputs`, o que dá ao build a
   * mesma coisa que o `sst deploy` já tinha para o resto do sistema: um jeito de saber que nada
   * mudou. Antes disso era `infra/scripts/package.sh`, que refazia à mão (por mtime) o que o Nx faz
   * por conteúdo.
   * <p>
   * A CONFIGURAÇÃO faz parte do comando (`:native`, `:native-container`, `:jvm`) porque ela faz
   * parte da escolha: é aqui que se lê qual empacotamento aquela função recebe. O resto — perfil do
   * Maven, montagem do zip, o JDK que o build exige — é do alvo, no `project.json` de cada app.
   * <p>
   * <b>Quem vai para a AWS é `:native-container`, e não o default `:native`</b>. `native-image` gera
   * um executável do SISTEMA onde roda: num Mac o default produz Mach-O, que o `provided.al2023`
   * não executa — e o modo de falhar é o pior que há, porque o deploy publica o zip e diz que deu
   * certo. O ELF/Linux só sai do builder image do Mandrel. O default continua sendo `native` porque
   * quem constrói na máquina quase sempre quer rodar a aplicação ali.
   */
  readonly buildCommand: string;
  /**
   * ONDE o zip aparece: o MESMO caminho que o alvo do Nx declara em `outputs`.
   *
   * Ele é escrito, e não derivado do nome do artefato, porque as duas pontas precisam concordar e
   * derivar esconderia isso. Divergir não é silencioso: o `assetPaths` abaixo não acha o arquivo e o
   * deploy falha ali.
   */
  readonly output: string;
  /** Onde o zip é publicado. */
  readonly bucket: $util.Input<string>;
  /**
   * Arquivos e diretórios cuja mudança obriga o Pulumi a RODAR o build de novo.
   *
   * Não é a mesma pergunta que os `inputs` do alvo do Nx, e é por isso que as duas listas convivem:
   * esta decide se o comando roda e se um objeto novo é publicado; a do Nx decide se rodar o comando
   * reconstrói alguma coisa ou devolve o zip do cache. Ver {@link QuarkusFunction.buildCode}.
   * <p>
   * `libs/` entra em todos porque `-pl` não funciona neste reator: toda invocação do Maven compila
   * as libs junto, e uma mudança nelas muda os quatro binários.
   */
  readonly sources: string[];
}

/**
 * O último build declarado — e a razão de ele ser de MÓDULO.
 *
 * Os perfis do Maven escrevem todos em `target/function.zip`, então dois builds em paralelo se
 * apagam. Cada um declara `dependsOn` o anterior, o que os serializa. É a mesma necessidade que faz o
 * `siteBuilder` do próprio SST usar um semáforo de 1 — aqui a fila é a ordem de declaração.
 */
let lastBuild: $util.Resource | undefined;

export interface QuarkusFunctionArgs {
  readonly platform: LambdaPlatform;
  readonly code: QuarkusCode;
  readonly environment: Record<string, $util.Input<string>>;
  readonly timeout: number;
  readonly memory?: number;
  /** Só a função do Web Adapter troca isto — ver {@link StreamingFunction}. */
  readonly handler?: string;
  readonly layers?: $util.Input<string>[];
  /**
   * O runtime do sandbox. O default é `provided.al2023` porque os zips passaram a ser NATIVOS: um
   * binário não precisa de JVM, e o `function.zip` das extensões `quarkus-amazon-lambda*` traz um
   * `bootstrap` no lugar do handler Java — que é exatamente o que um runtime `provided.*` executa.
   * <p>
   * Quem troca é a {@link StreamingFunction}: lá quem executa é o `run.sh` pela layer do Web
   * Adapter, e o `java21` continua servindo de sandbox sem que uma linha de Java rode.
   */
  readonly runtime?: string;
}

/**
 * O COLETOR DO OPENTELEMETRY, como layer — e ele vai em TODA função, sem exceção.
 *
 * É a regra que o `CLAUDE.md` já declarava para as aplicações ("aplicação nova nasce instrumentada")
 * aplicada ao empacotamento: sinal que existe numa função e não na vizinha dá um trace pela metade, e
 * um trace pela metade é pior que nenhum, porque a lacuna parece latência.
 * <p>
 * A layer é uma EXTENSÃO do Lambda: ela sobe antes do processo, escuta OTLP em `localhost` e faz o
 * flush no gancho de fim de invocação — antes do congelamento. Um exportador em processo não tem esse
 * gancho, e é por isso que o log da função não chegava enquanto o trace chegava.
 * <p>
 * A configuração dela é `infra/lambda/collector.yaml`, que o alvo do Nx põe na raiz de cada zip.
 */
export const OTEL_COLLECTOR_ARM64 =
  "arn:aws:lambda:us-east-1:184161586896:layer:opentelemetry-collector-arm64-0_23_0:1";

/**
 * O handler é o do Quarkus em TODOS os zips, e sempre o mesmo: a extensão instala o dela e escolhe o
 * que chamar por dentro, por `quarkus.lambda.handler` — que é configuração de RUNTIME. É isso que
 * permite ao mesmo artefato servir a fila e rodar as migrations.
 */
const HANDLER =
  "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest";

/**
 * Lê do ambiente e FALHA se não houver. Sem isto, um `.env` ausente viraria uma função implantada com
 * o coletor sem destino — que sobe, não reclama, e some com a telemetria em silêncio.
 */
function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} não está definida. Ela vem do \`.env\` da raiz (ver \`.env.example\`), que o ` +
        "SST carrega sozinho. É o destino da telemetria de TODA função.",
    );
  }
  return value;
}

export class QuarkusFunction extends $util.ComponentResource {
  readonly fn: aws.lambda.Function;
  /** O artefato desta função — é o que outra passa em `code` para reaproveitar o mesmo zip. */
  readonly code: Artifact;

  private IGNORED = new Set([
    "target",
    "node_modules",
    ".git",
    ".sst",
    "dist",
    ".DS_Store",
  ]);

  private walk(path: string): string[] {
    let info;
    try {
      info = statSync(path);
    } catch {
      // Fonte declarado que não existe não é erro: o `--only` de um artefato pode nomear um
      // diretório que outro não tem. O que seria erro é o silêncio — e ele não acontece, porque um
      // conjunto vazio dá um hash estável e o build roda na primeira vez de qualquer forma.
      return [];
    }
    if (!info.isDirectory()) return [path];

    const found: string[] = [];
    for (const entry of readdirSync(path, { withFileTypes: true }).sort(
      (a, b) => a.name.localeCompare(b.name),
    )) {
      if (this.IGNORED.has(entry.name)) continue;
      found.push(...this.walk(join(path, entry.name)));
    }
    return found;
  }

  private fingerprintOf(paths: string[]): { base64: string; short: string } {
    const digest = createHash("sha256");
    for (const path of [...paths].sort()) {
      for (const file of this.walk(path)) {
        digest.update(file);
        digest.update(
          readFileSync(file) as unknown as Uint8Array<ArrayBufferLike>,
        );
      }
    }
    return {
      base64: digest.copy().digest("base64"),
      short: digest.digest("hex").slice(0, 16),
    };
  }
  private isBuild(code: QuarkusCode): code is QuarkusBuild {
    return (code as QuarkusBuild).artifact !== undefined;
  }

  constructor(
    name: string,
    args: QuarkusFunctionArgs,
    opts?: $util.ComponentResourceOptions,
  ) {
    super("axonposts:aws:QuarkusFunction", name, {}, opts);

    this.code = this.isBuild(args.code) ? this.buildCode(args.code) : args.code;

    this.fn = new aws.lambda.Function(
      name,
      {
        role: args.platform.role,
        runtime: args.runtime ?? "provided.al2023",
        // arm64 deixou de ser só preço: o binário nativo É aarch64, compilado no builder do
        // Mandrel. Trocar a arquitetura aqui agora exige recompilar, não só redeployar.
        architectures: ["arm64"],
        handler: args.handler ?? HANDLER,
        layers: [...(args.layers ?? []), OTEL_COLLECTOR_ARM64],
        s3Bucket: this.code.bucket,
        s3Key: this.code.key,
        sourceCodeHash: this.code.hash,
        // 2 GB e não 1: memória no Lambda é também CPU, e o que domina aqui é o COLD START de
        // uma JVM com Hibernate, Axon, OIDC e o SDK da AWS. Pagar o dobro por metade do tempo
        // costuma custar o mesmo — e, na função de API, precisa caber no teto de 30s do API
        // Gateway.
        memorySize: args.memory ?? 2048,
        timeout: args.timeout,
        vpcConfig: {
          subnetIds: args.platform.subnetIds,
          securityGroupIds: args.platform.securityGroupIds,
        },
        environment: {
          variables: {
            ...args.environment,
            // Onde a layer acha a configuração dela. O arquivo é posto na raiz do zip
            // pelo alvo do Nx; sem esta linha o coletor sobe com o default dele, que
            // exporta para backends da AWS.
            OPENTELEMETRY_COLLECTOR_CONFIG_URI: "/var/task/collector.yaml",
            // O destino, resolvido pelo COLETOR (`${env:...}` no yaml). Vem do `.env` da
            // raiz, que o SST carrega no processo da config — por isso não há segredo
            // no repositório nem no IaC.
            BETTER_STACK_URL: requiredEnv("BETTER_STACK_URL"),
            BETTER_STACK_API_KEY: requiredEnv("BETTER_STACK_API_KEY"),
          },
        },
      },
      { parent: this },
    );

    this.registerOutputs({ arn: this.fn.arn });
  }

  /**
   * Constrói o artefato e o publica — o build vira parte do grafo de recursos.
   *
   * <h3>O que isto substitui</h3>
   * Rodar o empacotamento à mão antes de todo `sst deploy`, com o modo de falhar conhecido: esquecer
   * o passo e ver o deploy publicar o artefato ANTERIOR dizendo `✓ Complete`.
   *
   * <h3>Os três mecanismos, e o que cada um cobre</h3>
   * <ul>
   *   <li><b>{@code triggers}</b> com o fingerprint dos FONTES: é o que faz o Pulumi reexecutar o
   *       comando quando — e só quando — o código muda. O hash é dos fontes e não do zip porque na
   *       primeira vez o zip não existe: ele é produto deste recurso, não insumo;</li>
   *   <li><b>o CACHE DO NX</b>, que decide se rodar o comando reconstrói alguma coisa. O alvo declara
   *       `inputs` e `outputs`, então um build sem mudança devolve o zip do cache em segundos. É o
   *       que substituiu a checagem de mtime que o `package.sh` fazia, e é melhor nos três pontos em
   *       que aquela doía: compara CONTEÚDO e não data, respeita o `.gitignore` (nada de `target/`
   *       nem de `.DS_Store` fazendo os quatro artefatos parecerem velhos, que já custou ~25 min num
   *       deploy) e conhece o grafo — mexer em `libs/` invalida os quatro, mexer no `apps/tagging`
   *       não invalida nenhum do `posts-api`. Vale inteiro num clone novo ou depois de um
   *       `sst refresh`, quando o Pulumi recria o recurso e os `triggers` não sabem de nada;</li>
   *   <li><b>{@code assetPaths}</b>: a doc do provider é explícita — "a list of path globs to read
   *       AFTER the command completes". O zip é lido depois do build e entra no S3 como asset do
   *       Pulumi, o que dispensa o {@code $asset(caminho)} e o problema de o arquivo ainda não
   *       existir quando a configuração é avaliada. Eles NÃO decidem se o build roda: isso é
   *       {@code triggers}.</li>
   * </ul>
   */
  private buildCode(spec: QuarkusBuild): Artifact {
    const hash = this.fingerprintOf(spec.sources);

    const built = new command.local.Command(
      `${spec.artifact}Build`,
      {
        create: spec.buildCommand,
        update: spec.buildCommand,
        // O `cwd` do processo da configuração É a raiz do app, e `$asset()` NÃO parte dela: ele
        // resolve a partir de `.sst/platform/`. É também o que o alvo do Nx espera — o Maven
        // precisa rodar da raiz do reator.
        dir: process.cwd(),
        triggers: [hash.short],
        assetPaths: [spec.output],
      },
      { parent: this, dependsOn: lastBuild ? [lastBuild] : [] },
    );
    lastBuild = built;

    const object = new aws.s3.BucketObjectv2(
      `${spec.artifact}Code`,
      {
        bucket: spec.bucket,
        key: `${spec.artifact}-${hash.short}.zip`,
        source: built.assets.apply(
          (assets) => assets![spec.output] as $util.asset.Asset,
        ),
      },
      { parent: this, dependsOn: [built] },
    );

    return new Artifact($util.output(spec.bucket), object.key, hash.base64);
  }

  get arn(): $util.Output<string> {
    return this.fn.arn;
  }

  get functionName(): $util.Output<string> {
    return this.fn.name;
  }
}

export interface QueueWorkerArgs extends QuarkusFunctionArgs {
  /** A fila que aciona esta função. */
  readonly queue: sst.aws.Queue;
  /** O canal `@Incoming` que ela alimenta. Ver `SqsChannelBinding`. */
  readonly channel: string;
}

/**
 * Uma função movida por fila — e o event source mapping dela, que não faz sentido existir sozinho.
 *
 * <h2>Uma fila por função, e não uma função com várias filas</h2>
 * É o que permite ao canal ser uma CONSTANTE (`AXONPOSTS_LAMBDA_SQS_CHANNEL`) em vez de uma tabela
 * que case `eventSourceARN` contra nomes de fila dentro do processo. O roteamento inteiro fica nas
 * filter policies do topic, que é onde o provisionamento já o conhece.
 * <p>
 * E não é custo: as filas deste sistema são separadas justamente para ter falha, DLQ e concorrência
 * separadas. Juntá-las numa função devolveria as duas ao mesmo throttle.
 */
export class QueueWorker extends QuarkusFunction {
  readonly mapping: aws.lambda.EventSourceMapping;

  constructor(
    name: string,
    args: QueueWorkerArgs,
    opts?: $util.ComponentResourceOptions,
  ) {
    super(
      name,
      {
        ...args,
        environment: {
          ...args.environment,
          AXONPOSTS_LAMBDA_SQS_CHANNEL: args.channel,
        },
      },
      opts,
    );

    this.mapping = new aws.lambda.EventSourceMapping(
      `${name}Mapping`,
      {
        eventSourceArn: args.queue.arn,
        functionName: this.fn.arn,
        // Combina com o processamento EM SÉRIE do handler, que é o que preserva a ordem
        // dentro do grupo FIFO.
        batchSize: 10,
        // É o que faz a AWS OLHAR a lista que o `SqsChannelIngress` devolve. Sem ele o lote é
        // tudo-ou-nada — e não há aviso: uma mensagem-veneno entre dez faz as nove boas
        // voltarem, o `axon_message_inbox` as descarta, e o sistema parece funcionar enquanto
        // paga dez invocações para processar uma.
        functionResponseTypes: ["ReportBatchItemFailures"],
      },
      { parent: this },
    );
  }
}

export interface MigratorArgs extends Omit<
  QuarkusFunctionArgs,
  "timeout" | "memory"
> {}

/**
 * A função que cria o schema — e a invocação que a roda sozinha no deploy.
 *
 * <h2>Por que `schema-management.strategy=none` só aqui</h2>
 * Porque sem isso ela não sobe, pelo problema que ela existe para resolver. Medido:
 *
 * <pre>
 * AxonExtension.init -> JpaEventstoreConfigurer.configure -> getEntityManagerFactory
 * Caused by: SchemaManagementException: Schema validation: missing table [accounts]
 * Quarkus manual initialization failed
 * </pre>
 *
 * O recorder do Axon é RUNTIME_INIT e toca o EntityManager durante a PARTIDA, antes de qualquer
 * handler existir. Com `validate` contra banco vazio a aplicação morre ali — inclusive a função cujo
 * único trabalho seria criar as tabelas que faltam.
 * <p>
 * Desligar a validação SÓ nesta função quebra o laço sem perder a garantia: quem confere entidade
 * contra schema são as outras, que continuam recusando subir se divergirem.
 *
 * <h2>A invocação automática</h2>
 * `aws.lambda.Invocation` roda a função DURANTE o deploy, e o `input` com o instante atual é o que a
 * faz rodar a cada vez — Flyway é idempotente, então repetir não custa nada além de uma consulta ao
 * histórico. O ganho é que migration que falha vira DEPLOY que falha, em vez de uma função esquecida
 * e um `missing table` na primeira requisição.
 * <p>
 * `!$dev` porque em `sst dev` não há artefato publicado para invocar.
 */
export class Migrator extends QuarkusFunction {
  constructor(
    name: string,
    args: MigratorArgs,
    opts?: $util.ComponentResourceOptions,
  ) {
    super(
      name,
      {
        ...args,
        environment: {
          ...args.environment,
          QUARKUS_LAMBDA_HANDLER: "flyway-migrate",
          QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY: "none",
        },
        // Generoso: é cold start de JVM mais o tempo do Flyway, e roda uma vez por deploy.
        timeout: 300,
        // Esta não serve requisição nenhuma; o que ela faz é I/O contra o Postgres.
        memory: 1024,
      },
      opts,
    );

    if (!$dev) {
      new aws.lambda.Invocation(
        `${name}Invocation`,
        {
          input: Date.now().toString(),
          functionName: this.fn.name,
        },
        { parent: this },
      );
    }
  }
}

/**
 * A ARN da layer do <b>AWS Lambda Web Adapter</b>, arm64.
 *
 * É um projeto da própria AWS (`awslabs/aws-lambda-web-adapter`) publicado como layer pública. A
 * versão está fixada de propósito: uma layer que se move sozinha faz o comportamento do deploy
 * depender do dia.
 */
const WEB_ADAPTER_ARM64 =
  "arn:aws:lambda:us-east-1:753240598075:layer:LambdaAdapterLayerArm64:30";

export interface StreamingFunctionArgs extends Omit<
  QuarkusFunctionArgs,
  "handler" | "layers"
> {}

/**
 * A função que consegue manter uma conexão ABERTA — e por que ela precisa ser outra coisa.
 *
 * <h2>O problema</h2>
 * As outras funções desta stack usam `quarkus-amazon-lambda-http`, que traduz um evento do API
 * Gateway numa requisição do Vert.x e devolve a resposta PRONTA. Verificado no bytecode da 3.39.2:
 * o `LambdaHttpHandler` é um `RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>` — o
 * tipo de retorno é a resposta inteira — e o `NettyResponseHandler` acumula cada `HttpContent` num
 * `ByteArrayOutputStream`. Um SSE nunca termina, então o primeiro byte nunca sai.
 * <p>
 * Trocar para o `RequestStreamHandler` do `quarkus-amazon-lambda` também não resolve: o
 * `AbstractLambdaPollLoop` até entrega ao handler o `OutputStream` da conexão com a Runtime API, mas
 * o `responseStream(URL)` dele tem cinco instruções e nenhuma é `setChunkedStreamingMode` — então o
 * `HttpURLConnection` do JDK bufferiza tudo para calcular o `Content-Length`.
 *
 * <h2>A saída, e por que ela NÃO é um fork</h2>
 * O <b>Lambda Web Adapter</b> inverte o problema em vez de consertá-lo: a aplicação é empacotada como
 * o servidor HTTP que ela é, e a layer — que roda como extensão, antes do processo — espera o health
 * check e traduz cada invocação numa requisição para `localhost`. Com
 * `AWS_LWA_INVOKE_MODE=response_stream` e uma Function URL em `RESPONSE_STREAM`, o que o Vert.x
 * escreve sai para o cliente conforme escreve.
 * <p>
 * Nada de extensão do Quarkus é envolvido, e nada precisa ser forkado. O perfil Maven `lambda-stream`
 * é o único do projeto que não acrescenta dependência nenhuma.
 *
 * <h2>Function URL, e não API Gateway</h2>
 * Não é preferência: <b>response streaming na AWS só existe em Function URL</b>. O API Gateway não o
 * oferece em nenhum modo, e é por isso que ele fica com a função bufferizada.
 */
export class StreamingFunction extends QuarkusFunction {
  readonly url: aws.lambda.FunctionUrl;

  constructor(
    name: string,
    args: StreamingFunctionArgs,
    opts?: $util.ComponentResourceOptions,
  ) {
    super(
      name,
      {
        ...args,
        // O handler é o NOME DE UM ARQUIVO, não de uma classe: quem o executa é o
        // `/opt/bootstrap` da layer. Ver `apps/posts-api/src/main/lambda/run.sh`.
        handler: "run.sh",
        layers: [WEB_ADAPTER_ARM64],
        // `java21` e não `provided.al2023`: aqui quem executa é a layer do Web Adapter, pelo
        // `AWS_LAMBDA_EXEC_WRAPPER`, e esse gancho é do runtime GERENCIADO. O sandbox traz
        // uma JVM que nunca é usada — o `run.sh` executa o binário nativo — e isso custa
        // zero. Trocar por `provided.*` exigiria o binário se chamar `bootstrap` e abrir mão
        // do wrapper, que é o que faz o adapter funcionar.
        runtime: "java21",
        environment: {
          ...args.environment,
          // Sem isto a layer é só um arquivo parado: é o gancho padrão do Lambda para
          // alguém assumir a partida do processo.
          AWS_LAMBDA_EXEC_WRAPPER: "/opt/bootstrap",
          // O modo TEM de casar com o `invokeMode` da Function URL abaixo. Divergir não dá
          // erro: dá um corpo que o cliente não sabe ler.
          AWS_LWA_INVOKE_MODE: "response_stream",
          AWS_LWA_PORT: "8080",
          // O adapter segura a primeira invocação até este caminho responder — o que
          // transforma o cold start da JVM em latência, e não em 502.
          AWS_LWA_READINESS_CHECK_PATH: "/q/health/ready",
        },
      },
      opts,
    );

    this.url = new aws.lambda.FunctionUrl(
      `${name}Url`,
      {
        functionName: this.fn.name,
        // A autorização é da APLICAÇÃO: o `posts-api` é resource server e confere o bearer do
        // Cognito. `AWS_IAM` aqui exigiria SigV4 de todo cliente — inclusive do navegador.
        authorizationType: "NONE",
        invokeMode: "RESPONSE_STREAM",
      },
      { parent: this },
    );
  }

  get functionUrl(): $util.Output<string> {
    return this.url.functionUrl;
  }
}

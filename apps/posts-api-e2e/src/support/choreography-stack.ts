/**
 * A STACK inteira da saga coreografada: infraestrutura, os dois processos e o acesso a cada peça.
 *
 * Isto é o que era `docker/e2e/run.sh`, com UMA responsabilidade a menos: EMPACOTAR saiu daqui. O
 * `package` dos dois serviços é o alvo `build` deste projeto e o `test-e2e` depende dele — então o
 * `clean` que o script fazia "para medir o que um build do zero produz" foi substituído pelo que o
 * Nx faz melhor, que é o hash do CONTEÚDO das fontes. Um build que não mudou não roda de novo; um
 * que mudou não tem como ser reaproveitado.
 *
 * Uma instância só por execução do Vitest: quem a cria e a destrói é o `global-setup`.
 */
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { Broker } from "./broker";
import { Compose, Container, WORKSPACE_ROOT } from "./docker";
import { EventStore } from "./event-store";
import { PostsApi } from "./posts-api";
import { HttpHealth, LogLine, Service } from "./service";

const POSTS_DB = "axonposts";
const TAGGING_DB = "axonposts_tagging";

/** As tabelas de LEITURA do `posts-api`. O `tagging` não tem read model — ele nem mapeia essas entidades. */
const POSTS_READ_MODEL = ["post_tags", "posts", "tags", "accounts", "authors", "users"];

const SHARED_ENV = {
  RABBITMQ_HOST: "localhost",
  RABBITMQ_PORT: "5672",
  RABBITMQ_USERNAME: "guest",
  RABBITMQ_PASSWORD: "guest",
  QUARKUS_DATASOURCE_USERNAME: "axonposts",
  QUARKUS_DATASOURCE_PASSWORD: "axonposts",
  // Sem coletor no ar, o SDK ligado faz cada teste pagar tentativa de exportação e encher o log de
  // falha de conexão. `sdk.disabled` desliga a instrumentação inteira, não só o exportador.
  QUARKUS_OTEL_SDK_DISABLED: "true",
};

export class ChoreographyStack {
  readonly logDirectory = process.env.E2E_LOGS
    ?? join(WORKSPACE_ROOT, "apps/posts-api-e2e/target/logs");

  private readonly compose = new Compose();
  private readonly postgres = new Container("quarkus-axonposts-postgres");

  readonly broker = new Broker(new Container("quarkus-axonposts-rabbitmq"));
  readonly postsStore = new EventStore(this.postgres, POSTS_DB);
  readonly taggingStore = new EventStore(this.postgres, TAGGING_DB);
  readonly api = new PostsApi();

  readonly postsApi = new Service(
    "posts-api",
    "apps/posts-api/target/quarkus-app/quarkus-run.jar",
    {
      ...SHARED_ENV,
      QUARKUS_DATASOURCE_JDBC_URL: `jdbc:postgresql://localhost:5432/${POSTS_DB}`,
      KEYCLOAK_ISSUER_URI: "http://localhost:8081/realms/axon-posts",
    },
    new HttpHealth(this.api.healthUrl),
    this.logDirectory,
  );

  readonly tagging = new Service(
    "tagging",
    "apps/tagging/target/quarkus-app/quarkus-run.jar",
    {
      ...SHARED_ENV,
      QUARKUS_DATASOURCE_JDBC_URL: `jdbc:postgresql://localhost:5432/${TAGGING_DB}`,
    },
    new LogLine("started in"),
    this.logDirectory,
  );

  private get services(): Service[] {
    return [this.postsApi, this.tagging];
  }

  /** Infraestrutura no ar, schema aplicado, estado limpo e os dois processos respondendo. */
  async up(): Promise<void> {
    mkdirSync(this.logDirectory, { recursive: true });
    await this.startInfrastructure();
    await this.migrate();
    this.reset();
    await this.startApplications();
  }

  down(): void {
    for (const service of this.services) {
      service.stop();
    }
  }

  private async startInfrastructure(): Promise<void> {
    await this.compose.up("postgres", "keycloak", "rabbitmq");

    // O banco do tagueamento vem de `docker/postgres/init/02-tagging-database.sql` — mas o
    // `initdb` do Postgres roda SÓ com o volume vazio. Num volume que já existe o script nunca
    // rodou, e o sintoma é o Flyway girando em `connectRetries` sem dizer contra o quê.
    // `create database` não aceita `if not exists`, daí o guard.
    const exists = this.postgres.execQuietly(
      "psql", "-U", "axonposts", "-d", "postgres", "-tAc",
      `select 1 from pg_database where datname = '${TAGGING_DB}'`,
    );
    if (exists !== "1") {
      this.postgres.exec(
        "psql", "-U", "axonposts", "-d", "postgres", "-q", "-c",
        `create database ${TAGGING_DB} owner axonposts`,
      );
    }
  }

  /**
   * As migrations rodam FORA do processo, e isso não é preferência.
   *
   * `AxonExtension.init` é um recorder de RUNTIME_INIT que toca o EntityManager antes de o Flyway
   * ter a vez — por isso `migrate-at-start` é `false`, e com `validate` contra banco vazio a
   * aplicação morreria com `missing table [accounts]`.
   */
  private async migrate(): Promise<void> {
    await this.compose.runToCompletion("flyway-posts");
    await this.compose.runToCompletion("flyway-tagging");
  }

  private reset(): void {
    this.postsStore.truncate(...POSTS_READ_MODEL);
    this.taggingStore.truncate();
    this.broker.deleteKnownQueues();
  }

  private async startApplications(): Promise<void> {
    for (const service of this.services) {
      service.start();
    }
    await Promise.all(this.services.map((service) => service.waitUntilReady()));
  }
}

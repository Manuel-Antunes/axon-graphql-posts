import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

import { Broker } from './broker';
import { Compose, Container, WORKSPACE_ROOT } from './docker';
import { EventStore } from './event-store';
import { PostsApi } from './posts-api';
import { HttpHealth, LogLine, Service } from './service';

const POSTS_DB = 'axonposts';
const TAGGING_DB = 'axonposts_tagging';

const STAGE = 'apps/posts-api-e2e/target/stack';

const POSTS_READ_MODEL = [
  'post_tags',
  'posts',
  'tags',
  'accounts',
  'authors',
  'users',
];

const SHARED_ENV = {
  RABBITMQ_HOST: 'localhost',
  RABBITMQ_PORT: '5672',
  RABBITMQ_USERNAME: 'guest',
  RABBITMQ_PASSWORD: 'guest',
  QUARKUS_DATASOURCE_USERNAME: 'axonposts',
  QUARKUS_DATASOURCE_PASSWORD: 'axonposts',
  QUARKUS_OTEL_SDK_DISABLED: 'true',
};

export class ChoreographyStack {
  readonly logDirectory =
    process.env.E2E_LOGS ??
    join(WORKSPACE_ROOT, 'apps/posts-api-e2e/target/logs');

  private readonly compose = new Compose();
  private readonly postgres = new Container('quarkus-axonposts-postgres');

  readonly broker = new Broker(new Container('quarkus-axonposts-rabbitmq'));
  readonly postsStore = new EventStore(this.postgres, POSTS_DB);
  readonly taggingStore = new EventStore(this.postgres, TAGGING_DB);
  readonly api = new PostsApi();

  readonly postsApi = new Service(
    'posts-api',
    `${STAGE}/posts-api/quarkus-run.jar`,
    {
      ...SHARED_ENV,
      QUARKUS_DATASOURCE_JDBC_URL: `jdbc:postgresql://localhost:5432/${POSTS_DB}`,
      KEYCLOAK_ISSUER_URI: 'http://localhost:8081/realms/axon-posts',
    },
    new HttpHealth(this.api.healthUrl),
    this.logDirectory,
  );

  readonly tagging = new Service(
    'tagging',
    `${STAGE}/tagging/quarkus-run.jar`,
    {
      ...SHARED_ENV,
      QUARKUS_DATASOURCE_JDBC_URL: `jdbc:postgresql://localhost:5432/${TAGGING_DB}`,
    },
    new LogLine('started in'),
    this.logDirectory,
  );

  private get services(): Service[] {
    return [this.postsApi, this.tagging];
  }

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
    await this.compose.up('postgres', 'keycloak', 'rabbitmq');

    const exists = this.postgres.execQuietly(
      'psql',
      '-U',
      'axonposts',
      '-d',
      'postgres',
      '-tAc',
      `select 1 from pg_database where datname = '${TAGGING_DB}'`,
    );
    if (exists !== '1') {
      this.postgres.exec(
        'psql',
        '-U',
        'axonposts',
        '-d',
        'postgres',
        '-q',
        '-c',
        `create database ${TAGGING_DB} owner axonposts`,
      );
    }
  }

  private async migrate(): Promise<void> {
    await this.compose.runToCompletion('flyway-posts');
    await this.compose.runToCompletion('flyway-tagging');
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

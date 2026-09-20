import { join } from 'node:path';

import { Broker } from './broker';
import { Compose, Container, WORKSPACE_ROOT } from './docker';
import { EventStore } from './event-store';
import { PostsApi } from './posts-api';
import { HttpHealth, LogLine, Service } from './service';

const POSTS_DB = 'axonposts';
const TAGGING_DB = 'axonposts_tagging';

const APPS_PROFILE = 'apps';

const POSTS_READ_MODEL = [
  'post_tags',
  'posts',
  'tags',
  'accounts',
  'authors',
  'users',
];

export class ChoreographyStack {
  readonly logDirectory =
    process.env.E2E_LOGS ??
    join(WORKSPACE_ROOT, 'apps/posts-api-e2e/target/logs');

  private readonly compose = new Compose(APPS_PROFILE);
  private readonly postgres = new Container('quarkus-axonposts-postgres');

  readonly broker = new Broker(new Container('quarkus-axonposts-rabbitmq'));
  readonly postsStore = new EventStore(this.postgres, POSTS_DB);
  readonly taggingStore = new EventStore(this.postgres, TAGGING_DB);
  readonly api = new PostsApi();

  readonly postsApi = new Service(
    'posts-api',
    this.compose,
    new HttpHealth(this.api.healthUrl),
    this.logDirectory,
  );

  readonly tagging = new Service(
    'tagging',
    this.compose,
    new LogLine('started in'),
    this.logDirectory,
  );

  private get services(): Service[] {
    return [this.postsApi, this.tagging];
  }

  async up(): Promise<void> {
    await this.startInfrastructure();
    await this.migrate();
    this.reset();
    try {
      await this.startApplications();
    } catch (failure) {
      this.saveLogs();
      throw failure;
    }
  }

  async down(): Promise<void> {
    this.saveLogs();
    await this.compose.remove('posts-api', 'tagging');
  }

  private saveLogs(): void {
    for (const service of this.services) {
      service.saveLog();
    }
  }

  private async startInfrastructure(): Promise<void> {
    await this.compose.up('postgres', 'keycloak', 'rabbitmq');
    this.recreateDatabase(POSTS_DB);
    this.recreateDatabase(TAGGING_DB);
  }

  private recreateDatabase(database: string): void {
    this.onPostgres(`drop database if exists ${database} with (force)`);
    this.onPostgres(`create database ${database} owner axonposts`);
  }

  private onPostgres(statement: string): void {
    this.postgres.exec(
      'psql',
      '-U',
      'axonposts',
      '-d',
      'postgres',
      '-q',
      '-c',
      statement,
    );
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
    await this.compose.start('posts-api', 'tagging');
    await Promise.all(this.services.map((service) => service.waitUntilReady()));
  }
}

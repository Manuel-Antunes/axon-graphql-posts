import { execFileSync } from 'node:child_process';
import { join } from 'node:path';

import type { Service } from './service';
import { Broker } from './broker';
import { Compose, Container, WORKSPACE_ROOT } from './docker';
import { EventStore } from './event-store';
import { Keycloak } from './keycloak';
import {
  ComposedService,
  HttpAnswering,
  HttpHealth,
  LogLine,
  SpawnedService,
} from './service';

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

export const WEB_PORT = Number(process.env.WEB_PORT ?? 4300);
export const WEB_URL = process.env.WEB_URL ?? `http://localhost:${WEB_PORT}`;

export const POSTS_API_URL =
  process.env.POSTS_API_URL ??
  `http://localhost:${process.env.POSTS_API_PORT ?? 8080}`;

export const GRAPHQL_URL = `${POSTS_API_URL}/graphql`;

export class ChoreographyStack {
  readonly logDirectory =
    process.env.E2E_LOGS ?? join(WORKSPACE_ROOT, 'apps/web-e2e/target/logs');

  private readonly compose = new Compose(APPS_PROFILE);
  private readonly postgres = new Container('quarkus-axonposts-postgres');

  readonly broker = new Broker(new Container('quarkus-axonposts-rabbitmq'));
  readonly keycloak = new Keycloak();
  readonly postsStore = new EventStore(this.postgres, POSTS_DB);
  readonly taggingStore = new EventStore(this.postgres, TAGGING_DB);

  private readonly webEnvironment: NodeJS.ProcessEnv = {
    NEXT_PUBLIC_GRAPHQL_URL: GRAPHQL_URL,
    NEXT_PUBLIC_COGNITO_ISSUER: '',
    COGNITO_CLIENT_ID: '',
    OIDC_ISSUER_URL: this.keycloak.issuerUrl,
    OIDC_CLIENT_ID: process.env.OIDC_CLIENT_ID ?? 'axon-posts-api',
    PORT: String(WEB_PORT),
  };

  readonly postsApi = new ComposedService(
    'posts-api',
    this.compose,
    new HttpHealth(`${POSTS_API_URL}/q/health`),
    this.logDirectory,
  );

  readonly tagging = new ComposedService(
    'tagging',
    this.compose,
    new LogLine('started in'),
    this.logDirectory,
  );

  readonly web = new SpawnedService(
    'web',
    {
      command: 'npx',
      args: ['next', 'start', '-p', String(WEB_PORT)],
      cwd: join(WORKSPACE_ROOT, 'apps/web'),
      environment: this.webEnvironment,
    },
    new HttpAnswering(`${WEB_URL}/login`),
    this.logDirectory,
  );

  private get services(): Service[] {
    return [this.postsApi, this.tagging, this.web];
  }

  async up(): Promise<void> {
    await this.startInfrastructure();
    await this.migrate();
    this.reset();
    this.buildTheClient();
    try {
      await this.startApplications();
    } catch (failure) {
      this.saveLogs();
      this.stopTheClient();
      throw failure;
    }
  }

  async down(): Promise<void> {
    this.saveLogs();
    this.stopTheClient();
    await this.compose.remove('posts-api', 'tagging');
  }

  private saveLogs(): void {
    for (const service of this.services) {
      service.saveLog();
    }
  }

  private stopTheClient(): void {
    this.web.stop();
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

  private buildTheClient(): void {
    execFileSync('npx', ['nx', 'run', 'web:build'], {
      cwd: WORKSPACE_ROOT,
      env: { ...process.env, ...this.webEnvironment },
      stdio: 'inherit',
      maxBuffer: 64 * 1024 * 1024,
    });
  }

  private async startApplications(): Promise<void> {
    await this.compose.start('posts-api', 'tagging');
    this.web.start();
    await Promise.all(this.services.map((service) => service.waitUntilReady()));
  }
}

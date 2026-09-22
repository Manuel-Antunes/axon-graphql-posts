import { spawn } from 'node:child_process';
import {
  appendFileSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import type { ChildProcess } from 'node:child_process';

import type { Compose } from './docker';
import { WORKSPACE_ROOT } from './docker';

export const sleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));

export interface Readiness {
  isReady(log: () => string): Promise<boolean>;
  readonly description: string;
}

export class HttpHealth implements Readiness {
  readonly description: string;

  constructor(private readonly url: string) {
    this.description = `${url} respondendo`;
  }

  async isReady(): Promise<boolean> {
    try {
      return (await fetch(this.url)).ok;
    } catch {
      return false;
    }
  }
}

export class HttpAnswering implements Readiness {
  readonly description: string;

  constructor(private readonly url: string) {
    this.description = `${url} respondendo abaixo de 500`;
  }

  async isReady(): Promise<boolean> {
    try {
      return (await fetch(this.url, { redirect: 'manual' })).status < 500;
    } catch {
      return false;
    }
  }
}

export class LogLine implements Readiness {
  readonly description: string;

  constructor(private readonly needle: string) {
    this.description = `a linha "${needle}" no log`;
  }

  async isReady(log: () => string): Promise<boolean> {
    return log().includes(this.needle);
  }
}

export interface Service {
  readonly name: string;
  readonly log: string;
  waitUntilReady(seconds?: number): Promise<void>;
  tail(lines?: number): string;
  saveLog(): void;
}

abstract class RunningService implements Service {
  constructor(
    readonly name: string,
    protected readonly readiness: Readiness,
    private readonly logDirectory: string,
  ) {}

  abstract get log(): string;
  protected abstract diedWith(): string | null;

  async waitUntilReady(seconds = 180): Promise<void> {
    for (let attempt = 0; attempt < seconds; attempt++) {
      if (await this.readiness.isReady(() => this.log)) return;
      const death = this.diedWith();
      if (death !== null) {
        throw new Error(
          `${this.name} morreu na partida — ${death}\n${this.tail()}`,
        );
      }
      await sleep(1000);
    }
    throw new Error(
      `${this.name} não subiu em ${seconds}s (esperava ${this.readiness.description}):\n${this.tail()}`,
    );
  }

  get startupTime(): string {
    return (
      /started in [0-9.]+s/.exec(this.log)?.[0] ?? '(sem a linha de partida)'
    );
  }

  tail(lines = 30): string {
    return this.log
      .split('\n')
      .filter((line) => !/^\s+at /.test(line))
      .slice(-lines)
      .join('\n');
  }

  saveLog(): void {
    mkdirSync(this.logDirectory, { recursive: true });
    writeFileSync(join(this.logDirectory, `${this.name}.log`), this.log);
  }
}

export class ComposedService extends RunningService {
  constructor(
    name: string,
    private readonly compose: Compose,
    readiness: Readiness,
    logDirectory: string,
  ) {
    super(name, readiness, logDirectory);
  }

  get log(): string {
    return this.compose.logs(this.name);
  }

  protected diedWith(): string | null {
    if (this.compose.isRunning(this.name)) return null;
    return `o container saiu com código ${this.compose.exitCode(this.name) ?? '?'}`;
  }
}

export interface Launch {
  readonly command: string;
  readonly args: string[];
  readonly cwd?: string;
  readonly environment: NodeJS.ProcessEnv;
}

export class SpawnedService extends RunningService {
  private child?: ChildProcess;

  constructor(
    name: string,
    private readonly launch: Launch,
    readiness: Readiness,
    private readonly directory: string,
  ) {
    super(name, readiness, directory);
  }

  start(): void {
    mkdirSync(this.directory, { recursive: true });
    writeFileSync(this.logFile, '');
    this.child = spawn(this.launch.command, this.launch.args, {
      cwd: this.launch.cwd ?? WORKSPACE_ROOT,
      env: { ...process.env, ...this.launch.environment },
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    });
    const record = (chunk: Buffer) =>
      appendFileSync(this.logFile, chunk.toString());
    this.child.stdout?.on('data', record);
    this.child.stderr?.on('data', record);
  }

  get log(): string {
    try {
      return readFileSync(this.logFile, 'utf8');
    } catch {
      return '';
    }
  }

  stop(): void {
    const child = this.child;
    this.child = undefined;
    if (!child?.pid) return;
    try {
      process.kill(-child.pid, 'SIGTERM');
    } catch {
      child.kill('SIGTERM');
    }
  }

  protected diedWith(): string | null {
    const code = this.child?.exitCode;
    return code === null || code === undefined
      ? null
      : `o processo saiu com código ${code}`;
  }

  private get logFile(): string {
    return join(this.directory, `${this.name}.log`);
  }
}

import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { Compose } from './docker';
import { sleep } from './posts-api';

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

export class LogLine implements Readiness {
  readonly description: string;

  constructor(private readonly needle: string) {
    this.description = `a linha "${needle}" no log`;
  }

  async isReady(log: () => string): Promise<boolean> {
    return log().includes(this.needle);
  }
}

export class Service {
  constructor(
    readonly name: string,
    private readonly compose: Compose,
    private readonly readiness: Readiness,
    private readonly logDirectory: string,
  ) {}

  async waitUntilReady(seconds = 180): Promise<void> {
    for (let attempt = 0; attempt < seconds; attempt++) {
      if (await this.readiness.isReady(() => this.log)) return;
      if (!this.compose.isRunning(this.name)) {
        throw new Error(
          `${this.name} morreu na partida — o container saiu com código ` +
            `${this.compose.exitCode(this.name) ?? '?'}\n${this.tail()}`,
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

  get log(): string {
    return this.compose.logs(this.name);
  }

  tail(lines = 25): string {
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

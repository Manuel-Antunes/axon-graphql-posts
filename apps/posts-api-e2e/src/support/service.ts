import { spawn } from 'node:child_process';
import { createWriteStream, existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ChildProcess } from 'node:child_process';

import { WORKSPACE_ROOT } from './docker';
import { sleep } from './posts-api';

const JAVA = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', 'java')
  : 'java';

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
  private process?: ChildProcess;
  private death?: string;

  constructor(
    readonly name: string,
    private readonly jar: string,
    private readonly env: Record<string, string>,
    private readonly readiness: Readiness,
    private readonly logDirectory: string,
  ) {}

  start(): void {
    const log = createWriteStream(this.logFile, { flags: 'w' });
    this.death = undefined;
    const child = spawn(JAVA, ['-jar', join(WORKSPACE_ROOT, this.jar)], {
      cwd: WORKSPACE_ROOT,
      env: { ...process.env, ...this.env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.pipe(log);
    child.stderr.pipe(log);

    child.on('error', (error) => {
      this.death = `não foi possível executar ${JAVA}: ${error.message}`;
    });
    child.on('exit', (code, signal) => {
      if (signal === 'SIGTERM') return;
      this.death =
        `o processo saiu com código ${code ?? '?'}${signal ? ` (${signal})` : ''}` +
        (this.log.trim() === ''
          ? ' e NÃO ESCREVEU UMA LINHA — quase sempre é o JDK:' +
            ` um artefato \`release 21\` sob um JDK mais velho sai exatamente assim.` +
            ` JAVA_HOME=${process.env.JAVA_HOME ?? '<não definido>'}`
          : '');
    });
    this.process = child;
  }

  async waitUntilReady(seconds = 120): Promise<void> {
    for (let attempt = 0; attempt < seconds; attempt++) {
      if (await this.readiness.isReady(() => this.log)) return;
      if (this.death) {
        throw new Error(
          `${this.name} morreu na partida — ${this.death}\n${this.tail()}`,
        );
      }
      await sleep(1000);
    }
    throw new Error(
      `${this.name} não subiu em ${seconds}s (esperava ${this.readiness.description}):\n${this.tail()}`,
    );
  }

  stop(): void {
    this.process?.kill('SIGTERM');
    this.process = undefined;
  }

  get startupTime(): string {
    return (
      /started in [0-9.]+s/.exec(this.log)?.[0] ?? '(sem a linha de partida)'
    );
  }

  get log(): string {
    return existsSync(this.logFile) ? readFileSync(this.logFile, 'utf8') : '';
  }

  tail(lines = 25): string {
    return this.log
      .split('\n')
      .filter((line) => !/^\s+at /.test(line))
      .slice(-lines)
      .join('\n');
  }

  private get logFile(): string {
    return join(this.logDirectory, `${this.name}.log`);
  }
}

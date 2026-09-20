import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';

const exec = promisify(execFile);

export const WORKSPACE_ROOT = new URL('../../../..', import.meta.url).pathname;

export class Container {
  constructor(readonly name: string) {}

  exec(...command: string[]): string {
    return execFileSync('docker', ['exec', this.name, ...command], {
      encoding: 'utf8',
    }).trim();
  }

  execQuietly(...command: string[]): string {
    try {
      return this.exec(...command);
    } catch {
      return '';
    }
  }
}

export class Compose {
  constructor(
    private readonly profile: string,
    private readonly root: string = WORKSPACE_ROOT,
  ) {}

  async up(...services: string[]): Promise<void> {
    await this.run('up', '-d', '--wait', ...services);
  }

  async start(...services: string[]): Promise<void> {
    await this.run('up', '-d', ...services);
  }

  async runToCompletion(service: string): Promise<void> {
    await this.run('up', '--exit-code-from', service, service);
  }

  async remove(...services: string[]): Promise<void> {
    await this.run('rm', '-fsv', ...services);
  }

  logs(service: string): string {
    try {
      return execFileSync(
        'docker',
        ['compose', '--profile', this.profile, 'logs', '--no-color', service],
        { cwd: this.root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 },
      );
    } catch {
      return '';
    }
  }

  isRunning(service: string): boolean {
    try {
      const ids = execFileSync(
        'docker',
        ['compose', '--profile', this.profile, 'ps', '-q', service],
        { cwd: this.root, encoding: 'utf8' },
      ).trim();
      if (ids === '') return false;
      return execFileSync(
        'docker',
        ['inspect', '-f', '{{.State.Running}}', ...ids.split('\n')],
        { encoding: 'utf8' },
      ).includes('true');
    } catch {
      return false;
    }
  }

  exitCode(service: string): number | null {
    try {
      const id = execFileSync(
        'docker',
        ['compose', '--profile', this.profile, 'ps', '-aq', service],
        { cwd: this.root, encoding: 'utf8' },
      ).trim();
      if (id === '') return null;
      return Number(
        execFileSync('docker', ['inspect', '-f', '{{.State.ExitCode}}', id], {
          encoding: 'utf8',
        }).trim(),
      );
    } catch {
      return null;
    }
  }

  private async run(...args: string[]): Promise<string> {
    const { stdout } = await exec(
      'docker',
      ['compose', '--profile', this.profile, ...args],
      {
        cwd: this.root,
        maxBuffer: 32 * 1024 * 1024,
      },
    );
    return stdout.trim();
  }
}

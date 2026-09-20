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
  constructor(private readonly root: string = WORKSPACE_ROOT) {}

  async up(...services: string[]): Promise<void> {
    await this.run('up', '-d', '--wait', ...services);
  }

  async runToCompletion(service: string): Promise<void> {
    await this.run('up', '--exit-code-from', service, service);
  }

  private async run(...args: string[]): Promise<string> {
    const { stdout } = await exec('docker', ['compose', ...args], {
      cwd: this.root,
      maxBuffer: 32 * 1024 * 1024,
    });
    return stdout.trim();
  }
}

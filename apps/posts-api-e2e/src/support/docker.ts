/**
 * O DOCKER visto como objetos: um `Compose` que provisiona e um `Container` que responde a perguntas.
 *
 * Existe porque o teste da saga afirma coisas que a borda GraphQL não conta — quantos eventos há no
 * event store de cada serviço, se o broker roteou uma mensagem republicada — e todas elas são feitas
 * a processos que vivem em containers. Encapsular o `docker exec` aqui é o que mantém o resto do app
 * falando de saga, e não de linha de comando.
 */
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';

const exec = promisify(execFile);

/** A raiz do monorepo, deduzida deste arquivo: é onde mora o `docker-compose.yml`. */
export const WORKSPACE_ROOT = new URL('../../../..', import.meta.url).pathname;

/**
 * Um container do compose, endereçado pelo nome.
 *
 * Os nomes levam prefixo `quarkus-` para não colidirem com os containers do projeto Spring original,
 * e por isso são dados e não deduzidos do serviço.
 */
export class Container {
  constructor(readonly name: string) {}

  /**
   * SÍNCRONO de propósito: quem chama está no meio de uma afirmação (`expect(store.streamOf(id))`),
   * e uma promessa ali só acrescentaria ruído a uma chamada que leva milissegundos.
   */
  exec(...command: string[]): string {
    return execFileSync('docker', ['exec', this.name, ...command], {
      encoding: 'utf8',
    }).trim();
  }

  /** O mesmo, para os comandos da provisão que falham à toa — apagar uma fila que não existe. */
  execQuietly(...command: string[]): string {
    try {
      return this.exec(...command);
    } catch {
      return '';
    }
  }
}

/** O `docker compose` da raiz — só o que a provisão deste teste precisa dele. */
export class Compose {
  constructor(private readonly root: string = WORKSPACE_ROOT) {}

  /** Sobe serviços e ESPERA pelo healthcheck de cada um. */
  async up(...services: string[]): Promise<void> {
    await this.run('up', '-d', '--wait', ...services);
  }

  /** Roda um serviço de tarefa única (o Flyway) e propaga o código de saída dele. */
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

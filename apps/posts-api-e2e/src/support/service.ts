/**
 * Um dos DOIS processos da saga, e a pergunta que os separa: como se sabe que ele subiu?
 *
 * O `posts-api` tem `/q/health`. O `tagging` NÃO TEM PORTA NENHUMA — `quarkus-opentelemetry` depende
 * de `quarkus-vertx` e não de `quarkus-vertx-http`, que é justamente o que permite instrumentá-lo sem
 * lhe dar um endpoint. O único sinal de que ele subiu é a linha do Quarkus no log dele.
 *
 * Duas respostas para a mesma pergunta é o que faz da prontidão uma ESTRATÉGIA, e não um `if`.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream, existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { WORKSPACE_ROOT } from "./docker";
import { sleep } from "./posts-api";

/**
 * O `java` que roda os artefatos — e NÃO é o do PATH.
 *
 * MEDIDO nesta máquina: o shell traz um JDK 17, e um `quarkus-run.jar` compilado com `release 21`
 * sai com CÓDIGO 1 E LOG VAZIO nele. Nem `UnsupportedClassVersionError`, nem uma linha em stderr —
 * o sintoma é indistinguível de "a aplicação morreu na partida", e custou uma espera de 120s para
 * dizer nada.
 *
 * Quem garante um JDK serviçável é o `infra/scripts/build-env.sh`, que o alvo do Nx põe na frente do
 * `vitest`: ele acha um JDK >= 21 e exporta `JAVA_HOME`. Aqui só se honra o que ele decidiu — a
 * busca não é reescrita, porque duas buscas divergem.
 */
const JAVA = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "java") : "java";

export interface Readiness {
  /** Verdadeiro quando o serviço está no ar. Recebe o log para quem não tem outra coisa a olhar. */
  isReady(log: () => string): Promise<boolean>;
  readonly description: string;
}

/** Quem tem porta responde a uma sonda HTTP. */
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

/** Quem não tem porta só tem o log. */
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
  /** Preenchido quando o processo morre sozinho — é o que transforma 120s de espera numa frase. */
  private death?: string;

  constructor(
    readonly name: string,
    private readonly jar: string,
    private readonly env: Record<string, string>,
    private readonly readiness: Readiness,
    private readonly logDirectory: string,
  ) {}

  start(): void {
    const log = createWriteStream(this.logFile, { flags: "w" });
    this.death = undefined;
    const child = spawn(JAVA, ["-jar", join(WORKSPACE_ROOT, this.jar)], {
      cwd: WORKSPACE_ROOT,
      env: { ...process.env, ...this.env },
      stdio: ["ignore", "pipe", "pipe"],
    });
    child.stdout.pipe(log);
    child.stderr.pipe(log);

    // Sem este ouvinte, um `spawn` que falha (ENOENT no `java`) vira exceção NÃO TRATADA e o
    // Vitest a reporta sem dizer qual serviço era.
    child.on("error", (error) => {
      this.death = `não foi possível executar ${JAVA}: ${error.message}`;
    });
    // A morte precoce é o caso que custou caro: o processo sai, o log fica vazio, e sem isto a
    // espera segue até o prazo para então dizer só que ele "não subiu".
    child.on("exit", (code, signal) => {
      if (signal === "SIGTERM") return;
      this.death = `o processo saiu com código ${code ?? "?"}${signal ? ` (${signal})` : ""}`
        + (this.log.trim() === "" ? " e NÃO ESCREVEU UMA LINHA — quase sempre é o JDK:"
          + ` um artefato \`release 21\` sob um JDK mais velho sai exatamente assim.`
          + ` JAVA_HOME=${process.env.JAVA_HOME ?? "<não definido>"}` : "");
    });
    this.process = child;
  }

  async waitUntilReady(seconds = 120): Promise<void> {
    for (let attempt = 0; attempt < seconds; attempt++) {
      if (await this.readiness.isReady(() => this.log)) return;
      // Antes do `sleep`, e não depois: um processo que já morreu não vai ficar pronto, e
      // esperar o prazo inteiro só atrasa a resposta.
      if (this.death) {
        throw new Error(`${this.name} morreu na partida — ${this.death}\n${this.tail()}`);
      }
      await sleep(1000);
    }
    throw new Error(
      `${this.name} não subiu em ${seconds}s (esperava ${this.readiness.description}):\n${this.tail()}`,
    );
  }

  stop(): void {
    this.process?.kill("SIGTERM");
    this.process = undefined;
  }

  get startupTime(): string {
    return /started in [0-9.]+s/.exec(this.log)?.[0] ?? "(sem a linha de partida)";
  }

  get log(): string {
    return existsSync(this.logFile) ? readFileSync(this.logFile, "utf8") : "";
  }

  /** As últimas linhas RELEVANTES: a pilha de uma exceção Java empurra a causa para fora da tela. */
  tail(lines = 25): string {
    return this.log
      .split("\n")
      .filter((line) => !/^\s+at /.test(line))
      .slice(-lines)
      .join("\n");
  }

  private get logFile(): string {
    return join(this.logDirectory, `${this.name}.log`);
  }
}

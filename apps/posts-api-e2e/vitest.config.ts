import { defineConfig } from "vitest/config";

/**
 * Um teste que provisiona DOIS PROCESSOS e um broker não se parece com um teste de unidade, e a
 * configuração diz exatamente isso — cada linha abaixo é uma consequência disso, não afinamento.
 */
export default defineConfig({
  test: {
    // As specs moram em `src/specs/` e levam `.e2e.spec.ts`: o sufixo diz o NÍVEL, e o nível é o
    // que decide a esteira. `src/support/` fica de fora do padrão de propósito — o que há lá é
    // mecanismo, e mecanismo não é spec.
    include: ["src/specs/**/*.e2e.spec.ts"],

    // A stack sobe UMA vez para todos os arquivos. Provisionar por arquivo custaria dois JVMs
    // por arquivo, e o `globalSetup` é o gancho que roda antes de qualquer suíte.
    globalSetup: ["./src/global-setup.ts"],

    // Um processo só, um arquivo por vez: os testes disputariam o MESMO event store e o mesmo
    // broker. Paralelismo aqui não acelera nada — ele muda o que está sendo medido.
    fileParallelism: false,
    pool: "forks",
    poolOptions: { forks: { singleFork: true } },

    // A saga atravessa um broker, e a PRIMEIRA entrega de cada canal paga a conexão do emitter
    // de saída, que é lazy de propósito. Medido: ~20s até a primeira mensagem fechar o ciclo.
    testTimeout: 90_000,
    // O `globalSetup` sobe Postgres, Keycloak, RabbitMQ, roda duas migrations e espera duas JVMs.
    hookTimeout: 300_000,
    teardownTimeout: 60_000,

    // Consistência eventual não se conserta com repetição: um teste que só passa na segunda
    // tentativa está escondendo exatamente o que este app existe para medir.
    retry: 0,

    reporters: process.env.CI ? ["default", "junit"] : ["default"],
    outputFile: { junit: "target/test-results/junit.xml" },
  },
});

/// <reference path="./.sst/platform/config.d.ts" />
/**
 * A raiz do SST — e é só isso que ela é.
 *
 * O `sst.config.ts` tem de morar na raiz (é onde o CLI o procura), mas não precisa CONTER a
 * infraestrutura. O que há de verdade está em `infra/aws/`, um arquivo por camada, e a única coisa
 * que acontece aqui é a ordem em que eles são carregados.
 *
 * O `await import` dinâmico não é estilo: dentro de `run()` o contexto do Pulumi já existe, e os
 * módulos de `infra/` criam recursos no topo do arquivo. Importá-los estaticamente aqui os avaliaria
 * ANTES de `app()` ter rodado.
 */
export default $config({
  app(input) {
    return {
      name: "axonposts",
      // `retain` em produção porque os dois Postgres SÃO o event store: apagar a stack
      // apagaria o log de fatos do qual todo estado deste sistema é derivado. Não é o read
      // model que se perderia — é a história.
      removal: input?.stage === "production" ? "retain" : "remove",
      protect: input?.stage === "production",
      home: "aws",
      providers: { command: { package: "@pulumi/command", version: "1.2.1" } },
    };
  },
  async run() {
    const infra = await import("./infra/aws");
    return infra.outputs;
  },
});

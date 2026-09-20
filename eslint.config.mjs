// @ts-check
import baseConfig from "./eslint.base.config.mjs";

/**
 * O ESLint da RAIZ — e ele NÃO é o lint de um projeto.
 *
 * Quem tem código tem o seu: `apps/web`, `apps/posts-api-e2e` e `infra`, cada um com o próprio
 * `eslint.config.mjs` e o próprio alvo `lint`. `nx run-many -t lint` resolve os quatro (os três de
 * ESLint mais o Spotless do reator Java) sem que ninguém precise listá-los.
 *
 * Este arquivo existe para o que roda FORA dos alvos: o editor, que resolve a config a partir da
 * raiz, e um `npx eslint <arquivo>` avulso. Ele também é o ponto onde o `jsonc-eslint-parser` entra,
 * porque JSON solto (`nx.json`, `package.json`) não pertence a projeto nenhum.
 *
 * **O `sst.config.ts` fica de fora de qualquer alvo, e isso é consequência de onde ele mora:** o CLI
 * do SST o procura na raiz, e a raiz é o projeto do reator Maven, não um projeto de JavaScript.
 * Editá-lo continua tendo lint no editor por este arquivo.
 */
export default [
  ...baseConfig,

  {
    files: ["**/*.json"],
    languageOptions: {
      parser: await import("jsonc-eslint-parser"),
    },
  },

  {
    /*
     * A MESMA exceção que `infra/eslint.config.mjs` faz, e pelo mesmo motivo: o `sst.config.ts` é
     * SST como o resto, só que mora na raiz por exigência do CLI.
     *
     * O `/// <reference path=".sst/platform/config.d.ts" />` é como o SST põe os tipos gerados dele
     * em escopo (`$config`, `$app`, `sst.aws.*`). Não há import equivalente porque não há módulo: é
     * um arquivo de declarações. Trocar por `import` deixaria o arquivo SEM TIPO NENHUM.
     *
     * O `enforce-module-boundaries` também sai, e aqui a regra está certa e o arquivo é a exceção:
     * o `await import("./infra/aws")` É um import relativo para dentro de outro projeto — e tem de
     * ser, porque o SST exige que a raiz da configuração esteja neste arquivo e os módulos de
     * `infra/` criam recursos no topo. Importá-los estaticamente os avaliaria antes de `app()`.
     */
    files: ["sst.config.ts"],
    rules: {
      "@typescript-eslint/triple-slash-reference": "off",
      "@nx/enforce-module-boundaries": "off",
    },
  },

  {
    /*
     * Um `npx eslint .` daqui não repete o que cada projeto já diz uma vez.
     */
    ignores: ["apps/**", "libs/**", "infra/**", "docker/**", "tools/**"],
  },
];

/** @typedef {import("prettier").Config} PrettierConfig */
/** @typedef {import("prettier-plugin-tailwindcss").PluginOptions} TailwindConfig */
/** @typedef {import("@ianvs/prettier-plugin-sort-imports").PluginConfig} SortImportsConfig */

/**
 * O FORMATADOR DO LADO JAVASCRIPT — e o que ele NÃO decide é o mais importante aqui.
 *
 * <h2>A indentação vem do `.editorconfig`, e não deste arquivo</h2>
 * Não há `tabWidth` nem `useTabs` abaixo, de propósito: o Prettier 3 lê o `.editorconfig` por
 * padrão, e `indent_style`/`indent_size`/`end_of_line`/`max_line_length` de lá VENCEM o default
 * dele. Medido — com `indent_size = 8` no `.editorconfig`, o Prettier indenta 8.
 *
 * É isso que mantém a convenção num lugar só. O `.editorconfig` é o que o editor lê enquanto se
 * digita e o que o Prettier aplica ao salvar; declarar o mesmo número aqui seria a mesma regra em
 * dois arquivos, e o primeiro ajuste os separaria em silêncio.
 *
 * <h2>O que este arquivo NÃO alcança</h2>
 * Os 153 arquivos Java e os 12 `pom.xml`. O Prettier não fala nenhuma das duas linguagens, e por
 * isso o `.editorconfig` tem blocos próprios para elas — ver o comentário de lá. Do lado Java quem
 * cuida da higiene é o Spotless, e ele deliberadamente NÃO formata (ver *O LADO JAVA* no CLAUDE.md).
 */

/** @type { PrettierConfig & SortImportsConfig & TailwindConfig } */
const config = {
  singleQuote: true,
  quoteProps: 'consistent',

  plugins: [
    '@ianvs/prettier-plugin-sort-imports',
    'prettier-plugin-tailwindcss',
  ],

  /*
   * `tailwindStylesheet` e NÃO `tailwindConfig`: na v4 a configuração é o próprio CSS — o `@theme`
   * dentro do `globals.css` —, e não existe `tailwind.config.ts` neste projeto. É o mesmo arquivo
   * que o `entryPoint` do `eslint-plugin-better-tailwindcss` aponta, e tem de continuar sendo: são
   * dois leitores do MESMO tema.
   *
   * `cn` e `cva` porque é por dentro das duas que as classes deste app passam — sem elas o plugin
   * ordenaria só o que estivesse cru num `className`, que aqui é a minoria.
   */
  tailwindStylesheet: './apps/web/src/app/globals.css',
  tailwindFunctions: ['cn', 'cva'],

  /*
   * A ORDEM DOS IMPORTS — e os grupos são os deste monorepo, não os do template de onde este
   * arquivo veio: não há `@acme`, `@plasmo` nem `expo` aqui, e um grupo que nunca casa é uma linha
   * que ninguém mantém.
   *
   * O que existe são três camadas, e a string vazia entre elas é uma linha em branco na saída:
   * o framework (React e Next), o resto do mundo, o próprio app (`@/`, que é o alias do
   * `apps/web`), e o que está ao lado (relativos). `<TYPES>` na frente de cada uma mantém os
   * `import type` no topo do grupo a que pertencem — que é o que torna visível, lendo de cima,
   * quanto daquele bloco some na compilação.
   */
  importOrder: [
    '<TYPES>',
    '^(react/(.*)$)|^(react$)|^(react-dom/(.*)$)|^(react-dom$)',
    '^(next/(.*)$)|^(next$)',
    '<THIRD_PARTY_MODULES>',
    '',
    '<TYPES>^@/',
    '^@/(.*)$',
    '',
    '<TYPES>^[.]',
    '^[.]',
  ],
  importOrderParserPlugins: ['typescript', 'jsx', 'decorators-legacy'],
  importOrderTypeScriptVersion: '5.9.0',
};

export default config;

import { dirname } from 'path';
import { fileURLToPath } from 'url';
import { FlatCompat } from '@eslint/eslintrc';
import {
  configs as graphqlConfigs,
  parser as graphqlParser,
  processors as graphqlProcessors,
  rules as graphqlRules,
} from '@graphql-eslint/eslint-plugin';
import vitest from '@vitest/eslint-plugin';
import tailwind from 'eslint-plugin-better-tailwindcss';

import baseConfig from '../../eslint.base.config.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const compat = new FlatCompat({
  baseDirectory: __dirname,
});

const eslintConfig = [
  // A BASE vem primeiro, e compor é o ponto: é dela que vem o `@nx/enforce-module-boundaries`, a
  // única regra que fala de arquitetura. Repetir a lista aqui seria a mesma regra em dois lugares —
  // e o primeiro ajuste as separaria em silêncio.
  ...baseConfig,
  ...compat.extends('next/core-web-vitals', 'next/typescript'),
  {
    ignores: [
      'node_modules/**',
      '.next/**',
      'out/**',
      'build/**',
      'next-env.d.ts',
      // saída do graphql-codegen: é gerada a cada build, e as regras aqui valem para o que
      // alguém escreveu
      'src/gql/**',
      '.open-next/**',
    ],
  },
  {
    /*
     * As MESMAS regras do `apps/posts-api-e2e`, e pela mesma razão: elas pegam os jeitos de uma
     * suíte MENTIR que nenhum compilador vê. A lista é curta de propósito — cada uma corresponde a
     * um modo de ficar verde sem afirmar nada:
     *
     * - `expect-expect`: um `it` sem `expect` PASSA;
     * - `no-focused-tests`: um `it.only` esquecido some com o resto e o relatório não acusa;
     * - `no-disabled-tests`: um `it.skip` esquecido é o mesmo, mais silencioso;
     * - `no-identical-title`: dois `it` com o mesmo nome fazem ler o relatório errado;
     * - `no-standalone-expect`: `expect` fora de um teste roda na COLETA, e a falha aparece como
     *   erro de arquivo, sem dizer qual afirmação era;
     * - `prefer-to-be`: a mensagem de um matcher específico é a diferença entre "esperava true" e
     *   "esperava a versão 2, veio 1".
     */
    files: ['src/**/*.spec.{ts,tsx}'],
    plugins: { vitest },
    rules: {
      'vitest/expect-expect': 'error',
      'vitest/no-focused-tests': 'error',
      'vitest/no-disabled-tests': 'warn',
      'vitest/no-identical-title': 'error',
      'vitest/no-standalone-expect': 'error',
      'vitest/prefer-to-be': 'error',
    },
  },

  {
    /*
     * TAILWIND — e a linha entre o que ENTRA e o que fica de fora foi MEDIDA, não escolhida por
     * gosto. Rodando as 15 regras do plugin contra os 71 fontes deste app:
     *
     *   172  enforce-logical-properties        `mt-1` → `mbs-1`, `size-7` → `block-7 inline-7`
     *   101  enforce-consistent-line-wrapping  um FORMATTER de className
     *    12  enforce-canonical-classes         `text-sm leading-relaxed` → `text-sm/relaxed`
     *     2  enforce-shorthand-classes         `-translate-x-1/2 -translate-y-1/2` → `-translate-1/2`
     *     1  enforce-consistent-class-order    hoje é do Prettier — ver abaixo
     *     1  no-deprecated-classes             `backdrop-blur` (a escala do blur mudou na v4)
     *     1  no-unknown-classes                `toaster`, que é do sonner e não do Tailwind
     *
     * As quatro primeiras NÃO entram, e é o mesmo argumento que já manteve o `importOrder` do
     * Spotless desligado: não existindo convenção a preservar, a regra não estaria arrumando nada —
     * estaria ESCOLHENDO uma e reescrevendo quase tudo para impô-la. `enforce-logical-properties`
     * é o caso extremo: ela troca o vocabulário do Tailwind por um que ninguém aqui lê, para
     * resolver um problema (RTL) que esta aplicação não tem.
     *
     * O que entra é o que aponta DEFEITO — classe que não existe, classe que briga com outra,
     * classe montada por concatenação (que o Tailwind não consegue extrair e portanto poda do CSS)
     * — mais as duas que custaram uma ocorrência cada e são auto-corrigíveis pelo `pnpm lint:fix`.
     *
     * **`enforce-consistent-class-order` também fica de fora, e não por medição: por DUPLICIDADE.**
     * Quem ordena classe aqui é o `prettier-plugin-tailwindcss`, ao salvar. Duas ferramentas
     * ordenando a mesma linha com algoritmos próprios é um arquivo que muda de forma conforme quem
     * rodou por último — e o `eslint-config-prettier` não pega este caso, porque ele não conhece
     * este plugin. Ordenar é formatação, e formatação é do Prettier; ao ESLint fica o defeito.
     *
     * `entryPoint` e não `tailwindConfig`: na v4 a configuração é o PRÓPRIO CSS (`@theme` dentro do
     * `globals.css`), e não há `tailwind.config.ts` neste projeto para apontar. É o MESMO arquivo
     * que o `tailwindStylesheet` do `.prettierrc.mjs` aponta — os dois leem o mesmo tema, e têm de
     * continuar lendo.
     */
    files: ['src/**/*.{ts,tsx}'],
    plugins: { 'better-tailwindcss': tailwind },
    settings: {
      'better-tailwindcss': { entryPoint: 'src/app/globals.css' },
    },
    rules: {
      ...tailwind.configs['correctness-error'].rules,
      'better-tailwindcss/no-deprecated-classes': 'error',
      'better-tailwindcss/no-duplicate-classes': 'error',
      'better-tailwindcss/no-unnecessary-whitespace': 'error',
      // `toaster` é classe do SONNER, aplicada pela biblioteca no próprio CSS dela — o Tailwind não
      // a conhece e nunca vai conhecer. É a única exceção, e ela é nominal de propósito: um
      // `ignore` largo aqui desligaria a proteção contra erro de digitação, que é o que esta regra
      // existe para dar.
      'better-tailwindcss/no-unknown-classes': [
        'error',
        { ignore: ['toaster'] },
      ],
    },
  },

  {
    /*
     * GRAPHQL, PASSO 1: extrair as operações de dentro do TypeScript.
     *
     * O processador roda o `graphql-tag-pluck` sobre cada arquivo e entrega o que achar como
     * arquivos `.graphql` VIRTUAIS, que o bloco seguinte linta. Ele reconhece tanto
     * ``gql`...` `` quanto `graphql(`...`)` — e é a segunda forma que importa aqui, porque é a do
     * `client-preset` do codegen, que é o que este app usa.
     *
     * `src/gql/**` fica de fora porque é GERADO: lintar o que o codegen escreveu seria reclamar de
     * um arquivo que ninguém edita, e o `graphql.ts` de lá contém o schema inteiro em texto.
     */
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/gql/**'],
    processor: graphqlProcessors.graphql,
  },

  {
    /*
     * GRAPHQL, PASSO 2: as regras, sobre os documentos extraídos.
     *
     * A base é `operations-recommended` e não `operations-all`, e a diferença foi medida contra
     * este app: as cinco regras que só existem em `all` produziram 45 das 57 ocorrências, e as três
     * maiores BRIGAM com o desenho que o `apps/web/README.md` documenta —
     * `require-import-fragment` (18) quer comentários `#import`, que são do fluxo de arquivos
     * `.graphql` e não do `client-preset`; `no-one-place-fragments` (2) quer inlinar um fragmento
     * usado uma vez, quando a promessa do fragment masking é justamente que o COMPONENTE seja dono
     * do que pede; e `alphabetize` (25) reordena seleção, que aqui é lida na ordem em que a tela
     * mostra.
     *
     * O que a base entrega é validação de VERDADE contra o schema — campo que não existe, variável
     * não usada, argumento obrigatório faltando, fragmento sobre o tipo errado, campo depreciado —,
     * e ela já pagou o preço de entrada: `require-selections` achou um `TagList_post` que lia um
     * `Post` sem pedir o `id`. Como o cache do Apollo normaliza `Post` por `keyFields: ["id"]`, um
     * fragmento assim não é auto-suficiente — e o sintoma seria o mesmo post virando dois objetos
     * no cache, em silêncio.
     *
     * **O SCHEMA NÃO É DECLARADO AQUI**, e isso é o ponto. Não há `parserOptions.graphQLConfig`
     * neste bloco: o `@graphql-eslint` descobre o `graphql.config.yml` da RAIZ sozinho, e é de lá
     * que saem o schema (`apps/web/schema.graphql`), os `documents` e o `exclude` de `src/gql/**`.
     * Declará-los aqui funcionava, e era o mesmo fato escrito duas vezes — o lint lendo um
     * arquivo, a IDE e o codegen lendo outro, e nada conferindo que continuam iguais.
     *
     * Provado que a descoberta funciona e que as regras não ficaram mudas: tirando o `id` do
     * `TagList_post`, o `require-selections` acusa com a mesma mensagem de antes.
     */
    files: ['**/*.graphql'],
    /*
     * O `schema.graphql` da raiz do app é SCHEMA, e estas regras são de OPERAÇÃO: sem esta linha o
     * `executable-definitions` reclama de cada `type` dele — 50 erros dizendo que uma definição de
     * tipo não é executável, o que é verdade e não é defeito.
     *
     * E ele nem é escrito aqui: sai do `/graphql/schema.graphql` da API, por `pnpm schema:pull`.
     * Lintá-lo seria este app opinando sobre o desenho do servidor, que ele não controla — a mesma
     * razão pela qual `src/gql/**` está nos `ignores` lá em cima.
     */
    ignores: ['schema.graphql'],
    languageOptions: { parser: graphqlParser },
    plugins: { '@graphql-eslint': { rules: graphqlRules } },
    rules: {
      ...graphqlConfigs['flat/operations-recommended'].rules,
      /*
       * A `naming-convention` do preset exige `PascalCase` em fragmento e reprovaria os NOVE deste
       * app — `PostCard_post`, `AuthorByline_author`, `TagList_post`. Mas esse nome não é descuido:
       * é a convenção do `client-preset`, `<Componente>_<prop>`, e é ela que liga o fragmento ao
       * componente que o declara e à prop que o recebe.
       *
       * Por isso o `requiredPattern` no lugar do `style`: a regra deixa de brigar com a convenção e
       * passa a EXIGI-LA. O resto do preset (operação em PascalCase, sem prefixo `Get`, sem sufixo
       * `Query`) continua valendo — este app já o cumpre inteiro.
       */
      '@graphql-eslint/naming-convention': [
        'error',
        {
          ...graphqlConfigs['flat/operations-recommended'].rules[
            '@graphql-eslint/naming-convention'
          ][1],
          FragmentDefinition: {
            requiredPattern: /^[A-Z][A-Za-z0-9]*_[a-z][A-Za-z0-9]*$/,
          },
          /*
           * `_entities`, `_Entity` e `_Any` levam underscore porque a ESPECIFICAÇÃO da Apollo
           * Federation manda — são os nomes reservados que todo subgraph expõe, e a página
           * `/federation` consulta o primeiro. Proibir o underscore aqui seria proibir falar com um
           * subgraph.
           */
          allowLeadingUnderscore: true,
        },
      ],
    },
  },
];

export default eslintConfig;

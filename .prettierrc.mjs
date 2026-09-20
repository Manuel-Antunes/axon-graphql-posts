/** @typedef {import("prettier").Config} PrettierConfig */
/** @typedef {import("prettier-plugin-tailwindcss").PluginOptions} TailwindConfig */
/** @typedef {import("@ianvs/prettier-plugin-sort-imports").PluginConfig} SortImportsConfig */

/** @type { PrettierConfig & SortImportsConfig & TailwindConfig } */
const config = {
  singleQuote: true,
  quoteProps: 'consistent',

  plugins: [
    '@ianvs/prettier-plugin-sort-imports',
    'prettier-plugin-tailwindcss',
  ],

  tailwindStylesheet: './apps/web/src/app/globals.css',
  tailwindFunctions: ['cn', 'cva'],

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

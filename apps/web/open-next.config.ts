import type { OpenNextConfig } from "@opennextjs/aws/types/open-next";

// NOTE: SST's `sst.aws.Nextjs` (v4) only deploys `openNextOutput.origins.default`
// — it ignores any extra `functions: {...}` entries OpenNext builds. Splitting
// routes out of the default function therefore *orphans* them: they're excluded
// from the deployed bundle but no replacement lambda is ever created, so
// requests 500 with `Cannot find module '.../route.js'`. Keep everything in the
// default function until SST gains multi-origin support.
const config = {
    default: {
        override: {
            wrapper: "aws-lambda-streaming",
            converter: "aws-apigw-v2",
            incrementalCache: "s3-lite",
            tagCache: "dynamodb-lite",
            queue: "sqs-lite",
            proxyExternalRequest: "node",
        },
        // Do NOT re-minify. With `minify: true` OpenNext runs terser (`mangle: true`)
        // over the WHOLE server bundle — including the Next/Turbopack server chunks,
        // which Next already minified. That second pass re-mangles the deployed code
        // so it no longer matches the `.js.map` files uploaded to Sentry at build
        // time: the debug IDs still match (they're injected as code, so terser keeps
        // them) but the line/column mappings are stale, and every server-side stack
        // trace renders minified (e.g. `_0~l92ye._.js:1:193897`). Keeping the
        // build-time bytes intact lets Sentry's existing upload symbolicate them.
        minify: false,
    },

    imageOptimization: {
        install: {
            packages: ["sharp@0.33.5"],
            arch: "arm64",
        },
    },

    // O `next build` NÃO é rodado aqui: quem o roda é o alvo `build` do Nx, de que
    // `open-next-build` depende. É o que faz o cache do Nx valer para o build do Next
    // e o empacotamento ser só empacotamento.
    buildCommand: "exit 0",
    buildOutputPath: ".",
    appPath: ".",
    // A raiz do monorepo: é lá que estão o lockfile e o `node_modules` real do pnpm.
    packageJsonPath: "../../",
} satisfies OpenNextConfig;

export default config;

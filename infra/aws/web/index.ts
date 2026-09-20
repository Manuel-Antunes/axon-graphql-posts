/// <reference path="../../../.sst/platform/config.d.ts" />

import { streaming } from "../compute";
import { router } from "../edge";
import { OTEL_COLLECTOR_ARM64 } from "../support";
import { client, issuer } from "../identity";

/**
 * O cliente de teste — `apps/web`, um Next.js atrás do CloudFront.
 *
 * <h2>Por que aqui, e não num `sst.aws.StaticSite`</h2>
 * Porque ele NÃO é estático. A autenticação acontece em <b>server actions</b>: a senha vai para o
 * servidor do Next, que fala com o Cognito e devolve os tokens em cookies `httpOnly`. Um site
 * estático não tem onde rodar isso — a senha teria de atravessar o JavaScript da página e o refresh
 * token acabaria em `localStorage`.
 *
 * <h2>O build é do Nx, não do SST</h2>
 * O default do componente seria `pnpm run build` seguido do OpenNext embutido. Aqui o `buildCommand`
 * chama o Nx, e isso compra duas coisas:
 * <ul>
 *   <li><b>cache</b>: o alvo `open-next-build` declara `outputs: [".open-next"]` e depende de `build`.
 *       Deploy sem mudança no front não reconstrói nada — o Nx devolve o diretório do cache;</li>
 *   <li><b>uma definição só</b>: quem constrói em CI, na máquina e no deploy roda o MESMO alvo. Um
 *       `buildCommand` escrito à mão aqui seria uma segunda verdade sobre como se constrói o site.</li>
 * </ul>
 * As variáveis abaixo chegam ao processo do build (o SST as injeta no ambiente do comando), e é isso
 * que faz `INFRA_PROVIDER=aws` ligar o `output: "standalone"` do `next.config.ts` e os
 * `NEXT_PUBLIC_*` serem inlinados no bundle. Ver `apps/web/open-next.config.ts`, que conta o outro
 * lado: ele NÃO roda `next build` (`buildCommand: "exit 0"`), justamente porque o Nx já rodou.
 *
 * <h2>O que é público e o que não é</h2>
 * `NEXT_PUBLIC_*` vira texto dentro do JavaScript servido ao navegador. O endereço do subgraph e o
 * issuer podem — os dois já são descobríveis por quem abrir o devtools. `COGNITO_CLIENT_ID` e
 * `COGNITO_REGION` ficam sem o prefixo de propósito: quem os usa são as server actions, e mantê-los
 * fora do bundle é o que deixa a fronteira visível. (O client id não é segredo — um client público
 * não tem secret —; o que importa é que a CHAMADA ao Cognito acontece do lado do servidor.)
 */
export const web = new sst.aws.Nextjs("Web", {
    path: "apps/web",
    buildCommand: "npx -y nx run web:open-next-build",

    /*
     * O SITE FICA ATRÁS DO ROUTER, e não numa distribuição própria.
     *
     * O que isso compra está no Javadoc de `edge/index.ts`: um domínio e um certificado para site e
     * API, e o `OriginReadTimeout` deixando de ser um 20 literal para virar o `timeout` declarado
     * logo abaixo — que é o que permite o proxy de `/api/graphql` ficar calado durante o cold start
     * do `posts-api` sem virar 504.
     *
     * Sem `domain` porque esta stack ainda não tem um: o router traz o dele, e `web.url` passa a
     * devolver esse endereço (o SST troca o `prodUrl` quando o site é roteado). O dia do domínio
     * mexe numa linha em `edge/index.ts`, não aqui.
     */
    router: { instance: router },

    /*
     * 60 s, e este número agora vale nos DOIS lados: o SST o espelha no `readTimeout` da rota do
     * site no router (`ssr-site.ts:1857`). Antes ele era metade verdade — o CDN cortava aos 20 s, e
     * quem descobria isso era uma subscription morrendo no meio de um cold start.
     *
     * `/api/graphql` repassa o SSE do `posts-api`, e o `open-next.config.ts` empacota este servidor
     * com `wrapper: "aws-lambda-streaming"` — sem isso a resposta seria montada inteira em memória e
     * nenhum ajuste de relógio adiantaria.
     *
     * 60 é o teto útil: é o máximo que o CloudFront aceita numa origem escolhida dinamicamente. Uma
     * tentativa antiga com 360 s derrubou o site inteiro com 502. O que torna o teto invisível para o
     * usuário é a reconexão do cliente (`retryAttempts` em `lib/apollo/links/sse-link.ts`), somada ao
     * keep-alive de 2 s do `SseStream`, que impede a conexão de ficar calada enquanto está aberta.
     *
     * Isso não muda o custo das outras rotas: uma função do Lambda é cobrada pelo tempo que roda, não
     * pelo timeout declarado.
     */
    server: {
        timeout: "60 seconds",
        /*
         * arm64, e aqui ele não é só 20% mais barato por GB-s.
         *
         * Esta função é cobrada pela DURAÇÃO, e uma subscription mantém a invocação viva enquanto o
         * navegador estiver olhando — até o teto acima. Um assinante conectado custa 60 s de função a
         * cada reconexão, então o preço por segundo é o preço da feature, não um arredondamento.
         *
         * O otimizador de imagem já era arm64: o `open-next.config.ts` instala o `sharp` para essa
         * arquitetura e o SST a respeita. Quem estava fora do padrão era o servidor.
         */
        architecture: "arm64",
        /*
         * A MESMA layer do coletor que as funções Java carregam.
         *
         * Sem ela o `instrumentation.node.ts` exportaria para um `localhost:4318` que não existe, e o
         * trace do navegador terminaria na borda do Next — deixando o salto até o `posts-api` como um
         * buraco que parece latência. Ver `infra/lambda/collector.yaml`.
         */
        layers: [OTEL_COLLECTOR_ARM64],
    },

    /*
     * O `server` do componente não expõe `copyFiles`, e a layer precisa do arquivo DENTRO do pacote:
     * ela lê `/var/task/collector.yaml`. O `transform` dá acesso aos argumentos crus da função.
     */
    transform: {
        server: (args) => {
            args.copyFiles = [
                ...((args.copyFiles as { from: string; to?: string }[]) ?? []),
                { from: "infra/lambda/collector.yaml", to: "collector.yaml" },
            ];
        },
    },
    environment: {
        INFRA_PROVIDER: "aws",

        /*
         * A TELEMETRIA DO WEB — três variáveis, e nenhuma delas é `NEXT_PUBLIC_`.
         *
         * A aplicação exporta OTLP para `localhost`; quem conhece o Better Stack é o coletor da
         * layer. O endereço é o receptor HTTP (4318) porque é o que o `@vercel/otel` fala — as
         * funções Java usam 4317 (gRPC), e o mesmo `collector.yaml` abre as duas portas.
         *
         * `BETTER_STACK_API_KEY` não leva prefixo público de propósito: com `NEXT_PUBLIC_` ela seria
         * inlinada no JavaScript servido ao navegador. Quem a lê é o coletor, dentro do sandbox.
         */
        OPENTELEMETRY_COLLECTOR_CONFIG_URI: "/var/task/collector.yaml",
        OTEL_EXPORTER_OTLP_ENDPOINT: "http://localhost:4318",
        BETTER_STACK_URL: process.env.BETTER_STACK_URL ?? "",
        BETTER_STACK_API_KEY: process.env.BETTER_STACK_API_KEY ?? "",
        /*
         * A FUNCTION URL, e não o API Gateway — e a diferença é a subscription.
         *
         * As duas servem a mesma aplicação, mas só esta faz response streaming: ela roda atrás do
         * Lambda Web Adapter, com `InvokeMode: RESPONSE_STREAM`. Pelo API Gateway um
         * `Accept: text/event-stream` fica 30 s sem receber um byte e é cortado; por aqui os eventos
         * chegam conforme são escritos, e o proxy de `/api/graphql` apenas REPASSA o stream.
         *
         * A URL termina em barra; `/graphql` colado a ela daria `//graphql`, que roteia mas aparece
         * feio em todo log.
         */
        NEXT_PUBLIC_GRAPHQL_URL: streaming.functionUrl.apply(
            (url) => `${url.replace(/\/$/, "")}/graphql`,
        ),
        NEXT_PUBLIC_COGNITO_ISSUER: issuer,
        COGNITO_REGION: aws.getRegionOutput().name,
        COGNITO_CLIENT_ID: client.id,
    },
});

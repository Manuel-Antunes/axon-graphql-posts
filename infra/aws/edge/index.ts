/// <reference path="../../../.sst/platform/config.d.ts" />

import { streaming } from '../compute';

/**
 * A BORDA: uma distribuição do CloudFront na frente de tudo.
 *
 * <h2>Por que um router, e não uma distribuição por componente</h2>
 * Porque o dia do domínio próprio chega, e ele é o que decide esta escolha. Com um
 * {@link sst.aws.Router}, site e API ficam atrás da MESMA distribuição: um certificado, um registro
 * de DNS, um lugar onde a borda é configurada. Sem ele são duas distribuições, dois certificados e
 * dois domínios que só por convenção parecem da mesma aplicação.
 * <p>
 * O `Router` roteia por PADRÃO — prefixo de caminho hoje (`/graphql`), padrão de domínio quando
 * houver um (`api.exemplo.com`). A troca de um para o outro é o argumento do `route`, e mais nada:
 * nenhuma função muda, nenhum endereço interno muda.
 *
 * <h2>E ele resolve um número que vinha mordendo</h2>
 * O CloudFront corta a origem que ficar calada por mais que o `OriginReadTimeout`, e um
 * `sst.aws.Nextjs` SOLTO cria a distribuição dele com 20 s literais (`ssr-site.ts:996`) — conferido
 * na conta. Um cold start do `posts-api` leva 15–26 s, e nesse intervalo o proxy do site não tem byte
 * para repassar: metade dos cold starts virava 504.
 * <p>
 * Roteado, o número deixa de ser escrito à mão. O SST espelha o `timeout` do servidor na metadata da
 * rota (`ssr-site.ts:1857`), então os 60 s declarados em `web/index.ts` passam a valer dos dois lados.
 * Havia um `transform` aqui para forçar isso na distribuição própria do site; ele saiu junto.
 *
 * <h2>O limite a conhecer: 60 s</h2>
 * É o máximo do CloudFront para uma origem escolhida dinamicamente. Subir o `timeout` da função além
 * disso não estende a conexão — faz a borda recusar a origem, e a aplicação inteira responde 502.
 * Está medido neste projeto: uma tentativa com 360 s derrubou o site. O que torna o teto invisível
 * para quem assina é a reconexão do cliente, não um número maior.
 */
export const router = new sst.aws.Router('Edge');

/**
 * O subgraph, no mesmo endereço do site.
 *
 * <h2>Quem usa esta rota — e quem NÃO usa</h2>
 * Ela é a porta pública do subgraph: é para cá que um roteador de federação ou um cliente externo
 * aponta, e com um domínio próprio ela vira `api.<domínio>` sem que nada aqui dentro mude.
 * <p>
 * O <b>proxy do site não passa por aqui</b>, e isso é deliberado: `NEXT_PUBLIC_GRAPHQL_URL` continua
 * sendo a Function URL direta. Mandar uma chamada servidor-a-servidor pelo CDN acrescentaria um salto
 * e latência para não comprar nada — o cookie e o bearer já estão à mão do lado de lá.
 * <p>
 * E o navegador continua falando só com `/api/graphql`. Não é por falta de rota: é que quem põe o
 * `Authorization` é o servidor do Next, lendo um cookie `httpOnly`. Abrir esta rota para o navegador
 * exigiria o token no JavaScript da página, que é exatamente o que o proxy existe para evitar.
 *
 * <h2>`/graphql` é PREFIXO</h2>
 * Cobre `/graphql` e `/graphql/schema.graphql` — o POST, o SDL, `_service` e `_entities`. O que fica
 * de fora é `/q/*` (health, GraphiQL, dev UI), e fica de propósito: são endpoints de operação, não
 * contrato. Com o domínio, `api.<domínio>/` passa a cobrir tudo de uma vez.
 */
router.route(
  '/graphql',
  streaming.functionUrl.apply((url) => url.replace(/\/$/, '')),
  {
    // O mesmo teto da rota do site, e pela mesma razão: uma subscription que atravesse esta rota
    // precisa sobreviver ao cold start da JVM do outro lado.
    readTimeout: '60 seconds',
  },
);

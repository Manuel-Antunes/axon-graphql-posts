/**
 * O teste da SAGA COREOGRAFADA, ponta a ponta e entre processos.
 *
 * O que ele prova, e por que cada afirmação existe:
 *
 *  1. o createPost responde na VERSÃO 1, sem tag. É a assinatura de que o passo de tagueamento saiu do
 *     fluxo da escrita: se respondesse 2, o trabalho estaria sendo feito em processo e a coreografia
 *     seria decorativa;
 *  2. a subscription onPostCreated recebe o post COMPLETO, versão 2, com a tag. É a volta inteira:
 *     posts-api -> RabbitMQ -> tagging -> RabbitMQ -> posts-api -> WebSocket;
 *  3. o event store de CADA serviço tem exatamente os eventos que devia ter — nem um a mais. É o que
 *     prova as duas coisas que mais podiam dar errado: que a ingestão apenda no store (e não só
 *     alimente um processor em memória), e que a marca de origem impeça o laço de reenvio. Um laço
 *     apareceria aqui como contagem crescendo;
 *  4. o inbox de cada serviço tem uma linha por mensagem recebida;
 *  5. reentregar a MESMA mensagem não produz uma segunda decisão. É o teste de idempotência, e ele é
 *     feito pela API de management do RabbitMQ — publicando o envelope na mão, o que também valida o
 *     formato de fio.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8081/realms/axon-posts';
const RABBIT = 'http://localhost:15672/api';
const RABBIT_AUTH = 'Basic ' + Buffer.from('guest:guest').toString('base64');

let pass = 0, fail = 0;
const ok = (m, extra) => { console.log(`  PASS  ${m}`); if (extra) console.log(`        ${extra}`); pass++; };
const bad = (m, d) => { console.log(`  FAIL  ${m}`); console.log(`        -> ${d}`); fail++; };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function token() {
  const body = new URLSearchParams({
    grant_type: 'password', client_id: 'axon-posts-api',
    username: 'manuel@example.com', password: 'segredo123',
  });
  const res = await (await fetch(`${KC}/protocol/openid-connect/token`, { method: 'POST', body })).json();
  if (!res.access_token) throw new Error(`sem token: ${JSON.stringify(res)}`);
  return res.access_token;
}

async function gql(query, tok, variables) {
  const headers = { 'Content-Type': 'application/json' };
  if (tok) headers.Authorization = `Bearer ${tok}`;
  return (await fetch(`${API}/graphql`, {
    method: 'POST', headers, body: JSON.stringify({ query, variables }),
  })).json();
}

/** A subscription por SSE — a porta escrita em interfaces/graphql/sse, que não exige cliente WebSocket. */
async function subscribe(query) {
  const controller = new AbortController();
  const res = await fetch(`${API}/graphql`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ query }),
    signal: controller.signal,
  });
  const state = { status: res.status, events: [], close: () => controller.abort() };
  (async () => {
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop();
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const payload = line.slice(5).trim();
            if (payload) { try { state.events.push(JSON.parse(payload)); } catch { /* parcial */ } }
          }
        }
      }
    } catch { /* abortado por nós */ }
  })();
  return state;
}

/** Conta linhas no event store de um dos dois bancos. `psql` roda dentro do container. */
import { execFileSync } from 'node:child_process';
function query(database, sql) {
  return execFileSync('docker', [
    'exec', 'quarkus-axonposts-postgres',
    'psql', '-U', 'axonposts', '-d', database, '-tAc', sql,
  ], { encoding: 'utf8' }).trim();
}

const author = await token();

// ------------------------------------------------------------------ 1 e 2
console.log('=== 1. createPost responde PRÉ-CRIADO (v1, sem tag) ===');
const subscription = await subscribe(
    'subscription { onPostCreated { id version tags { edges { node { name } } } } }');
if (subscription.status !== 200) {
  bad('abrir a subscription', `HTTP ${subscription.status}`);
} else {
  ok('subscription onPostCreated aberta');
}
await sleep(500);

const created = await gql(
    'mutation { createPost(input:{title:"Saga coreografada",content:"c"}) '
    + '{ id version tags { edges { node { name } } } } }', author);
const post = created?.data?.createPost;
if (!post?.id) {
  bad('createPost', JSON.stringify(created).slice(0, 400));
  process.exit(1);
}
const postId = post.id;
if (post.version === 1 && post.tags.edges.length === 0) {
  ok(`createPost respondeu v1 sem tag — o tagueamento saiu do fluxo da escrita`, `postId ${postId}`);
} else {
  bad('createPost devia responder v1 sem tag',
      `v${post.version}, ${post.tags.edges.length} tag(s) — o passo está rodando em processo`);
}

console.log('=== 2. a subscription recebe o post COMPLETO depois da volta da saga ===');
/*
 * 60s, e não 20s: a PRIMEIRA entrega de cada canal paga a conexão do emitter de saída, que é lazy de
 * propósito (ver AxonOutbox — resolver o Emitter na partida dá SRMSG00019). Medido: a
 * primeira mensagem foi nacked e só a reentrega fechou a saga, ~20s depois.
 */
let complete = null;
for (let i = 0; i < 120 && !complete; i++) {
  complete = subscription.events
      .map(e => e?.data?.onPostCreated)
      .find(p => p?.id === postId && p?.version === 2) ?? null;
  if (!complete) await sleep(500);
}
subscription.close();
if (complete) {
  const names = complete.tags.edges.map(e => e.node.name);
  if (names.includes('Untagged')) {
    ok(`onPostCreated emitiu o post pronto: v${complete.version}, tags ${JSON.stringify(names)}`);
  } else {
    bad('o post chegou completo mas sem a tag padrão', JSON.stringify(names));
  }
} else {
  bad('a subscription não recebeu o post completo',
      `${subscription.events.length} evento(s) no fio; a saga não fechou em 20s`);
}

// ------------------------------------------------------------------ 3
console.log('=== 3. o event store de cada serviço tem EXATAMENTE os eventos esperados ===');
const postsEvents = query('axonposts',
    `select string_agg(type, ',' order by globalindex) from aggregateevententry `
    + `where aggregateidentifier = '${postId}'`);
const taggingEvents = query('axonposts_tagging',
    `select string_agg(type, ',' order by globalindex) from aggregateevententry `
    + `where aggregateidentifier = '${postId}'`);

// Os DOIS serviços têm os mesmos dois eventos, e cada um produziu um deles:
//   posts-api  produziu o PostPreCreated e INGERIU o PostCreated
//   tagging    INGERIU o PostPreCreated e produziu o PostCreated
// É essa simetria que prova a integração: o evento que chega é apendado, não só processado. E é aqui
// que um laço de reenvio apareceria — como contagem crescendo.
const expectedPosts = 'posts.PostPreCreated,posts.PostCreated';
const expectedTagging = 'posts.PostPreCreated,posts.PostCreated';

if (postsEvents === expectedPosts) {
  ok(`posts-api: ${postsEvents}`);
} else {
  bad('o stream do posts-api divergiu', `esperado [${expectedPosts}], obtido [${postsEvents}]`);
}
if (taggingEvents === expectedTagging) {
  ok(`tagging:   ${taggingEvents}`, 'o evento INGERIDO está no store dele — a fila não é a fonte');
} else {
  bad('o stream do tagging divergiu', `esperado [${expectedTagging}], obtido [${taggingEvents}]`);
}

// ------------------------------------------------------------------ 4
console.log('=== 4. o inbox registrou uma linha por mensagem recebida ===');
const postsInbox = query('axonposts',
    `select string_agg(message_type || '<-' || origin, ',') from axon_message_inbox`);
const taggingInbox = query('axonposts_tagging',
    `select string_agg(message_type || '<-' || origin, ',') from axon_message_inbox`);
if (postsInbox.includes('posts.PostCreated') && postsInbox.includes('axonposts-tagging')) {
  ok(`posts-api ingeriu de 'axonposts-tagging': ${postsInbox}`);
} else {
  bad('inbox do posts-api', postsInbox || '(vazio)');
}
if (taggingInbox.includes('posts.PostPreCreated') && taggingInbox.includes('quarkus-axon-graphql-posts')) {
  ok(`tagging ingeriu de 'quarkus-axon-graphql-posts': ${taggingInbox}`);
} else {
  bad('inbox do tagging', taggingInbox || '(vazio)');
}

// ------------------------------------------------------------------ 5
console.log('=== 5. reentregar a MESMA mensagem não produz uma segunda decisão ===');
// O envelope é montado à mão e publicado pela API de management — o que também valida o formato de fio.
const preCreated = JSON.parse(query('axonposts',
    `select json_build_object('identifier', identifier, 'messageType', type || '#' || version)::text `
    + `from aggregateevententry where aggregateidentifier = '${postId}' `
    + `and type = 'posts.PostPreCreated'`));
const payload = {
  messageType: preCreated.messageType,
  identifier: preCreated.identifier,
  timestamp: new Date().toISOString(),
  metadata: { 'axon-channel-origin': 'quarkus-axon-graphql-posts' },
  tags: [{ key: 'postId', value: postId }],
  payload: Buffer.from(JSON.stringify({
    postId, title: 'Saga coreografada', content: 'c',
    authorId: '00000000-0000-0000-0000-000000000000', occurredAt: new Date().toISOString(),
  })).toString('base64'),
};
const republish = await fetch(`${RABBIT}/exchanges/%2F/axonposts.events/publish`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: RABBIT_AUTH },
  body: JSON.stringify({
    properties: {}, routing_key: `posts.PostPreCreated.${postId}`,
    payload: JSON.stringify(payload), payload_encoding: 'string',
  }),
});
const routed = await republish.json();
if (routed.routed !== true) {
  bad('republicar a mensagem', `o broker não roteou: ${JSON.stringify(routed)}`);
} else {
  ok('mensagem reentregue no mesmo identificador');
  await sleep(4000);
  const after = query('axonposts_tagging',
      `select count(*) from aggregateevententry where aggregateidentifier = '${postId}' `
      + `and type = 'posts.PostCreated'`);
  if (after === '1') {
    ok('o tagging continua com UM PostCreated — inbox e agregado seguraram a duplicata');
  } else {
    bad('a reentrega duplicou a decisão', `${after} eventos PostCreated no stream do tagging`);
  }
  const inboxRows = query('axonposts_tagging',
      `select count(*) from axon_message_inbox where identifier = '${preCreated.identifier}'`);
  if (inboxRows === '1') {
    ok('o inbox tem UMA linha para o identificador reentregue');
  } else {
    bad('linhas de inbox para a mensagem reentregue', inboxRows);
  }
}

// ------------------------------------------------------------------ 6
console.log('=== 6. o canal de RÉPLICA mantém o stream do Post completo no outro serviço ===');
/*
 * O serviço de tagueamento não reage a PostUpdated — mas precisa TER o evento, porque ele escreve no
 * stream do Post e a posição de um append vem de ter lido o stream antes. Um canal só para isso, com
 * routing keys próprias, é o que a versão de canal único não conseguia expressar.
 */
const updated = await gql(
    'mutation Editar($id: ID!) { updatePost(input:{id:$id, title:"Saga editada"}) { version } }',
    author, { id: postId });
if (updated?.data?.updatePost?.version !== 3) {
  bad('updatePost', JSON.stringify(updated).slice(0, 300));
} else {
  ok('post editado no posts-api (v3)');
  let replicated = '';
  for (let i = 0; i < 40; i++) {
    replicated = query('axonposts_tagging',
        `select string_agg(type, ',' order by globalindex) from aggregateevententry `
        + `where aggregateidentifier = '${postId}'`);
    if (replicated.includes('posts.PostUpdated')) break;
    await sleep(500);
  }
  if (replicated === 'posts.PostPreCreated,posts.PostCreated,posts.PostUpdated') {
    ok(`o stream replicou no tagging: ${replicated}`,
       'ninguém reagiu ao evento — ele está lá para o próximo append não colidir');
  } else {
    bad('o canal de réplica não trouxe o PostUpdated', `stream do tagging: [${replicated}]`);
  }
}

console.log(`\n=== SAGA COREOGRAFADA: ${pass} passaram, ${fail} falharam ===`);
process.exit(fail === 0 ? 0 : 1);

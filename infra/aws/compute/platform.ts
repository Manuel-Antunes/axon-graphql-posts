/// <reference path="../../../.sst/platform/config.d.ts" />

import { vpc } from "../network";
import { ExecutionRole } from "./role";
import type { LambdaPlatform } from "../support";

/**
 * O que é comum às seis funções, reunido nos dois objetos que elas recebem por injeção.
 *
 * É aqui que `support/` — que só DEFINE — encontra os recursos que os outros pacotes criaram. Este é
 * o único arquivo que precisa conhecer os dois lados, e é por isso que ele existe: sem ele,
 * `support/functions.ts` teria de importar `network` e `role`, e um helper que importa infraestrutura
 * deixa de ser helper.
 */
const role = new ExecutionRole("LambdaRole");

/** Onde as funções correm e com que identidade. */
export const platform: LambdaPlatform = {
  role: role.arn,
  subnetIds: vpc.privateSubnets as unknown as $util.Input<string[]>, // O SST não expõe o tipo certo, mas é o que ele devolve.
  // O SG que o SST cria para o VPC libera TODO tráfego vindo do CIDR do próprio VPC, que é o que dá
  // a estas funções acesso aos dois RDS sem uma regra a mais.
  securityGroupIds: vpc.securityGroups as unknown as $util.Input<string[]>, // O SST não expõe o tipo certo, mas é o que ele devolve.
};

/**
 * De onde elas leem o código — e o que alimenta cada build.
 *
 * O bucket é tudo que sobrou do `ArtifactStore`, que deixou de existir. QUEM CONSTRÓI É A FUNÇÃO:
 * cada uma declara em `code` o comando que a constrói (um alvo do Nx) e o zip que ele produz, e o
 * componente cuida do upload e da republicação. Ver `support/functions.ts`.
 *
 * Antes disso era `./infra/scripts/package.sh` rodado à mão antes do deploy, com o modo de falhar
 * conhecido: esquecer o passo e ver o deploy publicar o artefato anterior dizendo `✓ Complete`.
 */
const bucket = new sst.aws.Bucket("CodeBucket");

/** Onde os zips moram. É o que uma função precisa saber para publicar o próprio código. */
export const codeBucket = bucket.name;

/**
 * Os projetos do Nx, com o nome que o `@nx/maven` lhes dá: as COORDENADAS do Maven.
 *
 * O nome não é escolha nossa e não dá para encurtá-lo — o plugin descobre os projetos do reator e
 * liga as dependências por esse nome, então renomeá-los em `project.json` quebraria o grafo. O `nx
 * run` sabe desfazer a ambiguidade dos dois-pontos: ele casa o prefixo mais longo que é um projeto
 * de verdade, e só depois lê alvo e configuração.
 */
export const projects = {
  postsApi: "dev.manuelantunes:quarkus-axon-graphql-posts",
  tagging: "dev.manuelantunes:axonposts-tagging",
};

/**
 * Os fontes de cada artefato — o que faz o PULUMI rodar o build de novo.
 *
 * `libs/` e o pom da raiz entram nos DOIS porque `-pl` não funciona neste reator (ver CLAUDE.md):
 * toda invocação do Maven compila as libs junto, então uma mudança nelas muda todos os binários. O
 * `collector.yaml` entra porque ele vai dentro de cada zip, e o wrapper do Maven porque é ele quem
 * escolhe a versão que constrói.
 *
 * Esta lista é GROSSA de propósito, e é a de fora das duas: quem decide se o build reconstrói
 * alguma coisa é o `inputs` do alvo do Nx (`quarkusLambda`, no `nx.json`), que compara conteúdo e
 * respeita o `.gitignore`. Aqui basta não deixar passar nada que aquele veja — o inverso é só um
 * comando que roda e devolve o cache.
 */
const COMUM = [
  "pom.xml",
  "mvnw",
  ".mvn",
  "libs",
  "infra/lambda/collector.yaml",
];
export const sources = {
  postsApi: [...COMUM, "apps/posts-api/pom.xml", "apps/posts-api/src"],
  tagging: [...COMUM, "apps/tagging/pom.xml", "apps/tagging/src"],
};

/**
 * O ciclo de vida da stack, uma vez por execução do Vitest.
 *
 * Fica no `globalSetup` — e não num `beforeAll` — porque provisionar é caro e não é do teste: subir
 * Postgres, Keycloak, RabbitMQ e DOIS processos é o que este app de teste EXISTE para fazer, e é o
 * que o alvo `test-e2e` do Nx provisiona uma vez para todos os arquivos de teste que vierem.
 *
 * O `globalSetup` roda noutro processo, então o objeto criado aqui não atravessa para os testes. Não
 * é problema: `ChoreographyStack` é uma fachada sem estado sobre o Docker e o HTTP — o arquivo de
 * teste constrói a dele e olha para a mesma stack. O que NÃO atravessa é o processo filho de cada
 * serviço, e por isso quem os derruba é o `teardown` daqui.
 */
import { ChoreographyStack } from "./support/choreography-stack";

const stack = new ChoreographyStack();

export async function setup(): Promise<void> {
  console.log("### provisionando a stack da saga coreografada");
  await stack.up();
  console.log(`    posts-api: ${stack.postsApi.startupTime}`);
  console.log(`    tagging:   ${stack.tagging.startupTime}`);
  console.log("### a topologia que as duas aplicações declararam");
  console.log(stack.broker.bindings().replace(/^/gm, "    "));
}

export function teardown(): void {
  stack.down();
}

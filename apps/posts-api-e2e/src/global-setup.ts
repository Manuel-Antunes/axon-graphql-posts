import { ChoreographyStack } from './support/choreography-stack';

const stack = new ChoreographyStack();

export async function setup(): Promise<void> {
  console.log('### provisionando a stack da saga coreografada');
  await stack.up();
  console.log(`    posts-api: ${stack.postsApi.startupTime}`);
  console.log(`    tagging:   ${stack.tagging.startupTime}`);
  console.log('### a topologia que as duas aplicações declararam');
  console.log(stack.broker.bindings().replace(/^/gm, '    '));
}

export function teardown(): void {
  stack.down();
}

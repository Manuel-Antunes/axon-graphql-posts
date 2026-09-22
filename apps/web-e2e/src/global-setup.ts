import { RunningStack } from './support/running-stack';

export default async function globalSetup(): Promise<void> {
  process.stdout.write(
    '### provisionando a stack: postgres, keycloak, rabbitmq, posts-api, tagging, web\n',
  );
  const stack = await RunningStack.up();
  process.stdout.write(`    posts-api: ${stack.postsApi.startupTime}\n`);
  process.stdout.write(`    tagging:   ${stack.tagging.startupTime}\n`);
  process.stdout.write('### a topologia que as duas aplicações declararam\n');
  process.stdout.write(`${stack.broker.bindings().replace(/^/gm, '    ')}\n`);
}

import { RunningStack } from './support/running-stack';

export default async function globalTeardown(): Promise<void> {
  await RunningStack.down();
}

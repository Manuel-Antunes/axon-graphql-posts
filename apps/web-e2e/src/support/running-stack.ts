import { ChoreographyStack } from './stack';

export class RunningStack {
  private static stack: ChoreographyStack | undefined;

  static async up(): Promise<ChoreographyStack> {
    const stack = new ChoreographyStack();
    RunningStack.stack = stack;
    await stack.up();
    return stack;
  }

  static async down(): Promise<void> {
    await RunningStack.stack?.down();
    RunningStack.stack = undefined;
  }
}

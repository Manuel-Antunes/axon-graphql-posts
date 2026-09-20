import { Suspense } from 'react';

import { Skeleton } from '@/components/ui/skeleton';

import { SagaRunner } from './_components/saga-runner';

export default function SagaPage() {
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Saga</h1>
        <p className="text-sm text-muted-foreground">
          Consistência eventual, cronometrada.
        </p>
      </div>
      <Suspense fallback={<Skeleton className="h-72 w-full" />}>
        <SagaRunner />
      </Suspense>
    </div>
  );
}

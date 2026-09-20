import { Skeleton } from '@/components/ui/skeleton';

export function FeedSkeleton() {
  return (
    <div className="space-y-5">
      <Skeleton className="h-9 w-40" />
      <div className="grid gap-4 sm:grid-cols-2">
        {[0, 1, 2, 3].map((index) => (
          <Skeleton key={index} className="h-44 w-full" />
        ))}
      </div>
    </div>
  );
}

import { Skeleton } from "@/components/ui/skeleton";

/**
 * O que o `Suspense` mostra enquanto o resultado do `PreloadQuery` não atravessou o stream.
 *
 * Na prática ele aparece por muito pouco tempo — o servidor começou a query ANTES de mandar o HTML —,
 * mas ele tem de existir: `useSuspenseQuery` suspende, e suspender sem fronteira sobe até a fronteira
 * mais próxima, que seria o layout inteiro.
 */
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

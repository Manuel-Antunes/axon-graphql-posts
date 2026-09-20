import { Suspense } from "react";

import { PreloadQuery } from "@/lib/apollo/rsc";
import { Skeleton } from "@/components/ui/skeleton";

import { PostView } from "./_components/post-view";
import { PostByIdQuery } from "./query";

/**
 * No Next 15 `params` é uma Promise — daí o `async`. O que a torna assíncrona não é buscar dados
 * aqui: quem busca é o `PreloadQuery`, que roda a query no servidor e a entrega pronta ao
 * componente de cliente.
 */
export default async function PostPage({ params }: { params: Promise<{ id: string }> }) {
    const { id } = await params;

    return (
        <PreloadQuery query={PostByIdQuery} variables={{ id }} errorPolicy="all">
            <Suspense fallback={<Skeleton className="h-72 w-full" />}>
                <PostView id={id} />
            </Suspense>
        </PreloadQuery>
    );
}

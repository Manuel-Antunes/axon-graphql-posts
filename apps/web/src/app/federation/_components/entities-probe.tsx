"use client";

import { useState } from "react";
import { useQuery, useSuspenseQuery } from "@apollo/client/react";
import { Loader2Icon, NetworkIcon, PlayIcon } from "lucide-react";

import { ErrorNotice } from "@/app/_components/error-notice";
import { useSession } from "@/app/_providers/session-provider";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { graphql } from "@/gql";

import { FederationSeedQuery } from "../query";

/**
 * `_entities` — a porta que o roteador da federação usa, e que este cliente chama diretamente.
 *
 * <h2>O que ela prova</h2>
 * <ul>
 *   <li><b>resolve por chave, em lote</b>: N representações numa chamada. O `@Resolver` do lado do
 *       servidor devolve uma posição por representação, NA ORDEM recebida, com `null` onde não
 *       existe — é por isso que a tabela abaixo mostra as duas colunas lado a lado;</li>
 *   <li><b>é PÚBLICA</b>: sem token. É a premissa da federação (o subgraph fica interno, o roteador é
 *       a fronteira) e a razão pela qual esta aplicação não deve ser publicada direto na internet;</li>
 *   <li><b>o tipo importa</b>: `_Entity` é uma UNIÃO de quatro tipos, e pedir o tipo errado responde
 *       `null`, nunca o outro tipo. Quem separa os quatro no cache é o `possibleTypes`.</li>
 * </ul>
 */
const EntitiesQuery = graphql(`
    query Entities($representations: [_Any!]!) {
        _entities(representations: $representations) {
            __typename
            ... on Post {
                id
                title
                version
            }
            ... on Tag {
                id
                name
            }
            ... on Author {
                id
                name
                email
            }
            ... on Reader {
                id
                name
            }
        }
    }
`);

type Representation = { __typename: string; id: string };

export function EntitiesProbe() {
    const { session } = useSession();
    // Prefetchada pelo `page.tsx`: quando este componente monta, os ids já estão no cache.
    const seed = useSuspenseQuery(FederationSeedQuery, { errorPolicy: "all" });
    const [representations, setRepresentations] = useState<Representation[] | null>(null);

    const entities = useQuery(EntitiesQuery, {
        variables: { representations: (representations ?? []) as Record<string, unknown>[] },
        skip: !representations,
        fetchPolicy: "network-only",
    });

    const nodes = (seed.data?.posts.edges ?? []).filter((edge) => edge !== null).map((edge) => edge.node);

    const build = (): Representation[] => {
        const list: Representation[] = [];
        for (const post of nodes) {
            list.push({ __typename: "Post", id: post.id });
            list.push({ __typename: "Author", id: post.author.id });
            for (const edge of post.tags.edges) {
                if (edge) list.push({ __typename: "Tag", id: edge.node.id });
            }
        }
        // Uma representação que não existe, de propósito: a posição dela tem de voltar `null`.
        list.push({ __typename: "Post", id: "00000000-0000-0000-0000-000000000000" });
        return list;
    };

    return (
        <div className="space-y-5">
            <Card>
                <CardHeader>
                    <CardTitle className="flex items-center gap-2 text-base">
                        <NetworkIcon className="size-4" aria-hidden />
                        _entities(representations:)
                    </CardTitle>
                    <CardDescription>
                        {session
                            ? "Você está autenticado, mas esta query não usa o token — ela é pública."
                            : "Sem token. É assim que o roteador da federação chama o subgraph."}
                    </CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                    {seed.error ? (
                        <ErrorNotice title="Não foi possível ler os ids" error={seed.error} />
                    ) : null}

                    <Button
                        onClick={() => setRepresentations(build())}
                        disabled={nodes.length === 0 || entities.loading}
                    >
                        {entities.loading ? <Loader2Icon className="animate-spin" /> : <PlayIcon />}
                        Resolver {nodes.length > 0 ? build().length : 0} representações
                    </Button>

                    {representations ? (
                        <div className="grid gap-3 md:grid-cols-2">
                            <div>
                                <p className="mb-1 text-xs font-medium">Enviado</p>
                                <pre className="max-h-72 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-[11px]">
                                    {JSON.stringify(representations, null, 2)}
                                </pre>
                            </div>
                            <div>
                                <p className="mb-1 text-xs font-medium">Recebido</p>
                                {entities.error ? (
                                    <ErrorNotice title="_entities falhou" error={entities.error} />
                                ) : (
                                    <pre className="max-h-72 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-[11px]">
                                        {JSON.stringify(entities.data?._entities ?? [], null, 2)}
                                    </pre>
                                )}
                            </div>
                        </div>
                    ) : null}

                    {entities.data ? (
                        <div className="flex flex-wrap gap-2">
                            {entities.data._entities.map((entity, index) => (
                                <Badge
                                    key={index}
                                    variant={entity ? "secondary" : "outline"}
                                    className="font-mono text-[10px]"
                                >
                                    {index}: {entity?.__typename ?? "null"}
                                </Badge>
                            ))}
                        </div>
                    ) : null}
                </CardContent>
            </Card>
        </div>
    );
}

"use client";

import { useState } from "react";
import Link from "next/link";
import { FileQuestionIcon, Loader2Icon, RotateCcwIcon, Trash2Icon } from "lucide-react";
import { toast } from "sonner";

import { EmptyState } from "@/app/_components/empty-state";
import { ErrorNotice } from "@/app/_components/error-notice";
import { PostArticle } from "@/app/_components/post-article";
import { useSession } from "@/app/_providers/session-provider";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";

import { usePost } from "../_hooks/use-post";
import { PostEditor } from "./post-editor";
import { cn } from "@/lib/utils";

export function PostView({ id }: { id: string }) {
    const { post, error, refetch, update, remove, restore } = usePost(id);
    const { session, isAuthor } = useSession();
    const [deleted, setDeleted] = useState(false);

    // Não há `loading`: o dado já veio do `PreloadQuery` e o `useSuspenseQuery` suspendeu enquanto ele
    // não tinha chegado. Quem desenha a espera é o `Suspense` do `page.tsx`.
    if (error) return <ErrorNotice title="Não foi possível ler o post" error={error} />;

    if (!post) {
        return (
            <EmptyState
                icon={FileQuestionIcon}
                title={deleted ? "Post apagado" : "Post não encontrado"}
                description={
                    deleted
                        ? "A exclusão é lógica: a linha e o stream continuam lá, e restorePost reidrata o agregado dos eventos."
                        : "post(id:) respondeu null. O id não existe — ou o post foi apagado logicamente."
                }
                action={
                    deleted ? (
                        <Button
                            size="sm"
                            variant="outline"
                            disabled={restore.loading}
                            onClick={() => {
                                void restore
                                    .run({ variables: { id } })
                                    .then(() => {
                                        setDeleted(false);
                                        void refetch();
                                        toast.success("restorePost devolveu o post");
                                    })
                                    .catch(() => undefined);
                            }}
                        >
                            {restore.loading ? (
                                <Loader2Icon className="animate-spin" />
                            ) : (
                                <RotateCcwIcon />
                            )}
                            Restaurar
                        </Button>
                    ) : (
                        <Link href="/feed" className={cn(buttonVariants({ variant: "outline", size: "sm" }))}>
                            Voltar ao feed
                        </Link>
                    )
                }
            />
        );
    }

    return (
        <div className="space-y-8">
            <PostArticle post={post} />

            {session && isAuthor ? (
                <Card>
                    <CardHeader>
                        <CardTitle className="text-base">Editar</CardTitle>
                        <CardDescription>
                            Ser autor autoriza a escrever, não a escrever no alheio — quem decide é o
                            agregado.
                        </CardDescription>
                    </CardHeader>
                    <CardContent className="space-y-5">
                        <PostEditor post={post} update={update} />

                        <Separator />

                        <div className="space-y-2">
                            <p className="text-sm font-medium">Exclusão lógica</p>
                            {remove.error ? (
                                <ErrorNotice title="deletePost falhou" error={remove.error} />
                            ) : null}
                            {restore.error ? (
                                <ErrorNotice title="restorePost falhou" error={restore.error} />
                            ) : null}
                            <Button
                                size="sm"
                                variant="destructive"
                                disabled={remove.loading}
                                onClick={() => {
                                    void remove
                                        .run({ variables: { id } })
                                        .then(() => {
                                            setDeleted(true);
                                            toast.success("deletePost aceito", {
                                                description:
                                                    "O post some das consultas; o stream continua no event store.",
                                            });
                                        })
                                        .catch(() => undefined);
                                }}
                            >
                                {remove.loading ? (
                                    <Loader2Icon className="animate-spin" />
                                ) : (
                                    <Trash2Icon />
                                )}
                                Apagar
                            </Button>
                        </div>
                    </CardContent>
                </Card>
            ) : null}
        </div>
    );
}

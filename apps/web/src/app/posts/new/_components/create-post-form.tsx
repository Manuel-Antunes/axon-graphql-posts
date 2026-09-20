"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation } from "@apollo/client/react";
import { Loader2Icon, PenLineIcon, WorkflowIcon } from "lucide-react";
import { toast } from "sonner";

import { ErrorNotice } from "@/app/_components/error-notice";
import { PostCard } from "@/app/_components/post-card";
import { useSession } from "@/app/_providers/session-provider";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { graphql } from "@/gql";
import { cn } from "@/lib/utils";

/**
 * `createPost` devolve o post JÁ PROJETADO — e na versão 1.
 *
 * Isto não é um detalhe de implementação que vazou: é o ciclo de vida do agregado. `PostPreCreated`
 * diz "o post existe"; `PostCreated`, que vem depois e de OUTRO serviço, diz "o post está completo".
 * A mutation responde o primeiro. Quem quiser ver o segundo espera — e é o que a página `/saga` faz.
 */
const CreatePostMutation = graphql(`
    mutation CreatePost($input: CreatePostInput!) {
        createPost(input: $input) {
            id
            version
            ...PostCard_post
        }
    }
`);

export function CreatePostForm() {
    const { session, isAuthor } = useSession();
    const [title, setTitle] = useState("");
    const [content, setContent] = useState("");

    const [createPost, { data, loading, error, reset }] = useMutation(CreatePostMutation, {
        // O feed é uma cursor connection fundida pelo cache; um post novo não aparece nela sozinho.
        // Refazer a query é mais honesto que inventar uma posição para ele na página 1.
        refetchQueries: ["FeedPosts"],
    });

    if (!session) {
        return (
            <Alert>
                <PenLineIcon />
                <AlertTitle>Entre para escrever</AlertTitle>
                <AlertDescription>
                    <Link href="/login?next=/posts/new" className="underline">
                        Ir para o login
                    </Link>
                </AlertDescription>
            </Alert>
        );
    }

    if (!isAuthor) {
        return (
            <Alert variant="destructive">
                <PenLineIcon />
                <AlertTitle>Esta conta não tem a role author</AlertTitle>
                <AlertDescription>
                    A mutation leva <span className="font-mono">@RolesAllowed(&quot;author&quot;)</span>, e
                    as roles vêm de <span className="font-mono">cognito:groups</span>. Entre como
                    manuel@example.com.
                </AlertDescription>
            </Alert>
        );
    }

    const created = data?.createPost;

    return (
        <div className="space-y-5">
            <form
                className="space-y-4"
                onSubmit={(event) => {
                    event.preventDefault();
                    reset();
                    void createPost({ variables: { input: { title, content } } })
                        .then((result) => {
                            const post = result.data?.createPost;
                            if (post) {
                                toast.success(`Post criado na versão ${post.version}`, {
                                    description: "Aguardando o serviço de tagueamento completá-lo.",
                                });
                                setTitle("");
                                setContent("");
                            }
                        })
                        .catch(() => {
                            /* o erro já está em `error`; o catch existe para não vazar unhandled */
                        });
                }}
            >
                <div className="space-y-2">
                    <Label htmlFor="title">Título</Label>
                    <Input
                        id="title"
                        value={title}
                        maxLength={200}
                        onChange={(event) => setTitle(event.target.value)}
                        placeholder="Um título de até 200 caracteres"
                        required
                    />
                    <p className="text-[11px] text-muted-foreground">
                        O limite de 200 é do schema (<span className="font-mono">@constraint</span>),
                        e o servidor o recusa com <span className="font-mono">BAD_REQUEST</span> antes
                        de existir command.
                    </p>
                </div>

                <div className="space-y-2">
                    <Label htmlFor="content">Conteúdo</Label>
                    <Textarea
                        id="content"
                        value={content}
                        rows={8}
                        onChange={(event) => setContent(event.target.value)}
                        placeholder="O corpo do post"
                        required
                    />
                </div>

                {error ? <ErrorNotice title="createPost falhou" error={error} /> : null}

                <Button type="submit" disabled={loading}>
                    {loading ? <Loader2Icon className="animate-spin" /> : <PenLineIcon />}
                    Publicar
                </Button>
            </form>

            {created ? (
                <div className="space-y-3 rounded-lg border p-4">
                    <p className="text-sm font-medium">
                        Resposta da mutation — versão {created.version}
                    </p>
                    {/* `created` carrega a REFERÊNCIA ao fragmento, não os campos: quem os lê é o
                        próprio PostCard. Por isso ele é passado inteiro, sem desmascarar aqui. */}
                    <PostCard post={created} />
                    <div className="flex gap-2">
                        {/* Um `Link` com as classes do botão, e NÃO `<Button render={<Link/>}>`. O
                            Base UI marca o elemento renderizado com `role="button"` quando ele não é
                            um `<button>` nativo — o que descreve errado uma NAVEGAÇÃO: leitor de tela
                            anuncia "botão", o menu de contexto perde "abrir em nova aba", e quem
                            procura por `role=link` não acha. `buttonVariants` dá a mesma aparência
                            sem mentir sobre o papel. */}
                        <Link href={`/posts/${created.id}`} className={cn(buttonVariants({ variant: "outline", size: "sm" }))}>
                            Abrir o post
                        </Link>
                        <Link href={`/saga?postId=${created.id}`} className={cn(buttonVariants({ variant: "outline", size: "sm" }))}>
                            <WorkflowIcon />
                            Observar a saga
                        </Link>
                    </div>
                </div>
            ) : null}
        </div>
    );
}

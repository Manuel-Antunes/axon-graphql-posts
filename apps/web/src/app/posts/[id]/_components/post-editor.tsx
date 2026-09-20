"use client";

import { useState } from "react";
import { Loader2Icon, SaveIcon } from "lucide-react";
import { toast } from "sonner";

import { ErrorNotice } from "@/app/_components/error-notice";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { getFragmentData, graphql, type FragmentType } from "@/gql";

/**
 * O editor pede o que o editor precisa: o id para o command, e o título e o corpo ATUAIS — que ele
 * usa como placeholder, para deixar claro o que acontece quando o campo fica em branco.
 *
 * `PostArticle_post` também pede `content`, e isso não é duplicação de transferência: o servidor
 * resolve o campo uma vez e o Apollo o normaliza uma vez. Dois fragmentos pedindo o mesmo campo é
 * exatamente o caso que a composição de fragmentos resolve.
 */
export const PostEditor_post = graphql(`
    fragment PostEditor_post on Post {
        id
        title
        content
    }
`);

interface UpdateRunner {
    run: (options: {
        variables: { input: { id: string; title?: string | null; content?: string | null } };
    }) => Promise<unknown>;
    loading: boolean;
    error?: unknown;
}

/**
 * Update PARCIAL: o que fica em branco não vai na mutation.
 *
 * Não é economia de bytes — é o contrato. `UpdatePostInput` tem `title` e `content` opcionais com
 * `@Pattern`, e não `@NotBlank`, porque uma constraint é IGNORADA quando o valor é `null`. Mandar
 * string vazia significaria "apague o título"; omitir significa "não mexa nele".
 */
export function PostEditor({
    post,
    update,
}: {
    post: FragmentType<typeof PostEditor_post>;
    update: UpdateRunner;
}) {
    const current = getFragmentData(PostEditor_post, post);
    const [title, setTitle] = useState("");
    const [content, setContent] = useState("");

    const nothingToSend = title.trim() === "" && content.trim() === "";

    return (
        <form
            className="space-y-3"
            onSubmit={(event) => {
                event.preventDefault();
                void update
                    .run({
                        variables: {
                            input: {
                                id: current.id,
                                title: title.trim() === "" ? null : title,
                                content: content.trim() === "" ? null : content,
                            },
                        },
                    })
                    .then(() => {
                        toast.success("updatePost aceito", {
                            description: "A versão do agregado subiu.",
                        });
                        setTitle("");
                        setContent("");
                    })
                    .catch(() => {
                        /* `update.error` já carrega a mensagem traduzida pelo servidor */
                    });
            }}
        >
            <div className="space-y-2">
                <Label htmlFor="new-title">Novo título</Label>
                <Input
                    id="new-title"
                    value={title}
                    maxLength={200}
                    placeholder={current.title}
                    onChange={(event) => setTitle(event.target.value)}
                />
            </div>

            <div className="space-y-2">
                <Label htmlFor="new-content">Novo conteúdo</Label>
                <Textarea
                    id="new-content"
                    rows={5}
                    value={content}
                    placeholder={current.content.slice(0, 120)}
                    onChange={(event) => setContent(event.target.value)}
                />
            </div>

            {update.error ? <ErrorNotice title="updatePost falhou" error={update.error} /> : null}

            <Button type="submit" size="sm" disabled={update.loading || nothingToSend}>
                {update.loading ? <Loader2Icon className="animate-spin" /> : <SaveIcon />}
                Enviar o que foi preenchido
            </Button>
            <p className="text-[11px] text-muted-foreground">
                Campos em branco não entram na mutation. Escrever no post de outro autor responde{" "}
                <span className="font-mono">FORBIDDEN</span> — a checagem está no domínio
                (<span className="font-mono">Post.assertWrittenBy</span>), não no resolver.
            </p>
        </form>
    );
}

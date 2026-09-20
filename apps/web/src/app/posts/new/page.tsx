import { CreatePostForm } from './_components/create-post-form';

export default function NewPostPage() {
  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Escrever</h1>
        <p className="text-sm text-muted-foreground">
          A mutation responde a versão 1 — sem tag. A versão 2 chega depois, por
          outro serviço.
        </p>
      </div>
      <CreatePostForm />
    </div>
  );
}

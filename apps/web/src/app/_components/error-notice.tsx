import { TriangleAlertIcon } from 'lucide-react';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';

/**
 * O erro como ele chega — e é assim que tem de ser numa aplicação de teste.
 *
 * O servidor traduz exceção de domínio em `code` (`NOT_FOUND`, `FORBIDDEN`, `BAD_REQUEST`…) num
 * interceptador CDI ligado por `@TranslatesErrors`. Esse código é a informação: esconder a mensagem
 * atrás de um "algo deu errado" apagaria justamente o que se veio medir.
 */
export function ErrorNotice({
  title = 'A operação falhou',
  error,
}: {
  title?: string;
  error: unknown;
}) {
  const message =
    error instanceof Error
      ? error.message
      : typeof error === 'string'
        ? error
        : String(error);

  return (
    <Alert variant="destructive">
      <TriangleAlertIcon />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>
        <span className="font-mono text-xs break-all">{message}</span>
      </AlertDescription>
    </Alert>
  );
}

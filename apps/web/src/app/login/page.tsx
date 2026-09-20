import { Suspense } from 'react';
import Link from 'next/link';
import { CheckCircle2Icon } from 'lucide-react';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { buttonVariants } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { readSession } from '@/lib/auth/cookies';
import { COGNITO_ISSUER } from '@/lib/env';
import { cn } from '@/lib/utils';

import { LoginForm } from './_components/login-form';

/**
 * Esta página NÃO redireciona quem já tem sessão — e essa ausência custou horas para ser entendida.
 *
 * <h2>A corrida</h2>
 * Havia aqui um `if (await readSession()) redirect("/feed")`, que é o reflexo certo. O problema é que
 * o Next <b>re-renderiza a rota atual depois de TODA server action</b>, e manda o resultado junto com
 * a resposta da ação. Então, no instante em que `signIn` gravava o cookie:
 *
 * <ol>
 *   <li>a ação devolvia `{ status: "ok" }`;</li>
 *   <li>o Next re-renderizava `/login` — que agora via a sessão e chamava `redirect("/feed")`;</li>
 *   <li>o roteador navegava SUAVE para `/feed`, reaproveitando o payload ANÔNIMO do layout que o
 *       prefetch havia guardado enquanto o usuário ainda não tinha entrado;</li>
 *   <li>o formulário era desmontado antes de conseguir fazer a recarga de página que corrigiria
 *       tudo.</li>
 * </ol>
 *
 * O sintoma: login bem-sucedido, cookie gravado, `/feed` na tela — e o cabeçalho dizendo "Entrar".
 * Em `next dev` nada disso aparecia, porque lá não há prefetch.
 *
 * <h2>A saída</h2>
 * Tirar o redirect daqui. Quem navega é o formulário, com recarga de página inteira (ver
 * `_components/login-form.tsx` e `app/actions/auth.ts`). Quem já está logado vê um cartão dizendo
 * isso, o que numa aplicação de teste é mais útil do que um salto.
 */
export default async function LoginPage() {
  const session = await readSession();

  return (
    <div className="mx-auto max-w-md">
      <Card>
        <CardHeader>
          <CardTitle>Entrar</CardTitle>
          <CardDescription>
            A senha vai para uma{' '}
            <span className="font-mono">server action</span>, que fala com o
            Cognito e guarda os tokens em cookies{' '}
            <span className="font-mono">httpOnly</span>. O navegador nunca vê o
            refresh token.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {session ? (
            <Alert>
              <CheckCircle2Icon />
              <AlertTitle>Já autenticado como {session.user.email}</AlertTitle>
              <AlertDescription className="space-y-2">
                <p>
                  Provedor{' '}
                  <span className="font-mono">{session.user.provider}</span> ·
                  grupos{' '}
                  <span className="font-mono">
                    {session.user.groups.join(', ') || 'nenhum'}
                  </span>
                </p>
                <Link
                  href="/feed"
                  className={cn(
                    buttonVariants({ variant: 'outline', size: 'sm' }),
                  )}
                >
                  Ir para o feed
                </Link>
              </AlertDescription>
            </Alert>
          ) : null}

          {/* useSearchParams precisa de fronteira de Suspense para o Next poder
                        pré-renderizar o resto da página. */}
          <Suspense fallback={<Skeleton className="h-64 w-full" />}>
            <LoginForm />
          </Suspense>

          <p className="text-[11px] break-all text-muted-foreground">
            emissor: <span className="font-mono">{COGNITO_ISSUER || '—'}</span>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}

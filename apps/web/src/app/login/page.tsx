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

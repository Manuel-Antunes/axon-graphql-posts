'use client';

import { useState, useTransition } from 'react';
import { useSearchParams } from 'next/navigation';
import { KeyRoundIcon, Loader2Icon } from 'lucide-react';

import type { SignInState } from '@/app/actions/auth';
import { ErrorNotice } from '@/app/_components/error-notice';
import { signIn } from '@/app/actions/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

const seeded = [
  { email: 'manuel@example.com', label: 'Manuel', role: 'author' },
  { email: 'promovido@example.com', label: 'Promovido', role: 'author' },
  { email: 'leitor@example.com', label: 'Leitor', role: 'só lê' },
];

const idle: SignInState = { status: 'idle' };

export function LoginForm() {
  const params = useSearchParams();
  const next = params.get('next') ?? '/feed';

  const [state, setState] = useState<SignInState>(idle);
  const [pending, startTransition] = useTransition();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('segredo123');
  const [leaving, setLeaving] = useState(false);

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setState(idle);

    startTransition(async () => {
      const result = await signIn(idle, form);
      if (result.status === 'ok' && result.next) {
        setLeaving(true);
        window.location.assign(result.next);
        return;
      }
      setState(result);
    });
  }

  return (
    <div className="space-y-5">
      <form onSubmit={submit} className="space-y-4">
        <input type="hidden" name="next" value={next} />

        <div className="space-y-2">
          <Label htmlFor="email">E-mail</Label>
          <Input
            id="email"
            name="email"
            type="email"
            autoComplete="username"
            placeholder="manuel@example.com"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="password">Senha</Label>
          <Input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </div>

        {state.status === 'error' ? (
          <ErrorNotice
            title={state.code ?? 'Falha na autenticação'}
            error={state.message ?? 'O Cognito recusou as credenciais.'}
          />
        ) : null}

        <Button type="submit" className="w-full" disabled={pending || leaving}>
          {pending || leaving ? (
            <Loader2Icon className="animate-spin" />
          ) : (
            <KeyRoundIcon />
          )}
          {leaving ? 'Entrando…' : 'Entrar'}
        </Button>
      </form>

      <div className="space-y-2 rounded-lg border bg-muted/30 p-3">
        <p className="text-xs font-medium">Usuários semeados</p>
        <div className="flex flex-wrap gap-2">
          {seeded.map((user) => (
            <Button
              key={user.email}
              type="button"
              size="sm"
              variant="outline"
              onClick={() => {
                setEmail(user.email);
                setPassword('segredo123');
              }}
            >
              {user.label}
              <span className="ms-1 text-[10px] text-muted-foreground">
                {user.role}
              </span>
            </Button>
          ))}
        </div>
        <p className="text-[11px] text-muted-foreground">
          Senha <span className="font-mono">segredo123</span> para os três — a
          mesma do realm. A política do pool foi afrouxada em{' '}
          <span className="font-mono">infra/aws/identity</span> exatamente para
          isso.
        </p>
      </div>
    </div>
  );
}

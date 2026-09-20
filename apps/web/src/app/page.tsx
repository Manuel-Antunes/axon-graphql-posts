import Link from 'next/link';
import {
  ArrowRightIcon,
  KeyRoundIcon,
  NetworkIcon,
  NewspaperIcon,
  PenLineIcon,
  RadioIcon,
  UserIcon,
  WorkflowIcon,
} from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { COGNITO_ISSUER, GRAPHQL_UPSTREAM } from '@/lib/env';

const flows = [
  {
    href: '/login',
    icon: KeyRoundIcon,
    title: 'Entrar',
    what: 'USER_PASSWORD_AUTH no Cognito, por server action',
    proves: 'O bearer é o ID token, e o `aud` dele é conferido pela API.',
  },
  {
    href: '/feed',
    icon: NewspaperIcon,
    title: 'Feed',
    what: 'posts(first:, after:) — cursor connection',
    proves:
      'Paginação Relay e fragmentos por componente: o card não pede `content`.',
  },
  {
    href: '/posts/new',
    icon: PenLineIcon,
    title: 'Escrever',
    what: 'createPost — exige a role author',
    proves:
      'O post nasce na versão 1, SEM tag. Quem completa é o outro serviço.',
  },
  {
    href: '/saga',
    icon: WorkflowIcon,
    title: 'Saga',
    what: 'cria e observa a travessia v1 → v2',
    proves: 'A consistência é eventual, e o tempo dela é mensurável aqui.',
  },
  {
    href: '/live',
    icon: RadioIcon,
    title: 'Tempo real',
    what: 'onPostCreated/onPostUpdated por SSE',
    proves:
      'O transporte é GraphQL over SSE. Contra o Lambda ele NÃO entrega — e a página diz por quê.',
  },
  {
    href: '/me',
    icon: UserIcon,
    title: 'Identidade',
    what: 'me — a interface User, polimórfica',
    proves:
      'possibleTypes no cache, e o account linking entre Keycloak e Cognito.',
  },
  {
    href: '/federation',
    icon: NetworkIcon,
    title: 'Federação',
    what: '_entities(representations:)',
    proves: 'Resolve entidade por chave, em lote, e SEM token.',
  },
];

export default function Home() {
  return (
    <div className="space-y-8">
      <section className="space-y-3">
        <Badge variant="outline" className="font-mono text-[10px]">
          cliente de teste
        </Badge>
        <h1 className="text-3xl font-semibold tracking-tight text-balance">
          Sete fluxos, e o que cada um prova
        </h1>
        <p className="max-w-2xl text-muted-foreground">
          Este cliente não existe para ser bonito: cada página exercita uma
          decisão de arquitetura do{' '}
          <span className="font-mono text-foreground">axonposts</span> e mostra
          o resultado como ele é — inclusive quando o resultado é que alguma
          coisa não atravessa.
        </p>
        <dl className="grid gap-2 pt-2 text-xs text-muted-foreground sm:grid-cols-2">
          <div>
            <dt className="font-medium text-foreground">Subgraph</dt>
            <dd className="font-mono break-all">{GRAPHQL_UPSTREAM}</dd>
          </div>
          <div>
            <dt className="font-medium text-foreground">Emissor</dt>
            <dd className="font-mono break-all">
              {COGNITO_ISSUER || 'não configurado'}
            </dd>
          </div>
        </dl>
      </section>

      <section className="grid gap-4 sm:grid-cols-2">
        {flows.map((flow) => (
          <Link key={flow.href} href={flow.href} className="group">
            <Card className="h-full transition-colors group-hover:border-foreground/25">
              <CardHeader>
                <div className="flex items-center gap-2">
                  <flow.icon
                    className="size-4 text-muted-foreground"
                    aria-hidden
                  />
                  <CardTitle className="text-base">{flow.title}</CardTitle>
                  <ArrowRightIcon className="ms-auto size-4 opacity-0 transition-opacity group-hover:opacity-60" />
                </div>
                <CardDescription className="font-mono text-[11px]">
                  {flow.what}
                </CardDescription>
              </CardHeader>
              <CardContent className="text-sm text-muted-foreground">
                {flow.proves}
              </CardContent>
            </Card>
          </Link>
        ))}
      </section>
    </div>
  );
}

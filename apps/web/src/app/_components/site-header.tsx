'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { LogOutIcon, RadioIcon } from 'lucide-react';

import { useSession } from '@/app/_providers/session-provider';
import { signOut } from '@/app/actions/auth';
import { Badge } from '@/components/ui/badge';
import { Button, buttonVariants } from '@/components/ui/button';
import { upstreamHost } from '@/lib/env';
import { cn } from '@/lib/utils';

const routes = [
  { href: '/', label: 'Roteiro' },
  { href: '/feed', label: 'Feed' },
  { href: '/posts/new', label: 'Escrever' },
  { href: '/saga', label: 'Saga' },
  { href: '/live', label: 'Tempo real' },
  { href: '/me', label: 'Identidade' },
  { href: '/federation', label: 'Federação' },
];

export function SiteHeader() {
  const pathname = usePathname();
  const { session, isAuthor } = useSession();

  return (
    <header className="sticky top-0 z-40 border-b bg-background/80 backdrop-blur-sm">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
        <Link href="/" className="flex items-center gap-2 font-semibold">
          <RadioIcon className="size-4 text-emerald-500" aria-hidden />
          axonposts
        </Link>

        <nav className="flex flex-wrap items-center gap-1 text-sm">
          {routes.map((route) => (
            <Link
              key={route.href}
              href={route.href}
              className={cn(
                'rounded-md px-2 py-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground',
                pathname === route.href && 'bg-accent text-foreground',
              )}
            >
              {route.label}
            </Link>
          ))}
        </nav>

        <div className="ms-auto flex items-center gap-2">
          <Badge
            variant="outline"
            className="hidden font-mono text-[10px] sm:inline-flex"
          >
            {upstreamHost()}
          </Badge>
          {session ? (
            <>
              <span className="text-xs text-muted-foreground">
                {session.user.email}
                {isAuthor ? (
                  <Badge variant="secondary" className="ms-2 text-[10px]">
                    author
                  </Badge>
                ) : null}
              </span>
              {/* A ação apaga os cookies; quem navega é esta função, com recarga —
                                mesma razão do login (ver `app/actions/auth.ts`). */}
              <Button
                variant="ghost"
                size="sm"
                aria-label="Sair"
                onClick={() => {
                  void signOut().then(() => window.location.assign('/login'));
                }}
              >
                <LogOutIcon />
              </Button>
            </>
          ) : (
            <Link
              href="/login"
              className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
            >
              Entrar
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}

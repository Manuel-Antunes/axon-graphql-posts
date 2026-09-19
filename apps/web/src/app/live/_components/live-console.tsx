"use client";

import { useState } from "react";
import Link from "next/link";
import { InfoIcon, RadioIcon, TrashIcon } from "lucide-react";

import { ErrorNotice } from "@/app/_components/error-notice";
import { RelativeTime } from "@/app/_components/relative-time";
import { StatusDot } from "@/app/_components/status-dot";
import { VersionBadge } from "@/app/_components/version-badge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { upstreamHost } from "@/lib/env";

import { useRecentPosts } from "../_hooks/use-recent-posts";
import { usePostStream } from "../_hooks/use-post-stream";

const time = new Intl.DateTimeFormat("pt-BR", {
  timeStyle: "medium",
  timeZone: "America/Sao_Paulo",
});

export function LiveConsole() {
  const [subscribing, setSubscribing] = useState(true);
  const stream = usePostStream(subscribing);
  const recent = useRecentPosts();

  return (
    <div className="space-y-5">
      <Alert>
        <InfoIcon />
        <AlertTitle>Isto é a subscription do Axon, sem intermediário</AlertTitle>
        <AlertDescription className="space-y-1">
          <p>
            O navegador fala com <span className="font-mono">/api/graphql</span>
            , o proxy desta aplicação, e tudo o que ele faz é um{" "}
            <span className="font-mono">fetch</span> para{" "}
            <span className="font-mono">{upstreamHost()}</span> devolvendo o
            corpo da resposta. Os bytes que chegam aqui são os que o{" "}
            <span className="font-mono">posts-api</span> escreveu.
          </p>
          <p>
            O proxy existe por uma razão só: é ele que põe o{" "}
            <span className="font-mono">Authorization</span>, lendo um cookie{" "}
            <span className="font-mono">httpOnly</span> — o token nunca chega ao
            JavaScript desta página.
          </p>
        </AlertDescription>
      </Alert>

      <Card>
        <CardHeader>
          <CardTitle className="flex flex-wrap items-center gap-2 text-base">
            <RadioIcon className="size-4" aria-hidden />
            onPostCreated / onPostUpdated
          </CardTitle>
          <CardDescription>
            Quem empurra é o processor streaming do Axon, que lê o event store —
            por isso o evento chega mesmo quando a mutation foi atendida por
            outro container.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-center gap-4">
            <span className="flex items-center gap-3">
              <Switch
                id="subscribing"
                checked={subscribing}
                onCheckedChange={setSubscribing}
              />
              <Label htmlFor="subscribing">Assinar</Label>
            </span>
            <span className="text-xs">
              onPostCreated <StatusDot status={stream.createdStatus} />
            </span>
            <span className="text-xs">
              onPostUpdated <StatusDot status={stream.updatedStatus} />
            </span>
          </div>

          {stream.error ? (
            <ErrorNotice title="A subscription falhou" error={stream.error} />
          ) : null}

          <EventList
            empty="Conectado. Nenhum evento ainda."
            onClear={stream.clear}
            rows={stream.events.map(event => ({
              key: `${event.postId}-${event.receivedAt}`,
              at: event.receivedAt,
              badge: event.source,
              title: event.title,
              version: event.version,
              postId: event.postId,
            }))}
          />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Estado atual</CardTitle>
          <CardDescription>
            Buscado no servidor pelo{" "}
            <span className="font-mono">PreloadQuery</span>. É a referência
            contra a qual os eventos acima são lidos.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {recent.error ? (
            <ErrorNotice
              title="Não foi possível ler os posts"
              error={recent.error}
            />
          ) : (
            <ul className="space-y-2">
              {recent.posts.map(post => (
                <li
                  key={post.id}
                  className="flex flex-wrap items-center justify-between gap-2 border-b pb-2 text-sm last:border-0"
                >
                  <Link href={`/posts/${post.id}`} className="hover:underline">
                    {post.title}
                  </Link>
                  <span className="flex items-center gap-3">
                    <VersionBadge version={post.version} />
                    <span className="text-xs text-muted-foreground">
                      <RelativeTime iso={post.updatedAt} />
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <p className="text-xs text-muted-foreground">
        Para ver funcionando: deixe esta página aberta e crie um post em{" "}
        <Link href="/posts/new" className="underline">
          Escrever
        </Link>{" "}
        noutra aba. Chega um <span className="font-mono">onPostCreated</span>{" "}
        quando o outro serviço fecha a saga (versão 2), e um{" "}
        <span className="font-mono">onPostUpdated</span> a cada edição depois
        disso.
      </p>
    </div>
  );
}

type Row = {
  key: string;
  at: number;
  badge: string;
  title: string;
  version: number;
  postId: string;
};

function EventList({
  rows,
  empty,
  onClear,
}: {
  rows: Row[];
  empty: string;
  onClear: () => void;
}) {
  if (rows.length === 0) {
    return (
      <p className="rounded-md border border-dashed p-4 text-xs text-muted-foreground">
        {empty}
      </p>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex justify-end">
        <Button size="sm" variant="ghost" onClick={onClear}>
          <TrashIcon />
          Limpar
        </Button>
      </div>
      <ul className="max-h-80 space-y-2 overflow-auto">
        {rows.map(row => (
          <li key={row.key} className="rounded-md border p-2 text-xs">
            <div className="flex items-center justify-between gap-2">
              <Badge variant="secondary" className="font-mono text-[10px]">
                {row.badge}
              </Badge>
              <span className="font-mono text-muted-foreground">
                {time.format(new Date(row.at))}
              </span>
            </div>
            <Link
              href={`/posts/${row.postId}`}
              className="mt-1 block font-medium hover:underline"
            >
              {row.title}
            </Link>
            <VersionBadge version={row.version} className="mt-1" />
          </li>
        ))}
      </ul>
    </div>
  );
}

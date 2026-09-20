import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

/**
 * A versão do post — que neste sistema não é um número de auditoria, é o ESTADO da saga.
 *
 * `PostPreCreated` nasce na v1, sem tag: o post existe e não está completo. Quem decide a primeira
 * tag é o outro serviço, e o `PostCreated` que volta leva o agregado à v2. Mostrar "v1" e "v2" sem
 * dizer isso esconderia justamente o que estas páginas existem para observar.
 */
export function VersionBadge({
  version,
  className,
}: {
  version: number;
  className?: string;
}) {
  const meaning =
    version <= 1
      ? {
          label: 'v1 · aguardando tag',
          tone: 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300',
        }
      : version === 2
        ? {
            label: 'v2 · saga fechada',
            tone: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
          }
        : {
            label: `v${version} · editado`,
            tone: 'border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300',
          };

  return (
    <Badge
      variant="outline"
      className={cn('font-mono text-[11px]', meaning.tone, className)}
    >
      {meaning.label}
    </Badge>
  );
}

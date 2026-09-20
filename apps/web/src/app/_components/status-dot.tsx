import { cn } from '@/lib/utils';

export type StreamStatus = 'idle' | 'connecting' | 'open' | 'error' | 'closed';

const tones: Record<StreamStatus, { dot: string; label: string }> = {
  idle: { dot: 'bg-muted-foreground/40', label: 'parado' },
  connecting: { dot: 'bg-amber-500 animate-pulse', label: 'conectando' },
  open: { dot: 'bg-emerald-500', label: 'aberto' },
  error: { dot: 'bg-red-500', label: 'erro' },
  closed: { dot: 'bg-muted-foreground/60', label: 'fechado' },
};

export function StatusDot({
  status,
  className,
}: {
  status: StreamStatus;
  className?: string;
}) {
  const tone = tones[status];
  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 text-xs text-muted-foreground',
        className,
      )}
    >
      <span className={cn('size-2 rounded-full', tone.dot)} aria-hidden />
      {tone.label}
    </span>
  );
}

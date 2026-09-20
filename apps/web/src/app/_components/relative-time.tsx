const format = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short',
  timeStyle: 'medium',
  timeZone: 'America/Sao_Paulo',
});

export function RelativeTime({
  iso,
  className,
}: {
  iso: string;
  className?: string;
}) {
  const date = new Date(iso);
  const valid = !Number.isNaN(date.getTime());
  return (
    <time dateTime={iso} title={iso} className={className}>
      {valid ? format.format(date) : iso}
    </time>
  );
}

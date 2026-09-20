/**
 * Uma data ISO-8601 legível — e determinística.
 *
 * O `DateTime` do SmallRye chega como string. Formatá-la com o locale e o fuso do AMBIENTE faria o
 * servidor e o navegador produzirem textos diferentes, e o React reclamaria de hidratação. Fixando os
 * dois (`pt-BR`, `America/Sao_Paulo`) o resultado é o mesmo dos dois lados.
 */
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

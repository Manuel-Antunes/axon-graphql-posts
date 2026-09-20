/**
 * O gancho de instrumentação do Next — e por que ele é só um `if`.
 *
 * O App Router chama `register()` UMA vez por processo, antes de servir qualquer coisa, e o chama nos
 * DOIS runtimes. Os SDKs são diferentes: o do Node depende de `async_hooks` e de patch de módulo, o
 * do edge não pode nem importar isso. Por isso o `await import` dentro da condição, e não um import
 * no topo — no topo, o bundle de edge tentaria carregar o pacote de Node e o BUILD quebraria.
 */
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    await import('./instrumentation.node');
  }
  if (process.env.NEXT_RUNTIME === 'edge') {
    await import('./instrumentation.edge');
  }
}

/// <reference path="../../../.sst/platform/config.d.ts" />

/**
 * A mensageria: o topic, as filas e as bindings entre eles.
 *
 * A ordem do `import` abaixo é a ordem de dependência — `routing` precisa dos dois anteriores —, e é
 * também a ordem em que se lê a topologia: primeiro o que existe, depois quem fala com quem.
 */
export { postEvents } from "./topic";
export { precreated, changes, completed } from "./queues";

// Só efeito colateral: este módulo CRIA as subscriptions e não exporta nada. Está por último de
// propósito.
import "./routing";

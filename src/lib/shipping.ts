import { z } from 'zod';

// Mirrors GET /api/v1/checkout/shipping. The server is the only authority on the charged amount;
// this is used to show the same number before the order is created.
export const shippingRulesSchema = z.object({
  currency: z.string(), flat: z.number(), freeFrom: z.number().nullish(),
  zones: z.array(z.object({ state: z.string(), rate: z.number() })),
  onlyZones: z.boolean(), deliveryEstimate: z.string(),
});
export type ShippingRules = z.infer<typeof shippingRulesSchema>;

const norm = (state: string) => {
  const name = state.normalize('NFD').replace(/\p{M}/gu, '').trim().toLowerCase();
  const aliases: Record<string, string> = {
    mexico: 'estado de mexico', edomex: 'estado de mexico', cdmx: 'ciudad de mexico',
    'distrito federal': 'ciudad de mexico', 'coahuila de zaragoza': 'coahuila',
    'michoacan de ocampo': 'michoacan', 'veracruz de ignacio de la llave': 'veracruz',
  };
  return aliases[name] ?? name;
};

/** Shipping cost for a state and subtotal, or null when the store does not deliver there. */
export function shippingFor(rules: ShippingRules, state: string, subtotal: number): number | null {
  const zone = rules.zones.find(z => norm(z.state) === norm(state));
  if (!zone && rules.onlyZones) return null;
  if (rules.freeFrom != null && subtotal >= rules.freeFrom) return 0;
  return zone ? zone.rate : rules.flat;
}

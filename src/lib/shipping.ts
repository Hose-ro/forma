import { z } from 'zod';

// Mirrors GET /api/v1/checkout/shipping. The server is the only authority on the charged amount;
// this is used to show the same number before the order is created.
export const shippingRulesSchema = z.object({
  currency: z.string(), flat: z.number(), freeFrom: z.number().nullish(),
  zones: z.array(z.object({ state: z.string(), rate: z.number() })),
  onlyZones: z.boolean(), deliveryEstimate: z.string(),
});
export type ShippingRules = z.infer<typeof shippingRulesSchema>;

export const MX_STATES = ['Aguascalientes', 'Baja California', 'Baja California Sur', 'Campeche', 'Chiapas', 'Chihuahua', 'Ciudad de México', 'Coahuila', 'Colima', 'Durango', 'Estado de México', 'Guanajuato', 'Guerrero', 'Hidalgo', 'Jalisco', 'Michoacán', 'Morelos', 'Nayarit', 'Nuevo León', 'Oaxaca', 'Puebla', 'Querétaro', 'Quintana Roo', 'San Luis Potosí', 'Sinaloa', 'Sonora', 'Tabasco', 'Tamaulipas', 'Tlaxcala', 'Veracruz', 'Yucatán', 'Zacatecas'];

const norm = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').trim().toLowerCase();

/** States the store delivers to. */
export const deliveryStates = (rules: ShippingRules | null) => (rules?.onlyZones ? rules.zones.map(z => z.state) : MX_STATES);

/** Shipping cost for a state and subtotal, or null when the store does not deliver there. */
export function shippingFor(rules: ShippingRules, state: string, subtotal: number): number | null {
  const zone = rules.zones.find(z => norm(z.state) === norm(state));
  if (!zone && rules.onlyZones) return null;
  if (rules.freeFrom != null && subtotal >= rules.freeFrom) return 0;
  return zone ? zone.rate : rules.flat;
}

// Business identity shown in the footer, legal texts and policies. Every value comes from NEXT_PUBLIC_* variables
// (see .env.example) so real data never has to be hard-coded. Defaults keep the demo storefront working.
const env = (value: string | undefined, fallback: string) => (value && value.trim()) || fallback;

/** `live` = real sales (production Mercado Pago, real shipping). Anything else keeps the demo/test labels. */
export const LIVE = process.env.NEXT_PUBLIC_STORE_MODE === 'live';

export const BUSINESS = {
  legalName: env(process.env.NEXT_PUBLIC_BUSINESS_LEGAL_NAME, 'FORMA. · Proyecto de demostración'),
  taxId: env(process.env.NEXT_PUBLIC_BUSINESS_TAX_ID, ''),
  address: env(process.env.NEXT_PUBLIC_BUSINESS_ADDRESS, 'Ciudad de México, México (ilustrativo)'),
  email: env(process.env.NEXT_PUBLIC_BUSINESS_EMAIL, 'hola@forma.example'),
  phone: env(process.env.NEXT_PUBLIC_BUSINESS_PHONE, ''),
  hours: env(process.env.NEXT_PUBLIC_BUSINESS_HOURS, 'Lunes a viernes, 9:00 a 18:00 h (centro de México)'),
  /** Calendar days the customer has to request a return. */
  returnDays: Number(process.env.NEXT_PUBLIC_RETURN_DAYS) || 30,
  /** Business days to dispatch an order after payment is confirmed. */
  handlingTime: env(process.env.NEXT_PUBLIC_HANDLING_TIME, '1 a 2 días hábiles'),
};

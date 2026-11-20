import { z } from 'zod';

const shippingFields = {
  street: z.string().trim().min(5, 'Escribe calle y número').max(200),
  line2: z.string().trim().max(200),
  neighborhood: z.string().trim().min(1, 'Selecciona tu colonia'),
  city: z.string().trim().min(2, 'Escribe tu ciudad'),
  state: z.string().trim().min(2, 'Selecciona tu estado'),
  postalCode: z.string().regex(/^\d{5}$/, 'Escribe 5 dígitos'),
};
/** The Mexican shipping address fields shared by checkout and the saved address book. */
export type ShippingFields = { street: string; line2: string; neighborhood: string; city: string; state: string; postalCode: string };

export const customerSchema = z.object({
  firstName: z.string().trim().min(2, 'Escribe tu nombre'),
  lastName: z.string().trim().min(2, 'Escribe tus apellidos'),
  email: z.email('Escribe un correo válido'),
  phone: z.string().trim().regex(/^[+\d\s()-]{10,20}$/, 'Escribe un teléfono válido'),
  ...shippingFields,
  country: z.literal('México'),
  consent: z.boolean().refine(v => v, 'Acepta las condiciones para continuar'),
});
export type CheckoutValues = z.infer<typeof customerSchema>;

export const savedAddressFormSchema = z.object({
  ...shippingFields,
  recipientName: z.string().trim().min(2, 'Escribe quién recibe').max(200),
  phone: z.string().trim().regex(/^([+\d\s()-]{10,20})?$/, 'Escribe un teléfono válido'),
  label: z.string().trim().max(60),
  makeDefault: z.boolean(),
});
export type SavedAddressFormValues = z.infer<typeof savedAddressFormSchema>;

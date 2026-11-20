'use client';

import { useEffect, useState } from 'react';
import { useWatch, type UseFormReturn } from 'react-hook-form';
import { z } from 'zod';
import { api } from '@/lib/api/client';
import { ApiError } from '@/lib/api/http';
import type { ShippingFields } from '@/lib/validation';

const postalSchema = z.object({
  postalCode: z.string(), municipality: z.string(), state: z.string(),
  neighborhoods: z.array(z.object({ name: z.string(), type: z.string(), locality: z.string() })),
});
type PostalCode = z.infer<typeof postalSchema>;
type Lookup = { code: string; data?: PostalCode; error?: 'missing' | 'unavailable' };

/** Postal-code driven address fields, shared by checkout and the customer's address book. */
export function CheckoutAddress<T extends ShippingFields>({ form }: { form: UseFormReturn<T> }) {
  // Every form using this component holds the shipping fields; narrow it once for typed field names.
  const { control, register, setValue, clearErrors, formState: { errors } } = form as unknown as UseFormReturn<ShippingFields>;
  const code = useWatch({ control, name: 'postalCode' }) || '';
  const complete = /^[0-9]{5}$/.test(code);
  const [lookup, setLookup] = useState<Lookup | null>(null);
  const [retry, setRetry] = useState(0);
  const result = lookup?.code === code ? lookup : null;

  function clearAddress() {
    setValue('city', '');
    setValue('state', '');
    setValue('neighborhood', '');
    clearErrors(['city', 'state', 'neighborhood']);
  }

  useEffect(() => {
    if (!complete) return;
    let active = true;
    const timer = window.setTimeout(async () => {
      try {
        const data = await api.get(`/checkout/postal-codes/${code}`, postalSchema);
        if (!active) return;
        setValue('city', data.municipality, { shouldValidate: true });
        setValue('state', data.state, { shouldValidate: true });
        if (data.neighborhoods.length === 1) {
          setValue('neighborhood', data.neighborhoods[0].name);
          clearErrors('neighborhood');
        }
        setLookup({ code, data });
      } catch (error) {
        if (active) {
          setLookup({ code, error: error instanceof ApiError && error.status === 404 ? 'missing' : 'unavailable' });
        }
      }
    }, 300);
    return () => { active = false; window.clearTimeout(timer); };
  }, [code, complete, retry, setValue, clearErrors]);

  const postalError = result?.error === 'missing' ? 'No encontramos ese código postal. Revisa los 5 dígitos.'
    : result?.error === 'unavailable' ? 'No pudimos verificar el código postal.' : errors.postalCode?.message;
  const data = result?.data;
  const neighborhood = useWatch({ control, name: 'neighborhood' }) || '';

  return <>
    <label>Código postal
      <input {...register('postalCode', { onChange: () => { clearAddress(); setLookup(null); } })}
        type="text" inputMode="numeric" autoComplete="shipping postal-code" maxLength={5} pattern="[0-9]{5}"
        aria-invalid={!!postalError} aria-describedby="postal-status" />
      <span id="postal-status" className={postalError ? 'error' : 'muted'} aria-live="polite">
        {postalError || (result?.data ? 'Código postal verificado.' : complete ? 'Buscando colonias…' : 'Escribe los 5 dígitos.')}
      </span>
    </label>
    <label>Colonia
      <select {...register('neighborhood')} value={neighborhood} disabled={!data}
        aria-invalid={!!errors.neighborhood} aria-describedby={errors.neighborhood ? 'neighborhood-error' : undefined}>
        <option value="">{data ? 'Selecciona tu colonia' : 'Ingresa tu código postal'}</option>
        {[...new Set(data?.neighborhoods.map(n => n.name))].map(name => <option key={name}>{name}</option>)}
      </select>
      {data && errors.neighborhood && <span className="error" id="neighborhood-error">{errors.neighborhood.message}</span>}
    </label>
    {result?.error === 'unavailable' && <div className="span-2">
      <button className="text-link" type="button" onClick={() => { setLookup(null); setRetry(n => n + 1); }}>Reintentar búsqueda</button>
    </div>}
    <label>Municipio o alcaldía
      <input {...register('city')} readOnly aria-readonly="true" />
    </label>
    <label>Estado
      <input {...register('state')} readOnly aria-readonly="true" />
    </label>
    <label>Calle y número exterior
      <input {...register('street')} autoComplete="shipping address-line1" maxLength={200}
        aria-invalid={!!errors.street} aria-describedby={errors.street ? 'street-error' : undefined} />
      {errors.street && <span className="error" id="street-error">{errors.street.message}</span>}
    </label>
    <label>Interior o referencias (opcional)
      <input {...register('line2')} autoComplete="shipping address-line2" maxLength={200} />
    </label>
    <p className="span-2 muted">País de envío: México</p>
  </>;
}

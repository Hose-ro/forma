'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { savedAddressSchema, savedAddressesSchema, type SavedAddress } from '@/lib/api/schemas';
import { savedAddressFormSchema, type SavedAddressFormValues } from '@/lib/validation';
import type { User } from '@/types';
import { CheckoutAddress } from './checkout-address';

export const MAX_SAVED_ADDRESSES = 10;

/** Request body for POST /me/addresses and PATCH /me/addresses/{id}. */
export function addressRequest(values: SavedAddressFormValues) {
  return {
    label: values.label, recipientName: values.recipientName, line1: values.street, line2: values.line2,
    neighborhood: values.neighborhood, city: values.city, state: values.state, postalCode: values.postalCode,
    country: 'MX', phone: values.phone, makeDefault: values.makeDefault,
  };
}

function saveError(err: unknown) {
  if (err instanceof ApiError && err.detail === 'Address limit reached') {
    return `Ya tienes el máximo de ${MAX_SAVED_ADDRESSES} direcciones guardadas.`;
  }
  return errorMessage(err, 'No pudimos guardar la dirección. Inténtalo de nuevo.');
}

export function AccountAddresses({ user }: { user: User }) {
  const [addresses, setAddresses] = useState<SavedAddress[] | null>(null);
  const [editing, setEditing] = useState<SavedAddress | 'new' | null>(null);
  const [loadingError, setLoadingError] = useState('');
  const [reload, setReload] = useState(0);
  const [feedback, setFeedback] = useState<{ message: string; error: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    api.get('/me/addresses', savedAddressesSchema).then(items => {
      if (active) setAddresses(items);
    }, err => {
      if (active) setLoadingError(errorMessage(err, 'No pudimos cargar tus direcciones.'));
    });
    return () => { active = false; };
  }, [reload]);

  function edit(address: SavedAddress | 'new' | null) {
    setEditing(address);
    setFeedback(null);
  }

  async function run(action: () => Promise<unknown>, message: string, fallback: (err: unknown) => string) {
    setBusy(true);
    setFeedback(null);
    try {
      await action();
      setEditing(null);
      setFeedback({ message, error: false });
      // The server decides which address ends up as the default; reload to show its answer.
      setReload(value => value + 1);
    } catch (err) {
      setFeedback({ message: fallback(err), error: true });
    } finally {
      setBusy(false);
    }
  }

  function save(values: SavedAddressFormValues) {
    const address = editing === 'new' ? null : editing;
    const body = addressRequest(values);
    void run(() => address ? api.patch(`/me/addresses/${address.id}`, savedAddressSchema, body)
      : api.post('/me/addresses', savedAddressSchema, body),
    address ? 'Dirección actualizada.' : 'Dirección guardada.', saveError);
  }

  function makeDefault(item: SavedAddress) {
    void run(() => api.patch(`/me/addresses/${item.id}/default`, savedAddressSchema),
      'Dirección predeterminada actualizada.', saveError);
  }

  function remove(item: SavedAddress) {
    if (!window.confirm(`¿Eliminar la dirección ${item.line1}?`)) return;
    void run(() => api.delete(`/me/addresses/${item.id}`, null), 'Dirección eliminada.',
      err => errorMessage(err, 'No pudimos eliminar la dirección. Inténtalo de nuevo.'));
  }

  const sorted = addresses && [...addresses].sort((a, b) => Number(b.isDefault) - Number(a.isDefault));
  const current = editing === 'new' ? null : editing;

  return <section className="account-addresses" aria-labelledby="account-addresses-title">
    <h2 id="account-addresses-title">Direcciones de envío</h2>
    {loadingError ? <>
      <p className="error" role="alert">{loadingError}</p>
      <button type="button" className="text-link" onClick={() => {
        setLoadingError('');
        setReload(value => value + 1);
      }}>Reintentar</button>
    </> : !sorted ? <p className="muted" role="status">Cargando direcciones…</p> : <>
      {sorted.length > 0 ? <ul className="account-address-list">
        {sorted.map(item => <li key={item.id}>
          <div className="account-address-heading">
            <strong>{item.label || item.recipientName}</strong>
            {item.isDefault && <span className="account-address-default">Predeterminada</span>}
          </div>
          {item.label && <p>{item.recipientName}</p>}
          <p>{[item.line1, item.line2].filter(Boolean).join(', ')}</p>
          <p>{[item.neighborhood, item.city].filter(Boolean).join(', ')}</p>
          <p>{item.state} · CP {item.postalCode}</p>
          <div className="account-address-actions">
            <button type="button" className="text-link" disabled={busy || editing !== null}
              aria-label={`Editar dirección ${item.line1}`} onClick={() => edit(item)}>Editar</button>
            {!item.isDefault && <button type="button" className="text-link" disabled={busy || editing !== null}
              aria-label={`Usar ${item.line1} como dirección predeterminada`}
              onClick={() => makeDefault(item)}>Hacer predeterminada</button>}
            <button type="button" className="text-link" disabled={busy || editing !== null}
              aria-label={`Eliminar dirección ${item.line1}`} onClick={() => remove(item)}>Eliminar</button>
          </div>
        </li>)}
      </ul> : !editing && <p className="muted">Aún no tienes direcciones guardadas.</p>}
      {!editing && (sorted.length < MAX_SAVED_ADDRESSES
        ? <button type="button" className="text-link" disabled={busy} onClick={() => edit('new')}>Agregar dirección</button>
        : <p className="muted">Puedes guardar hasta {MAX_SAVED_ADDRESSES} direcciones.</p>)}
      {editing && <AddressForm key={current?.id ?? 'new'} address={current} user={user} busy={busy}
        offerDefault={!current?.isDefault && sorted.length > (current ? 1 : 0)}
        onSubmit={save} onCancel={() => edit(null)} />}
    </>}
    {feedback && <p className={feedback.error ? 'error' : 'success-message'}
      role={feedback.error ? 'alert' : 'status'}>{feedback.message}</p>}
  </section>;
}

function AddressForm({ address, user, busy, offerDefault, onSubmit, onCancel }: {
  address: SavedAddress | null; user: User; busy: boolean; offerDefault: boolean;
  onSubmit: (values: SavedAddressFormValues) => void; onCancel: () => void;
}) {
  const form = useForm<SavedAddressFormValues>({
    resolver: zodResolver(savedAddressFormSchema),
    defaultValues: {
      recipientName: address?.recipientName ?? `${user.firstName} ${user.lastName}`.trim(),
      street: address?.line1 ?? '', line2: address?.line2 ?? '', neighborhood: address?.neighborhood ?? '',
      city: address?.city ?? '', state: address?.state ?? '', postalCode: address?.postalCode ?? '',
      phone: address ? address.phone ?? '' : user.phone ?? '', label: address?.label ?? '', makeDefault: false,
    },
  });
  const { register, handleSubmit, formState: { errors } } = form;

  return <form className="account-address-form" noValidate onSubmit={handleSubmit(onSubmit)}>
    <fieldset disabled={busy}>
      <legend>{address ? 'Editar dirección' : 'Nueva dirección'}</legend>
      <div className="form-grid">
        <label>Nombre de quien recibe
          <input {...register('recipientName')} autoComplete="shipping name" maxLength={200}
            aria-invalid={!!errors.recipientName} aria-describedby={errors.recipientName ? 'address-recipient-error' : undefined} />
          {errors.recipientName && <span className="error" id="address-recipient-error">{errors.recipientName.message}</span>}
        </label>
        <CheckoutAddress form={form} />
        <label>Teléfono (opcional)
          <input {...register('phone')} type="tel" autoComplete="shipping tel" maxLength={20}
            aria-invalid={!!errors.phone} aria-describedby={errors.phone ? 'address-phone-error' : undefined} />
          {errors.phone && <span className="error" id="address-phone-error">{errors.phone.message}</span>}
        </label>
        <label>Etiqueta (opcional)
          <input {...register('label')} maxLength={60} placeholder="Casa u oficina" />
        </label>
      </div>
      {offerDefault && <label className="checkbox-label">
        <input type="checkbox" {...register('makeDefault')} /> Usar como predeterminada
      </label>}
      <div className="account-address-actions">
        <button className="button">{busy ? 'Guardando…' : 'Guardar dirección'}</button>
        <button type="button" className="text-link" onClick={onCancel}>Cancelar</button>
      </div>
    </fieldset>
  </form>;
}

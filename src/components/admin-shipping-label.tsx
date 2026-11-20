'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { api } from '@/lib/api/client';
import { orderSchema } from '@/lib/api/schemas';
import { shippingLabelsSchema, shippingLabelSchema, shippingQuoteSchema, type ShippingLabels, type ShippingRate } from '@/lib/api/after-sales';
import { money } from '@/lib/data';
import { LIVE } from '@/lib/business';
import type { Order } from '@/types';
import type { RunOrderAction } from './admin-order-detail';

type Parcel = { weight: number; length: number; width: number; height: number; content: string };

export function AdminShippingLabel({ order, path, run, busy, onOrder }: {
  order: Order; path: string; run: RunOrderAction; busy: boolean; onOrder: (order: Order) => void;
}) {
  const endpoint = `${path}/shipping-label`;
  const [view, setView] = useState<ShippingLabels | null>(null);
  const [failed, setFailed] = useState(false);
  const [reload, setReload] = useState(0);
  const [quote, setQuote] = useState<{ parcel: Parcel; rates: ShippingRate[] } | null>(null);
  const [selected, setSelected] = useState(0);

  useEffect(() => {
    let active = true;
    api.get(endpoint, shippingLabelsSchema).then(
      data => { if (active) { setView(data); setFailed(false); } },
      () => { if (active) setFailed(true); },
    );
    return () => { active = false; };
  }, [endpoint, reload]);

  function quoteParcel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const parcel = { weight: Number(values.get('weight')), length: Number(values.get('length')),
      width: Number(values.get('width')), height: Number(values.get('height')), content: String(values.get('content')) };
    void run(async () => {
      const rates = await api.post(`${endpoint}/quotes`, shippingQuoteSchema, parcel);
      setQuote({ parcel, rates });
      setSelected(0);
      return rates.length ? 'Cotización actualizada.' : 'No hay servicios disponibles para este paquete.';
    });
  }

  function buyLabel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!quote?.rates[selected]) return;
    const { carrier, service, amount } = quote.rates[selected];
    void run(async () => {
      const label = await api.post(endpoint, shippingLabelSchema, { parcel: quote.parcel, carrier, service, amount });
      setView(previous => previous && { ...previous, label });
      setQuote(null);
      return label.status === 'READY' ? 'Guía lista para imprimir.' : 'Consulta el estado del intento antes de volver a generar una guía.';
    });
  }

  function clearAttempt(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const reason = new FormData(event.currentTarget).get('reason');
    void run(async () => {
      const label = await api.post(`${endpoint}/clear`, shippingLabelSchema, { reason });
      setView(previous => previous && { ...previous, label });
      return 'Revisión registrada. Puedes volver a cotizar.';
    });
  }

  function recoverLabel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget));
    void run(async () => {
      const label = await api.post(`${endpoint}/recover`, shippingLabelSchema, values);
      setView(previous => previous && { ...previous, label });
      return 'Guía conciliada y lista para imprimir.';
    });
  }

  if (failed) return <div>
    <p role="alert" className="error">No pudimos consultar las guías.</p>
    <button className="text-link" onClick={() => { setFailed(false); setReload(n => n + 1); }}>Reintentar</button>
  </div>;
  if (!view) return <p role="status">Consultando guía…</p>;
  const label = view.label;
  const notShipped = order.status === 'PAID' && ['UNFULFILLED', 'PREPARING'].includes(order.fulfillmentStatus ?? 'UNFULFILLED');
  if (label?.status === 'READY') return <div className="after-sales-stack">
    <p>{label.live ? 'Guía Envia' : 'Guía Envia de prueba'} · <strong>{label.trackingNumber}</strong></p>
    <div className="after-sales-actions">
      {label.labelUrl && <a className="button" href={label.labelUrl} rel="noopener noreferrer" target="_blank">Imprimir guía</a>}
      {notShipped && (!LIVE || label.live) && <button className="text-link" disabled={busy}
        onClick={() => void run(async () => {
          const next = await api.post(`${path}/fulfillment`, orderSchema, {
            status: 'SHIPPED', carrier: label.carrier, trackingNumber: label.trackingNumber,
          });
          onOrder(next);
          return 'Pedido marcado como enviado.';
        })}>Marcar enviado</button>}
    </div>
  </div>;
  if (label?.status === 'PROCESSING') return <div className="after-sales-stack">
    <p role="status">Envia está procesando la guía.</p>
    <button className="text-link" onClick={() => setReload(n => n + 1)}>Consultar de nuevo</button>
  </div>;
  if (label?.status === 'REVIEW') return <div className="after-sales-stack">
    <p>Consulta el pedido en el panel de Envia para ver si se generó la guía.</p>
    <details>
      <summary>Envia generó la guía</summary>
      <form className="after-sales-form" onSubmit={recoverLabel}>
        <label>ID del envío<input name="shipmentId" required maxLength={100}/></label>
        <label>Número de guía<input name="trackingNumber" required maxLength={120}/></label>
        <label>Enlace del PDF<input type="url" name="labelUrl" required maxLength={2048}/></label>
        <button className="button" disabled={busy}>Guardar guía existente</button>
      </form>
    </details>
    <details>
      <summary>Envia no confirmó una guía</summary>
      <form className="after-sales-form" onSubmit={clearAttempt}>
        <label className="checkbox-label"><input type="checkbox" required /> Confirmé que no hay una guía activa ni un cargo.</label>
        <label>Resultado de la revisión<textarea name="reason" rows={2} maxLength={500} required /></label>
        <button className="button" disabled={busy}>Habilitar otra cotización</button>
      </form>
    </details>
  </div>;
  if (!notShipped) return null;
  if (!view.enabled) return <p className="muted">La generación de guías con Envia está pendiente de configurar. Puedes registrar una guía en la lista de pedidos.</p>;

  return <div className="after-sales-stack">
    <h3>Generar guía con Envia</h3>
    {!view.live && <p className="muted">Modo de pruebas. Las guías de este entorno no sirven para envíos reales.</p>}
    <form className="after-sales-form" onSubmit={quoteParcel} onChange={() => setQuote(null)}>
      <div className="form-grid">
        <label>Peso (kg)<input name="weight" type="number" min="0.05" max={70} step="0.01" required disabled={busy} /></label>
        <label>Largo (cm)<input name="length" type="number" min={1} max={150} required disabled={busy} /></label>
        <label>Ancho (cm)<input name="width" type="number" min={1} max={150} required disabled={busy} /></label>
        <label>Alto (cm)<input name="height" type="number" min={1} max={150} required disabled={busy} /></label>
        <label className="span-2">Contenido del paquete<input name="content" required maxLength={100} disabled={busy} /></label>
      </div>
      <button className="button" disabled={busy}>Cotizar un paquete</button>
    </form>
    {!!quote?.rates.length && <form className="after-sales-form" onSubmit={buyLabel}>
      <fieldset className="shipping-rates"><legend>Selecciona un servicio</legend>
        {quote.rates.map((rate, i) => <label key={`${rate.carrier}-${rate.service}-${i}`} className="checkbox-label">
          <input type="radio" name="rate" checked={i === selected} disabled={busy} onChange={() => setSelected(i)} />
          {rate.description} · {money(rate.amount)} MXN
        </label>)}
      </fieldset>
      {view.live && <p>Al confirmar, Envia cargará la guía a tu cuenta.</p>}
      <button className="button" disabled={busy}>{view.live ? 'Comprar y generar guía' : 'Generar guía de prueba'}</button>
    </form>}
  </div>;
}

'use client';

import { useEffect, useState } from 'react';
import { ArrowUpRight } from 'lucide-react';
import { z } from 'zod';
import { api } from '@/lib/api/client';
import { ApiError } from '@/lib/api/http';
import { FULFILLMENT_STATUS, ORDER_STATUS, formatDate } from '@/lib/data';
import { BUSINESS } from '@/lib/business';

const trackingSchema = z.object({
  orderNumber: z.string(), status: z.string(), fulfillmentStatus: z.string(),
  carrier: z.string().nullish(), trackingNumber: z.string().nullish(),
  trackingUrl: z.url({ protocol: /^https$/ }).nullish(), shippedAt: z.string().nullish(),
});
type Tracking = z.infer<typeof trackingSchema>;

export function GuestTracking({ id }: { id: string }) {
  const [result, setResult] = useState<Tracking | 'invalid' | 'error' | null>(null);
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    const token = window.location.hash.slice(1);
    if (!/^[0-9]{1,12}\.[a-f0-9]{64}$/.test(token)) {
      setResult('invalid');
      return;
    }
    let active = true;
    api.post(`/orders/${encodeURIComponent(id)}/tracking`, trackingSchema, { token }).then(
      view => { if (active) setResult(view); },
      error => { if (active) setResult(error instanceof ApiError && [400, 404].includes(error.status) ? 'invalid' : 'error'); },
    );
    return () => { active = false; };
  }, [id, retry]);

  return <div className="page order-page">
    <div className="page-intro"><h1>Sigue tu <i>pedido.</i></h1></div>
    {result === null && <p role="status">Consultando tu pedido…</p>}
    {result === 'error' && <div className="address-block">
      <p role="alert">No pudimos consultar el pedido.</p>
      <button className="button" onClick={() => { setResult(null); setRetry(n => n + 1); }}>Reintentar</button>
    </div>}
    {result === 'invalid' && <div className="address-block">
      <p role="alert">Este enlace venció o está incompleto.</p>
      <a href={`mailto:${BUSINESS.email}`}>Contactar a Olvenio</a>
    </div>}
    {result && typeof result === 'object' && <section className="address-block" aria-label="Estado del envío">
      <h2>{result.orderNumber}</h2>
      <p>{['PAID', 'PARTIALLY_REFUNDED'].includes(result.status)
        ? FULFILLMENT_STATUS[result.fulfillmentStatus] : ORDER_STATUS[result.status]}</p>
      {result.shippedAt && <p>Enviado el {formatDate(result.shippedAt)}</p>}
      {result.carrier && <p>Paquetería: {result.carrier}</p>}
      {result.trackingNumber && <p>Guía: <strong>{result.trackingNumber}</strong></p>}
      {result.trackingUrl && <a href={result.trackingUrl} className="button" target="_blank" rel="noopener noreferrer">
        Rastrear envío <ArrowUpRight size={16}/>
      </a>}
    </section>}
  </div>;
}

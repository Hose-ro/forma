'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { ArrowUpRight } from 'lucide-react';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { afterSalesSchema, invoiceSchema, returnSchema, RETURN_STATUS, type AfterSales } from '@/lib/api/after-sales';
import { BUSINESS } from '@/lib/business';

export function CustomerAfterSales({ id }: { id: string }) {
  const [view, setView] = useState<AfterSales | null>(null);
  const [loadingError, setLoadingError] = useState(false);
  const [retry, setRetry] = useState(0);
  const [open, setOpen] = useState<'return' | 'invoice' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const orderPath = `/orders/${encodeURIComponent(id)}`;
  const invoicePath = `/me${orderPath}/invoice`;

  useEffect(() => {
    let active = true;
    api.get(`${orderPath}/after-sales`, afterSalesSchema).then(
      result => { if (active) setView(result); },
      e => { if (active && (!(e instanceof ApiError) || e.status !== 404)) setLoadingError(true); },
    );
    return () => { active = false; };
  }, [orderPath, retry]);

  async function send(event: FormEvent<HTMLFormElement>, kind: 'return' | 'invoice') {
    event.preventDefault();
    if (busy) return;
    setError('');
    const values = Object.fromEntries(new FormData(event.currentTarget));
    setBusy(true);
    try {
      if (kind === 'return') {
        const request = await api.post(`${orderPath}/returns`, returnSchema, values);
        setView(previous => previous && { ...previous, returnRequest: request });
      } else {
        const invoice = await api.post(invoicePath, invoiceSchema, {
          ...values, taxId: String(values.taxId).trim().toUpperCase(), cfdiUse: String(values.cfdiUse).trim().toUpperCase(),
        });
        setView(previous => previous && { ...previous, invoice });
      }
      setOpen(null);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 ? e.detail : errorMessage(e, 'No pudimos guardar tu solicitud.'));
    } finally {
      setBusy(false);
    }
  }

  if (loadingError) return <div className="address-block">
    <p role="alert">No pudimos consultar las solicitudes de este pedido.</p>
    <button className="text-link" onClick={() => { setLoadingError(false); setRetry(n => n + 1); }}>Reintentar</button>
  </div>;
  if (!view) return null;
  const request = view.returnRequest;
  const invoice = view.invoice;

  return <div className="after-sales-stack">
    {view.trackingUrl && <div><a href={view.trackingUrl} className="text-link" target="_blank" rel="noopener noreferrer">
      Rastrear envío <ArrowUpRight size={16}/>
    </a></div>}
    {(request || view.canReturn) && <section className="address-block">
      <h3>Cambios y devoluciones</h3>
      {request ? <>
        <p><strong>{request.kind === 'EXCHANGE' ? 'Cambio' : 'Devolución'} · {RETURN_STATUS[request.status]}</strong></p>
        <p>{request.items}</p>
        {request.response && <p>{request.response}</p>}
      </> : <>
        <button className="text-link" aria-expanded={open === 'return'} disabled={busy}
          onClick={() => { setError(''); setOpen(open === 'return' ? null : 'return'); }}>
          {open === 'return' ? 'Cerrar solicitud' : 'Solicitar cambio o devolución'}
        </button>
        {open === 'return' && <form className="after-sales-form" onSubmit={e => void send(e, 'return')}>
          <label>Tipo de solicitud<select name="kind"><option value="RETURN">Devolución</option><option value="EXCHANGE">Cambio</option></select></label>
          <label>Productos y cantidades<textarea name="items" rows={2} maxLength={500} required placeholder="Ej. 1 lámpara, color arena" /></label>
          <label>Motivo<textarea name="reason" rows={3} maxLength={500} required /></label>
          {error && <p className="error" role="alert">{error}</p>}
          <button className="button" disabled={busy}>{busy ? 'Enviando…' : 'Enviar solicitud'}</button>
        </form>}
      </>}
    </section>}
    {(invoice || view.canInvoice) && <section className="address-block">
      <h3>Factura</h3>
      {invoice ? <>
        <p role="status">{invoice.issuedAt ? 'Tu factura está disponible.' : 'Solicitud recibida. Aquí podrás descargar tu factura cuando esté lista.'}</p>
        {invoice.issuedAt && <div className="after-sales-actions">
          <a className="text-link" href={`/api/v1${invoicePath}/pdf`}>Descargar PDF</a>
          <a className="text-link" href={`/api/v1${invoicePath}/xml`}>Descargar XML</a>
        </div>}
      </> : <>
        <button className="text-link" aria-expanded={open === 'invoice'} disabled={busy}
          onClick={() => { setError(''); setOpen(open === 'invoice' ? null : 'invoice'); }}>
          {open === 'invoice' ? 'Cerrar solicitud' : 'Solicitar factura'}
        </button>
        {open === 'invoice' && <form className="after-sales-form" onSubmit={e => void send(e, 'invoice')}>
          <p className="muted">Captura los datos tal como aparecen en tu constancia fiscal.</p>
          <div className="form-grid">
            <label>RFC<input name="taxId" maxLength={13} minLength={12} pattern="[A-Za-zÑñ&amp;]{3,4}[0-9]{6}[A-Za-z0-9]{3}" required /></label>
            <label>Código postal fiscal<input name="postalCode" inputMode="numeric" pattern="[0-9]{5}" maxLength={5} required /></label>
            <label className="span-2">Nombre o razón social<input name="legalName" maxLength={254} required /></label>
            <label>Clave del régimen fiscal<input name="taxRegime" inputMode="numeric" pattern="[0-9]{3}" maxLength={3} required placeholder="Ej. 612" /></label>
            <label>Clave de uso del CFDI<input name="cfdiUse" pattern="[A-Za-z][0-9]{2}" maxLength={3} required placeholder="Ej. G03" /></label>
          </div>
          {error && <p className="error" role="alert">{error}</p>}
          <button className="button" disabled={busy}>{busy ? 'Enviando…' : 'Solicitar factura'}</button>
        </form>}
      </>}
    </section>}
    {!view.canInvoice && !invoice && <p className="muted">Para solicitar factura, escribe a <a href={`mailto:${BUSINESS.email}`}>{BUSINESS.email}</a>.</p>}
  </div>;
}

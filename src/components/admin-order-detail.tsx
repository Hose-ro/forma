'use client';

import Link from 'next/link';
import { useEffect, useState, type FormEvent } from 'react';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { orderSchema } from '@/lib/api/schemas';
import { afterSalesSchema, invoiceSchema, returnSchema, RETURN_STATUS, type AfterSales } from '@/lib/api/after-sales';
import { ORDER_STATUS, FULFILLMENT_STATUS, money } from '@/lib/data';
import type { Order } from '@/types';
import { AdminShippingLabel } from './admin-shipping-label';

export type RunOrderAction = (action: () => Promise<string>) => Promise<void>;

function asBase64(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('No pudimos leer el archivo.'));
    reader.onload = () => resolve(String(reader.result).split(',')[1]);
    reader.readAsDataURL(file);
  });
}

export function AdminOrderDetail({ id }: { id: string }) {
  const path = `/admin/orders/${encodeURIComponent(id)}`;
  const [order, setOrder] = useState<Order | null>(null);
  const [view, setView] = useState<AfterSales | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    Promise.all([api.get(path, orderSchema), api.get(`${path}/after-sales`, afterSalesSchema)]).then(
      ([o, v]) => { if (active) { setOrder(o); setView(v); } },
      e => { if (active) setError(errorMessage(e, 'No pudimos abrir este pedido.')); },
    );
    return () => { active = false; };
  }, [path, reload]);

  const run: RunOrderAction = async action => {
    if (busy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try { setNotice(await action()); }
    catch (e) {
      setError(e instanceof ApiError && [400, 409].includes(e.status) && !e.fieldErrors.length
        ? e.detail : errorMessage(e, e instanceof Error && !(e instanceof ApiError) ? e.message : 'No pudimos guardar el cambio.'));
    } finally { setBusy(false); }
  };

  function reviewReturn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(event.currentTarget));
    void run(async () => {
      const request = await api.patch(`${path}/returns`, returnSchema, values);
      setView(previous => previous && { ...previous, returnRequest: request });
      return 'Respuesta guardada. El cliente puede consultarla en su pedido.';
    });
  }

  function attachInvoice(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    void run(async () => {
      const pdf = values.get('pdf');
      const xml = values.get('xml');
      if (!(pdf instanceof File) || !(xml instanceof File) || !pdf.size || !xml.size
          || Math.max(pdf.size, xml.size) > 1024 * 1024) throw new Error('Adjunta el PDF y el XML. Cada archivo puede pesar hasta 1 MB.');
      const [pdfData, xmlData] = await Promise.all([asBase64(pdf), asBase64(xml)]);
      const invoice = await api.post(`${path}/invoice`, invoiceSchema, { pdf: pdfData, xml: xmlData });
      setView(previous => previous && { ...previous, invoice });
      return 'Factura disponible en la cuenta del cliente.';
    });
  }

  const request = view?.returnRequest;
  const invoice = view?.invoice;
  const address = order?.shippingAddress;

  return <main id="main" className="page admin-order-page after-sales-stack">
    <Link className="text-link" href="/admin?view=orders">Volver a pedidos</Link>
    <div className="admin-title"><h1>{order?.orderNumber ?? 'Pedido'}</h1></div>
    {notice && <p className="success-message" role="status">{notice}</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {!order && (error
      ? <button className="button" onClick={() => { setError(''); setReload(n => n + 1); }}>Reintentar</button>
      : <p role="status">Cargando pedido…</p>)}
    {order && <>
      <section className="admin-panel">
        <h2>Pedido y dirección</h2>
        <p><strong>{ORDER_STATUS[order.status]}</strong> · {money(order.total)} {order.currency}</p>
        {address && <address>
          {address.recipientName}<br/>
          {address.line1}<br/>
          {address.line2 && <>{address.line2}<br/></>}
          {address.neighborhood && <>{address.neighborhood}<br/></>}
          {address.city}, {address.state}, C.P. {address.postalCode}<br/>
          {address.phone ?? order.customer.phone}
        </address>}
        <p>{order.customer.email}</p>
        <ul>{order.items.map(item => <li key={item.sku}>{item.quantity} × {item.productName} · {item.variantName}</li>)}</ul>
        {order.status === 'PAYMENT_REVIEW' && <Link className="text-link" href={`/admin?view=payments&order=${id}`}>Revisar pago</Link>}
      </section>
      <section className="admin-panel after-sales-stack">
        <h2>Envío</h2>
        <p>{FULFILLMENT_STATUS[order.fulfillmentStatus ?? 'UNFULFILLED']}</p>
        {order.trackingNumber && <p>{order.carrier} · {order.trackingNumber}</p>}
        <AdminShippingLabel order={order} path={path} run={run} busy={busy} onOrder={setOrder} />
      </section>
      {request && <section className="admin-panel after-sales-stack">
        <h2>{request.kind === 'EXCHANGE' ? 'Solicitud de cambio' : 'Solicitud de devolución'}</h2>
        <p><strong>{RETURN_STATUS[request.status]}</strong></p>
        <p>{request.items}</p>
        <p>{request.reason}</p>
        {request.response && <p className="muted">Última respuesta: {request.response}</p>}
        {['REQUESTED', 'APPROVED'].includes(request.status) && <form className="after-sales-form" onSubmit={reviewReturn}>
          <label>Estado<select name="status">
            {request.status === 'REQUESTED'
              ? <><option value="APPROVED">Aprobar</option><option value="REJECTED">No aprobar</option></>
              : <option value="COMPLETED">Marcar como concluida</option>}
          </select></label>
          <label>Mensaje para el cliente<textarea name="response" rows={3} required maxLength={500} /></label>
          <button className="button" disabled={busy}>Guardar respuesta</button>
        </form>}
      </section>}
      {invoice && <section className="admin-panel after-sales-stack">
        <h2>Solicitud de factura</h2>
        <dl className="after-sales-facts">
          <dt>RFC</dt><dd>{invoice.taxId}</dd>
          <dt>Nombre o razón social</dt><dd>{invoice.legalName}</dd>
          <dt>Código postal fiscal</dt><dd>{invoice.postalCode}</dd>
          <dt>Régimen fiscal</dt><dd>{invoice.taxRegime}</dd>
          <dt>Uso del CFDI</dt><dd>{invoice.cfdiUse}</dd>
        </dl>
        {invoice.issuedAt ? <p>Factura entregada · {invoice.fiscalId}</p> : <form className="after-sales-form" onSubmit={attachInvoice}>
          <p className="muted">Emite el CFDI con tu facturador. Adjunta los dos archivos del mismo comprobante.</p>
          <label>PDF<input type="file" accept=".pdf,application/pdf" name="pdf" required /></label>
          <label>XML timbrado<input type="file" accept=".xml,application/xml,text/xml" name="xml" required /></label>
          <button className="button" disabled={busy}>Entregar factura</button>
        </form>}
      </section>}
    </>}
  </main>;
}

'use client';

import Image from 'next/image';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { ArrowUpRight, Check } from 'lucide-react';
import { api } from '@/lib/api/client';
import { ApiError } from '@/lib/api/http';
import { orderSchema } from '@/lib/api/schemas';
import { LIVE } from '@/lib/business';
import { FALLBACK_IMAGE, FULFILLMENT_STATUS, ORDER_STATUS, formatDate, money } from '@/lib/data';
import type { Order } from '@/types';
import { CustomerAfterSales } from './customer-after-sales';
import { OrderTracking } from './order-tracking';
import styles from './order-tracking.module.css';
import { PaymentAction, VerifiedPayment } from './payment';
import { Empty } from './ui';

// The API only returns the order to its owner (session), to the guest browser that created it or to an admin.
export function OrderDetail({ id, confirmation = false, showTracking = false }: {
  id: string;
  confirmation?: boolean;
  showTracking?: boolean;
}) {
  const [order, setOrder] = useState<Order | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'missing' | 'error'>('loading');
  const [attempt, setAttempt] = useState(0);
  const refreshPayment = useCallback(() => setAttempt(n => n + 1), []);

  useEffect(() => {
    let active = true;
    api.get(`/orders/${encodeURIComponent(id)}`, orderSchema).then(result => {
      if (!active) return;
      setOrder(result);
      setState('ready');
    }, error => {
      if (!active) return;
      setState(error instanceof ApiError && (error.status === 404 || error.status === 400) ? 'missing' : 'error');
    });
    return () => { active = false; };
  }, [id, attempt]);

  if (state === 'loading') return <div className="page skeleton" />;
  if (state === 'error') return <div className="page">
    <Empty title="No pudimos cargar tu pedido.">
      <p>Inténtalo de nuevo en unos segundos.</p>
      <button className="button" onClick={() => { setState('loading'); refreshPayment(); }}>Reintentar</button>
    </Empty>
  </div>;
  if (!order) return <div className="page">
    <Empty title="Pedido no encontrado.">
      <p>Inicia sesión con la cuenta que hizo el pedido, o ábrelo desde el navegador donde lo creaste.</p>
      <Link href="/account" className="button">Ir a mi cuenta</Link>
    </Empty>
  </div>;

  const address = order.shippingAddress;
  const paid = order.status === 'PAID';
  const tracking = showTracking && paid;
  const fulfillment = FULFILLMENT_STATUS[order.fulfillmentStatus || 'UNFULFILLED'];
  const summary = <>
    {order.items.map(item => <div className="order-line" key={item.sku}>
      <Image src={item.imageUrl || FALLBACK_IMAGE} alt={item.productName} width={80} height={100} />
      <div><strong>{item.productName}</strong><p>{item.variantName} · {item.quantity} ud.</p></div>
      <span>{money(item.lineTotal)}</span>
    </div>)}
    <div className="total-row"><span>Subtotal</span><span>{money(order.subtotal)}</span></div>
    <div className="total-row"><span>Envío</span><span>{order.shippingTotal === 0 ? 'Gratis' : money(order.shippingTotal)}</span></div>
    <div className="total-row"><span>Total</span><strong>{money(order.total)} MXN</strong></div>
  </>;

  return <div className="page order-page">
    <div className="page-intro">
      {confirmation && <Check className="confirmation-mark" size={32} />}
      <p className="eyebrow">{LIVE ? 'Pedido' : 'Pedido · Pagos de prueba'}</p>
      <h1>{confirmation ? <>Todo en su <i>lugar.</i></> : <>Tu <i>pedido.</i></>}</h1>
      <p>{confirmation
        ? (LIVE ? 'Tu pedido quedó registrado. Revisa el resumen y continúa al pago.' : 'Tu pedido quedó registrado. Revisa el resumen y continúa al pago de prueba.')
        : 'Consulta los detalles de tu selección.'}</p>
      {!LIVE && <div className="demo-notice">Modo de pruebas. El estado financiero se confirma con el procesador de pagos. Este pedido no será enviado.</div>}
    </div>
    <div className="order-meta">
      <span>{order.orderNumber || 'Cotización'}</span>
      <span>{formatDate(order.placedAt || order.createdAt)}</span>
      <span>{ORDER_STATUS[order.status]}</span>
      {paid && !tracking && <span>{fulfillment}</span>}
    </div>
    {tracking ? <div className={styles.layout}>
      <OrderTracking order={order} />
      <section className={styles.summary} aria-labelledby="order-summary-title">
        <h2 id="order-summary-title" className={styles.heading}>Resumen del pedido</h2>
        {summary}
      </section>
    </div> : summary}
    <div className="address-block">
      <h3>Datos del pedido</h3>
      <p>{order.customer.firstName} {order.customer.lastName}<br />
        {address && <>
          {address.line1}<br />
          {address.line2 && <>{address.line2}<br /></>}
          {address.neighborhood && <>{address.neighborhood}<br /></>}
          {address.city}, {address.state}, {address.postalCode}<br />
        </>}
        {order.customer.email}{order.customer.phone && <><br />{order.customer.phone}</>}
      </p>
    </div>
    {paid && !tracking && <div className="address-block">
      <h3>Envío</h3>
      <p>{fulfillment}{order.shippedAt && <> · {formatDate(order.shippedAt)}</>}
        {order.carrier && <><br />Paquetería: {order.carrier}</>}
        {order.trackingNumber && <><br />Número de guía: {order.trackingNumber}</>}
      </p>
    </div>}
    <VerifiedPayment orderId={order.id} knownStatus={order.paymentStatus} onVerified={refreshPayment} />
    {order.status === 'PENDING_PAYMENT' && <PaymentAction orderId={order.id} />}
    {!confirmation && ['PAID', 'PARTIALLY_REFUNDED', 'REFUNDED'].includes(order.status) && <CustomerAfterSales key={id} id={id} />}
    <Link href="/shop" className="button">Seguir explorando <ArrowUpRight size={18} /></Link>
  </div>;
}

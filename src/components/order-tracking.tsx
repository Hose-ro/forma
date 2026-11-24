import { Check } from 'lucide-react';
import type { Order } from '@/types';
import styles from './order-tracking.module.css';

const eventDate = new Intl.DateTimeFormat('es-MX', {
  day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit',
});

function formatEventDate(date?: string | null) {
  const timestamp = date ? Date.parse(date) : NaN;
  return Number.isFinite(timestamp) ? eventDate.format(timestamp) : null;
}

export function OrderTracking({ order }: { order: Order }) {
  const delivered = order.fulfillmentStatus === 'DELIVERED';
  const shipped = delivered || order.fulfillmentStatus === 'SHIPPED';
  const preparing = order.fulfillmentStatus === 'PREPARING';
  const steps = [
    { title: 'Pedido recibido', state: 'complete', label: 'Completado', date: order.placedAt || order.createdAt,
      dateLabel: formatEventDate(order.placedAt || order.createdAt) },
    { title: 'Preparación', state: shipped ? 'complete' : preparing ? 'current' : 'pending',
      label: shipped ? 'Completado' : preparing ? 'En curso' : 'Por preparar' },
    { title: 'Envío', state: delivered ? 'complete' : shipped ? 'current' : 'pending',
      label: delivered ? 'Entregado' : shipped ? 'Enviado' : 'Pendiente', date: order.shippedAt,
      dateLabel: formatEventDate(order.shippedAt) },
  ];

  return <section className={styles.card} aria-labelledby="order-tracking-title">
    <h2 id="order-tracking-title" className={styles.heading}>Seguimiento del pedido</h2>
    <ol className={styles.steps} role="list">
      {steps.map(({ title, state, label, date, dateLabel }, index) => <li
        key={title}
        className={styles.step}
        data-state={state}
        aria-current={state === 'current' ? 'step' : undefined}
      >
        <span className={styles.marker} aria-hidden="true">
          {state === 'complete' ? <Check size={17} /> : index + 1}
        </span>
        <div>
          <p className={styles.stepTitle}>{title}</p>
          <span className={styles.status}>{label}</span>
          {dateLabel && <time className={styles.date} dateTime={date || undefined}>
            {index === 2 && 'Enviado el '}{dateLabel}
          </time>}
          {index === 2 && (order.carrier || order.trackingNumber) && <p className={styles.shippingDetails}>
            {order.carrier && <span>Paquetería: {order.carrier}</span>}
            {order.trackingNumber && <span>Número de guía: {order.trackingNumber}</span>}
          </p>}
        </div>
      </li>)}
    </ol>
  </section>;
}

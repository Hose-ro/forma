'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { operationalAlertsSchema, type OperationalAlert } from '@/lib/api/after-sales';
import { formatDate } from '@/lib/data';

const LABELS: Record<OperationalAlert['kind'], string> = {
  PAYMENT: 'Pagos en revisión', ORDER_EMAIL: 'Correos de pedido fallidos', ACCOUNT_EMAIL: 'Correos de cuenta fallidos',
  PREPARATION: 'Pedidos por preparar', RETURN: 'Cambios y devoluciones activos',
  INVOICE: 'Facturas por entregar', LABEL: 'Guías por verificar',
};
const DETAIL: Record<string, string> = {
  CONFIRMATION: 'Confirmación de pedido', SHIPMENT: 'Aviso de envío', PASSWORD_RESET: 'Recuperación de contraseña',
  EMAIL_VERIFICATION: 'Verificación de correo', RETURN: 'Devolución', EXCHANGE: 'Cambio',
};

export function AdminAlerts() {
  const [alerts, setAlerts] = useState<OperationalAlert[] | null>(null);
  const [error, setError] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [pending, setPending] = useState('');
  const [message, setMessage] = useState<{ text: string; error?: boolean } | null>(null);

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const result = await api.get('/admin/alerts', operationalAlertsSchema);
        if (active) { setAlerts(result); setError(false); }
      } catch {
        if (active) setError(true);
      }
    }
    void load();
    const timer = window.setInterval(() => { if (!document.hidden) void load(); }, 60_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [refresh]);

  async function retryEmail(item: OperationalAlert) {
    if (!item.orderId || !item.detail || pending) return;
    setPending(`${item.orderId}/${item.detail}`);
    setMessage(null);
    try {
      await api.post(`/admin/orders/${item.orderId}/email-retry/${item.detail}`, null);
      setMessage({ text: 'Correo puesto en cola de nuevo.' });
      setRefresh(n => n + 1);
    } catch (e) {
      setMessage({ text: e instanceof ApiError && e.status === 409 ? e.detail : errorMessage(e, 'No pudimos reintentar el correo.'), error: true });
    } finally {
      setPending('');
    }
  }

  const groups = new Map<OperationalAlert['kind'], OperationalAlert[]>();
  for (const item of alerts ?? []) {
    const entries = groups.get(item.kind);
    if (entries) entries.push(item);
    else groups.set(item.kind, [item]);
  }
  return <section className="admin-panel after-sales-stack">
    <div className="after-sales-actions">
      <h2>Alertas operativas</h2>
      <button className="text-link" onClick={() => setRefresh(n => n + 1)}>Actualizar</button>
    </div>
    {error && <p className="error" role="alert">{alerts ? 'El último intento de actualizar falló.' : 'No pudimos cargar las alertas.'}</p>}
    {message && <p role={message.error ? 'alert' : 'status'} className={message.error ? 'error' : 'success-message'}>{message.text}</p>}
    {!alerts && !error && <p role="status">Consultando alertas…</p>}
    {alerts?.length === 0 && !error && <p className="muted">No hay alertas pendientes.</p>}
    {[...groups].map(([kind, rows]) => <details key={kind} className="alert-group">
      <summary>{LABELS[kind]} <strong>{rows[0].total}</strong></summary>
      <ul>
        {rows.map((row, index) => <li key={`${row.orderId ?? kind}-${index}`}>
          {row.orderId ? <Link href={kind === 'PAYMENT' ? `/admin?view=payments&order=${row.orderId}` : `/admin/orders/${row.orderId}`}>
            {row.reference}
          </Link> : <span>Correo de cuenta</span>}
          {row.detail && <span>{DETAIL[row.detail] ?? row.detail}</span>}
          <time dateTime={row.since}>{formatDate(row.since)}</time>
          {kind === 'ORDER_EMAIL' && <button type="button" className="text-link" disabled={!!pending}
            onClick={() => void retryEmail(row)}>{pending === `${row.orderId}/${row.detail}` ? 'Reintentando…' : 'Reintentar correo'}</button>}
        </li>)}
      </ul>
      {rows[0].total > rows.length && <p className="muted">Se muestran los {rows.length} casos más antiguos.</p>}
    </details>)}
  </section>;
}

 'use client';
import { LIVE } from '@/lib/business';
import {useEffect,useState} from 'react';
import type {z} from 'zod';
import Link from 'next/link';
import {api} from '@/lib/api/client';
import {errorMessage} from '@/lib/api/http';
import {adminPaymentsSchema,adminPaymentSchema} from '@/lib/api/schemas';
import {formatDate,money} from '@/lib/data';
export function AdminPayments(){
  const [rows,setRows]=useState<z.infer<typeof adminPaymentsSchema>>([]);const [page,setPage]=useState(0);
  const [detail,setDetail]=useState<z.infer<typeof adminPaymentSchema>|null>(null);const [error,setError]=useState('');
  useEffect(()=>{let active=true;setError('');api.get(`/admin/payments?page=${page}`,adminPaymentsSchema).then(r=>{if(active)setRows(r)},e=>{if(active)setError(errorMessage(e,'No pudimos consultar los pagos.'))});return()=>{active=false}},[page]);
  async function open(orderId:string){try{setDetail(await api.get(`/admin/payments/orders/${orderId}`,adminPaymentSchema));setError('')}catch(e){setError(errorMessage(e,'No pudimos consultar el detalle.'))}}
  return <section className="admin-panel"><h2>Pagos · Mercado Pago{LIVE?'':' de prueba'}</h2>{error&&<p role="alert" className="error">{error}</p>}<div className="table-scroll"><table><thead><tr><th>Pedido</th><th>Importe</th><th>Proveedor</th><th>Estado</th><th>Fecha</th><th>Identificador</th></tr></thead><tbody>{rows.map(p=><tr key={p.id}><td><button className="text-link" onClick={()=>void open(p.order_id)}>{p.order_number}</button></td><td>{money(p.amount)} {p.currency}</td><td>{p.provider}</td><td>{p.status}</td><td>{formatDate(p.created_at)}</td><td>{p.provider_payment_id||'Pendiente'}</td></tr>)}</tbody></table></div>{!rows.length&&<p>No hay pagos en esta página.</p>}<button disabled={page===0} onClick={()=>setPage(page-1)}>Anterior</button> <button disabled={rows.length<50} onClick={()=>setPage(page+1)}>Siguiente</button>{detail&&<div className="address-block"><h3>Detalle de pago</h3><p>{detail.payment.id} · {detail.payment.status}</p><p>Preferencia: {detail.payment.providerPreferenceId||'Sin confirmar'}</p>{detail.payment.failureReason&&<p>{detail.payment.failureReason}</p>}<Link href={`/account/orders/${detail.payment.orderId}`}>Ver pedido</Link><h3>Eventos verificados</h3>{detail.events.map((event,i)=><p key={i}>{formatDate(event.received_at)} · {event.event_type} · {event.processing_status}<br/><small>{event.payload}</small></p>)}<button onClick={()=>setDetail(null)}>Cerrar detalle</button></div>}</section>;
}

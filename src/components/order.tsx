'use client';
import Link from 'next/link';
import Image from 'next/image';
import { useCallback, useEffect, useState } from 'react';
import { Check, ArrowUpRight } from 'lucide-react';
import { api } from '@/lib/api/client';
import { ApiError } from '@/lib/api/http';
import { orderSchema } from '@/lib/api/schemas';
import { FALLBACK_IMAGE, ORDER_STATUS, formatDate, money } from '@/lib/data';
import type { Order } from '@/types';
import { Empty } from './ui';
import { PaymentAction, VerifiedPayment } from './payment';
// The API only returns the order to its owner (session), to the guest browser that created it or to an admin.
export function OrderDetail({id,confirmation=false}:{id:string;confirmation?:boolean}){const [order,setOrder]=useState<Order|null>(null);const [state,setState]=useState<'loading'|'ready'|'missing'|'error'>('loading');const [attempt,setAttempt]=useState(0);
const refreshPayment=useCallback(()=>setAttempt(n=>n+1),[]);
useEffect(()=>{let active=true;api.get(`/orders/${encodeURIComponent(id)}`,orderSchema).then(o=>{if(active){setOrder(o);setState('ready')}},e=>{if(active)setState(e instanceof ApiError&&(e.status===404||e.status===400)?'missing':'error')});return()=>{active=false}},[id,attempt]);
if(state==='loading')return <div className="page skeleton"/>;
if(state==='error')return <div className="page"><Empty title="No pudimos cargar tu pedido."><p>Inténtalo de nuevo en unos segundos.</p><button className="button" onClick={()=>{setState('loading');setAttempt(attempt+1)}}>Reintentar</button></Empty></div>;
if(!order)return <div className="page"><Empty title="Pedido no encontrado."><p>Inicia sesión con la cuenta que hizo el pedido, o ábrelo desde el navegador donde lo creaste.</p><Link href="/account" className="button">Ir a mi cuenta</Link></Empty></div>;
const a=order.shippingAddress;
return <div className="page order-page"><div className="page-intro">{confirmation&&<Check className="confirmation-mark" size={32}/>}<p className="eyebrow">Pedido · Mercado Pago de prueba</p><h1>{confirmation?<>Todo en su <i>lugar.</i></>:<>Tu <i>pedido.</i></>}</h1><p>{confirmation?'Tu pedido quedó registrado. Revisa el resumen y continúa al pago de prueba.':'Consulta los detalles de tu selección.'}</p><div className="demo-notice">Modo de pruebas. El estado financiero se confirma con Mercado Pago. Este pedido no será enviado.</div></div><div className="order-meta"><span>{order.orderNumber||'Cotización'}</span><span>{formatDate(order.placedAt||order.createdAt)}</span><span>{ORDER_STATUS[order.status]}</span></div>{order.items.map(i=><div className="order-line" key={i.sku}><Image src={i.imageUrl||FALLBACK_IMAGE} alt={i.productName} width={80} height={100}/><div><strong>{i.productName}</strong><p>{i.variantName} · {i.quantity} ud.</p></div><span>{money(i.lineTotal)}</span></div>)}<div className="total-row"><span>Subtotal</span><span>{money(order.subtotal)}</span></div><div className="total-row"><span>Envío</span><span>{money(order.shippingTotal)}</span></div><div className="total-row"><span>Total</span><strong>{money(order.total)} MXN</strong></div><div className="address-block"><h3>Datos del pedido</h3><p>{order.customer.firstName} {order.customer.lastName}<br/>{a&&<>{a.line1}<br/>{a.city}, {a.state}, {a.postalCode}<br/></>}{order.customer.email}</p></div><VerifiedPayment orderId={order.id} knownStatus={order.paymentStatus} onVerified={refreshPayment}/>{order.status==='PENDING_PAYMENT'&&<PaymentAction orderId={order.id}/>}<Link href="/shop" className="button">Seguir explorando <ArrowUpRight size={18}/></Link></div>}

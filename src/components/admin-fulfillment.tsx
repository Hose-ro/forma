'use client';
import { useState } from 'react';
import { api } from '@/lib/api/client';
import { orderSchema } from '@/lib/api/schemas';
import { FULFILLMENT_STATUS } from '@/lib/data';
import type { OrderSummary } from '@/types';

/** Logistics controls for a paid order: prepare → ship (carrier + tracking) → deliver. */
export function Fulfillment({order:o,busy,run}:{order:OrderSummary;busy:boolean;run:(action:()=>Promise<string>)=>void}){
  const [carrier,setCarrier]=useState('');const [tracking,setTracking]=useState('');
  const state=o.fulfillmentStatus||'UNFULFILLED';
  if(o.status!=='PAID')return <span className="muted">—</span>;
  const set=(status:'PREPARING'|'SHIPPED'|'DELIVERED',done:string)=>run(async()=>{await api.post(`/admin/orders/${o.id}/fulfillment`,orderSchema,{status,carrier:carrier.trim()||undefined,trackingNumber:tracking.trim()||undefined});return `Pedido ${o.orderNumber}: ${done}`});
  return <div className="fulfillment"><strong>{FULFILLMENT_STATUS[state]}</strong>
    {state==='UNFULFILLED'&&<button className="text-link" disabled={busy} onClick={()=>set('PREPARING','en preparación.')}>Preparar</button>}
    {(state==='UNFULFILLED'||state==='PREPARING')&&<><input aria-label={`Paquetería de ${o.orderNumber}`} placeholder="Paquetería" maxLength={80} value={carrier} onChange={e=>setCarrier(e.target.value)}/><input aria-label={`Número de guía de ${o.orderNumber}`} placeholder="Número de guía" maxLength={120} value={tracking} onChange={e=>setTracking(e.target.value)}/><button className="text-link" disabled={busy} onClick={()=>set('SHIPPED','marcado como enviado. Se avisará al cliente por correo.')}>Marcar enviado</button></>}
    {state==='SHIPPED'&&<button className="text-link" disabled={busy} onClick={()=>set('DELIVERED','marcado como entregado.')}>Marcar entregado</button>}
  </div>;
}

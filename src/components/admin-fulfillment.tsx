'use client';
import { useState } from 'react';
import { api } from '@/lib/api/client';
import { orderSchema } from '@/lib/api/schemas';
import { FULFILLMENT_STATUS } from '@/lib/data';
import type { OrderSummary } from '@/types';

type Run=(action:()=>Promise<string>)=>void;
const FLOW=['UNFULFILLED','PREPARING','SHIPPED','DELIVERED'];

/** Logistics controls for a paid order: prepare → ship (carrier + tracking, both required) → deliver. */
export function Fulfillment({order:o,busy,run}:{order:OrderSummary;busy:boolean;run:Run}){
  const [carrier,setCarrier]=useState('');const [tracking,setTracking]=useState('');const [fixing,setFixing]=useState(false);
  const state=o.fulfillmentStatus||'UNFULFILLED';
  if(o.status!=='PAID')return <span className="muted">—</span>;
  const set=(status:'PREPARING'|'SHIPPED'|'DELIVERED',done:string)=>run(async()=>{await api.post(`/admin/orders/${o.id}/fulfillment`,orderSchema,{status,carrier:carrier.trim()||undefined,trackingNumber:tracking.trim()||undefined});return `Pedido ${o.orderNumber}: ${done}`});
  const canShip=carrier.trim().length>0&&tracking.trim().length>0;
  return <div className="fulfillment"><strong>{FULFILLMENT_STATUS[state]}</strong>
    {state==='UNFULFILLED'&&<button className="text-link" disabled={busy} onClick={()=>set('PREPARING','en preparación.')}>Preparar</button>}
    {(state==='UNFULFILLED'||state==='PREPARING')&&<><input aria-label={`Paquetería de ${o.orderNumber}`} placeholder="Paquetería" maxLength={80} value={carrier} onChange={e=>setCarrier(e.target.value)}/><input aria-label={`Número de guía de ${o.orderNumber}`} placeholder="Número de guía" maxLength={120} value={tracking} onChange={e=>setTracking(e.target.value)}/><button className="text-link" disabled={busy||!canShip} title={canShip?undefined:'Escribe paquetería y número de guía'} onClick={()=>set('SHIPPED','marcado como enviado. Se avisará al cliente por correo.')}>Marcar enviado</button></>}
    {state==='SHIPPED'&&<button className="text-link" disabled={busy} onClick={()=>set('DELIVERED','marcado como entregado.')}>Marcar entregado</button>}
    {state!=='UNFULFILLED'&&<button className="text-link" aria-expanded={fixing} onClick={()=>setFixing(!fixing)}>{fixing?'Cerrar corrección':'Corregir'}</button>}
    {fixing&&state!=='UNFULFILLED'&&<Correction order={o} state={state} busy={busy} run={run} done={()=>setFixing(false)}/>}
  </div>;
}

/** Separate, audited path to repair a mistake: fix the tracking data or step back. It never advances an order. */
function Correction({order:o,state,busy,run,done}:{order:OrderSummary;state:string;busy:boolean;run:Run;done:()=>void}){
  const earlier=FLOW.slice(0,FLOW.indexOf(state)+1).filter(s=>s!=='DELIVERED');
  return <form className="fulfillment-fix" onSubmit={e=>{e.preventDefault();const fd=new FormData(e.currentTarget);const text=(k:string)=>String(fd.get(k)||'').trim()||undefined;run(async()=>{await api.post(`/admin/orders/${o.id}/fulfillment/correction`,orderSchema,{status:text('status'),carrier:text('carrier'),trackingNumber:text('tracking'),note:text('note')});done();return `Pedido ${o.orderNumber}: corrección registrada. Si cambió la guía de un pedido enviado, el cliente recibe el aviso de nuevo.`})}}>
    <select name="status" aria-label={`Estado corregido de ${o.orderNumber}`} defaultValue={state==='DELIVERED'?'SHIPPED':state}>{earlier.map(s=><option key={s} value={s}>{FULFILLMENT_STATUS[s]}</option>)}</select>
    <input name="carrier" aria-label={`Paquetería corregida de ${o.orderNumber}`} placeholder="Paquetería (si cambia)" maxLength={80}/>
    <input name="tracking" aria-label={`Guía corregida de ${o.orderNumber}`} placeholder="Guía (si cambia)" maxLength={120}/>
    <input name="note" aria-label={`Motivo de la corrección de ${o.orderNumber}`} placeholder="Motivo (obligatorio)" required maxLength={300}/>
    <button className="text-link" disabled={busy}>Guardar corrección</button>
  </form>;
}

 'use client';
import { LIVE } from '@/lib/business';
import Link from 'next/link';
import {useEffect,useRef,useState} from 'react';
import type {z} from 'zod';
import {api} from '@/lib/api/client';
import {ApiError,errorMessage} from '@/lib/api/http';
import {paymentSchema,preferenceSchema} from '@/lib/api/schemas';

export function PaymentAction({orderId}:{orderId:string}) {
  const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  const lock=useRef(false);const key=useRef<string|null>(null);
  async function pay(){
    if(lock.current)return;lock.current=true;setBusy(true);setError('');
    key.current??=`payment-${crypto.randomUUID()}`;
    try{
      const p=await api.post('/payments/mercadopago/preference',preferenceSchema,{orderId},{'Idempotency-Key':key.current});
      const url=new URL(p.checkoutUrl);
      if(url.protocol!=='https:'||url.hostname!=='www.mercadopago.com.mx'||url.port||url.username||url.password||url.pathname!=='/checkout/v1/redirect'||url.hash)throw new Error('La URL de pago no es válida.');
      setError('Redirigiendo a Mercado Pago…');window.location.assign(url.href);
    }catch(e){setError(errorMessage(e,'No pudimos iniciar el pago. Reintenta desde este mismo pedido.'));setBusy(false);lock.current=false}
  }
  return <section className="address-block"><h3>Método de pago</h3><label className="checkbox-label"><input type="radio" checked readOnly name="paymentMethod"/> Mercado Pago · Checkout Pro{LIVE?'':' (pruebas)'}</label><p>{LIVE?'Revisa el resumen y continúa al checkout oficial de Mercado Pago para pagar con tarjeta, transferencia o efectivo.':'Revisa el resumen y continúa al checkout oficial para realizar un pago de prueba.'}</p><button type="button" className="button" disabled={busy} onClick={()=>void pay()}>{busy?'Preparando pago…':'Pagar con Mercado Pago'}</button>{error&&<p role={busy?'status':'alert'} className={busy?'muted':'error'}>{error}</p>}</section>;
}
const labels:Record<z.infer<typeof paymentSchema>['status'],string>={CREATED:'Estamos verificando tu pago',PENDING:'Pago pendiente',IN_PROCESS:'Estamos verificando tu pago',APPROVED:'Pago confirmado',REJECTED:'Pago rechazado',CANCELLED:'Pago cancelado',REFUNDED:'Pago reembolsado',PARTIALLY_REFUNDED:'Pago con reembolso parcial',UNKNOWN:'Estamos verificando tu pago'};
export function VerifiedPayment({orderId,knownStatus,onVerified}:{orderId:string;knownStatus:string;onVerified:()=>void}) {
  const notified=useRef('');
  const [payment,setPayment]=useState<z.infer<typeof paymentSchema>|null>(null);const [error,setError]=useState('');const [round,setRound]=useState(0);
  useEffect(()=>{
    let active=true;let timer:ReturnType<typeof setTimeout>;let count=0;
    async function read(){
      try{const p=await api.get(`/payments/orders/${encodeURIComponent(orderId)}`,paymentSchema);if(!active)return;setPayment(p);setError('');if(p.status!==knownStatus&&notified.current!==`${p.id}:${p.status}`&&['APPROVED','REJECTED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED','UNKNOWN'].includes(p.status)){notified.current=`${p.id}:${p.status}`;onVerified()}
        if(['CREATED','PENDING','IN_PROCESS','UNKNOWN'].includes(p.status)&&++count<24)timer=setTimeout(()=>void read(),5000);
      }catch(e){if(active)setError(e instanceof ApiError&&e.status===404?'El pago aún no se ha iniciado o no tienes acceso desde esta sesión.':errorMessage(e,'No pudimos verificar tu pago. Abre el pedido con tu cuenta o desde el navegador donde lo creaste.'))}
    }
    void read();return()=>{active=false;clearTimeout(timer)};
  },[orderId,round,knownStatus,onVerified]);
  return <section className="address-block" aria-live="polite"><h2>{payment?labels[payment.status]:'Estamos verificando tu pago'}</h2><p>{payment?.status==='APPROVED'?(LIVE?'Confirmamos tu pago con Mercado Pago. Te enviaremos un correo con el resumen y otro cuando tu pedido salga a reparto.':'El backend confirmó tu pago de prueba con Mercado Pago. No se enviarán productos.'):'El estado se confirma con Mercado Pago. Puedes volver a consultar tu pedido más tarde.'}</p>{error&&<p className="error" role="alert">{error}</p>}<button className="text-link" onClick={()=>setRound(n=>n+1)}>Actualizar estado</button> <Link href={`/account/orders/${encodeURIComponent(orderId)}`}>Consultar pedido</Link></section>;
}

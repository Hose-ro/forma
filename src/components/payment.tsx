 'use client';
import { LIVE } from '@/lib/business';
import Link from 'next/link';
import {useEffect,useRef,useState} from 'react';
import type {z} from 'zod';
import {api} from '@/lib/api/client';
import {ApiError,errorMessage} from '@/lib/api/http';
import {checkoutSessionSchema,paymentMethodsSchema,paymentSchema,preferenceSchema} from '@/lib/api/schemas';

type Provider=z.infer<typeof checkoutSessionSchema>['provider'];
const METHODS:Record<Provider,{name:string;detail:string}>={
  STRIPE:{name:'Tarjeta u OXXO',detail:'Tarjeta de crédito o débito, o pago en efectivo en OXXO. Procesado por Stripe.'},
  MERCADO_PAGO:{name:'Mercado Pago',detail:'Tarjeta, efectivo o saldo de tu cuenta Mercado Pago.'},
  PAYPAL:{name:'PayPal',detail:'Paga con tu cuenta PayPal.'},
  CONEKTA:{name:'Transferencia SPEI',detail:'Recibe una CLABE para pagar desde tu banca en línea. Procesado por Conekta.'},
};
export const providerName=(provider:string)=>METHODS[provider as Provider]?.name??'el procesador de pagos';

/** Only the official hosted checkout of the chosen provider may receive the buyer. */
function trustedCheckout(provider:Provider,href:string){
  const url=new URL(href);
  if(url.protocol!=='https:'||url.port||url.username||url.password)return false;
  switch(provider){
    case 'MERCADO_PAGO':return url.hostname==='www.mercadopago.com.mx'&&url.pathname==='/checkout/v1/redirect'&&!url.hash;
    case 'STRIPE':return url.hostname==='checkout.stripe.com';
    case 'PAYPAL':return (url.hostname==='www.paypal.com'||(!LIVE&&url.hostname==='www.sandbox.paypal.com'))&&!url.hash;
    case 'CONEKTA':return (url.hostname==='pay.conekta.com'||url.hostname==='pay.conekta.io')&&!url.hash;
  }
}

export function PaymentAction({orderId}:{orderId:string}) {
  const [methods,setMethods]=useState<Provider[]|null>(null);const [choice,setChoice]=useState<Provider|null>(null);
  const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [round,setRound]=useState(0);
  // An API that predates method selection (e.g. the frontend deployed first) only offers Mercado Pago.
  const [legacy,setLegacy]=useState(false);
  const lock=useRef(false);
  // One key per method: retrying keeps the same checkout; choosing another method opens a new one.
  const keys=useRef<Partial<Record<Provider,string>>>({});
  useEffect(()=>{
    let active=true;setError('');
    api.get('/payments/methods',paymentMethodsSchema).then(list=>{if(!active)return;const p=list.map(m=>m.provider);setLegacy(false);setMethods(p);setChoice(c=>c&&p.includes(c)?c:p[0]??null)})
      .catch(e=>{
        if(!active)return;
        if(e instanceof ApiError&&e.status===404){setLegacy(true);setMethods(['MERCADO_PAGO']);setChoice('MERCADO_PAGO');return}
        setMethods([]);setError(errorMessage(e,'No pudimos cargar los métodos de pago.'));
      });
    return()=>{active=false};
  },[round]);
  async function pay(){
    if(lock.current||!choice)return;lock.current=true;setBusy(true);setError('');
    const key=keys.current[choice]??=`payment-${crypto.randomUUID()}`;
    try{
      const p=legacy
        ?{...await api.post('/payments/mercadopago/preference',preferenceSchema,{orderId},{'Idempotency-Key':key}),provider:'MERCADO_PAGO' as const}
        :await api.post('/payments/checkout',checkoutSessionSchema,{orderId,provider:choice},{'Idempotency-Key':key});
      if(p.provider!==choice||!trustedCheckout(choice,p.checkoutUrl))throw new Error('La URL de pago no es válida.');
      setError(`Redirigiendo a ${METHODS[choice].name}…`);window.location.assign(p.checkoutUrl);
    }catch(e){setError(errorMessage(e,'No pudimos iniciar el pago. Reintenta desde este mismo pedido.'));setBusy(false);lock.current=false}
  }
  if(methods===null)return <section className="address-block" aria-busy="true"><h3>Método de pago</h3><p className="muted">Cargando métodos de pago…</p></section>;
  return <section className="address-block"><h3>Método de pago</h3>
    {methods.length===0&&!error&&<p>Los pagos en línea no están disponibles por ahora. Intenta más tarde.</p>}
    <div role="radiogroup" aria-label="Método de pago">{methods.map(m=><label key={m} className="checkbox-label"><input type="radio" name="paymentMethod" value={m} checked={choice===m} disabled={busy} onChange={()=>setChoice(m)}/> <span><strong>{METHODS[m].name}</strong>{LIVE?'':' (pruebas)'}<br/><small className="muted">{METHODS[m].detail}</small></span></label>)}</div>
    {methods.length>0&&<p>{LIVE?'Continuarás al sitio oficial del método elegido para completar el pago. No guardamos datos de tu tarjeta.':'Continuarás al checkout oficial en modo de pruebas.'}{methods.length>1?' Si cambias de método, el intento anterior se cierra.':''}</p>}
    {choice&&<button type="button" className="button" disabled={busy} onClick={()=>void pay()}>{busy?'Preparando pago…':`Pagar con ${METHODS[choice].name}`}</button>}
    {error&&<p role={busy?'status':'alert'} className={busy?'muted':'error'}>{error}</p>}
    {methods.length===0&&error&&<button type="button" className="text-link" onClick={()=>{setMethods(null);setRound(n=>n+1)}}>Reintentar</button>}</section>;
}

/** PayPal sends the buyer back here after approval; the backend captures the payment, then the order is shown. */
export function ConfirmPayment({paymentId,children}:{paymentId:string;children:React.ReactNode}) {
  const started=useRef(false);const [working,setWorking]=useState(true);const [error,setError]=useState('');
  useEffect(()=>{
    if(started.current)return;started.current=true;
    api.post(`/payments/${encodeURIComponent(paymentId)}/confirm`,paymentSchema,{})
      .catch(e=>setError(errorMessage(e,'No pudimos confirmar tu pago todavía. El estado de tu pedido se actualizará en unos minutos.')))
      .finally(()=>setWorking(false));
  },[paymentId]);
  if(working)return <div className="page" aria-busy="true"><h1>Confirmando tu pago…</h1><p>No cierres esta página.</p></div>;
  return <>{error&&<div className="page"><p className="error" role="alert">{error}</p></div>}{children}</>;
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
  return <section className="address-block" aria-live="polite"><h2>{payment?labels[payment.status]:'Estamos verificando tu pago'}</h2><p>{payment?.status==='APPROVED'?(LIVE?`Confirmamos tu pago con ${providerName(payment.provider)}. Te enviaremos un correo con el resumen y otro cuando tu pedido salga a reparto.`:`El backend confirmó tu pago de prueba con ${providerName(payment.provider)}. No se enviarán productos.`):`El estado se confirma con ${payment?providerName(payment.provider):'el procesador de pagos'}. Puedes volver a consultar tu pedido más tarde.`}</p>{error&&<p className="error" role="alert">{error}</p>}<button className="text-link" onClick={()=>setRound(n=>n+1)}>Actualizar estado</button> <Link href={`/account/orders/${encodeURIComponent(orderId)}`}>Consultar pedido</Link></section>;
}

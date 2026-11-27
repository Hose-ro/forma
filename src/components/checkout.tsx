'use client';
import { BUSINESS, LIVE } from '@/lib/business';
import { shippingFor, shippingRulesSchema, type ShippingRules } from '@/lib/shipping';
import Link from 'next/link';
import Image from 'next/image';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { ArrowRight, Check } from 'lucide-react';
import { customerSchema, type CheckoutValues } from '@/lib/validation';
import { api } from '@/lib/api/client';
import { marketing } from '@/lib/marketing/tracking';
import { ApiError, errorMessage } from '@/lib/api/http';
import { orderSchema, savedAddressSchema, savedAddressesSchema, type SavedAddress } from '@/lib/api/schemas';
import { useShop } from '@/stores/shop';
import { FALLBACK_IMAGE, money } from '@/lib/data';
import { Empty } from './ui';
import { CheckoutAddress } from './checkout-address';
import { MAX_SAVED_ADDRESSES } from './account-addresses';
const fields: {name:'firstName'|'lastName'|'email'|'phone';label:string;auto:string;type?:string}[]=[
  {name:'firstName',label:'Nombre',auto:'given-name'},{name:'lastName',label:'Apellidos',auto:'family-name'},
  {name:'email',label:'Correo electrónico',auto:'email',type:'email'},{name:'phone',label:'Teléfono',auto:'tel',type:'tel'},
];
const same=(a?:string|null,b?:string|null)=>(a??'').trim().toLowerCase()===(b??'').trim().toLowerCase();
/** The saved address the form currently describes, if any (municipality and state follow the postal code). */
const matchSaved=(saved:SavedAddress[]|null,v:Partial<CheckoutValues>)=>saved?.find(a=>same(a.line1,v.street)&&same(a.line2,v.line2)&&same(a.neighborhood,v.neighborhood)&&same(a.postalCode,v.postalCode));
const savedLabel=(a:SavedAddress)=>`${a.label?`${a.label} · `:''}${a.line1}, ${a.neighborhood||a.city}${a.isDefault?' (predeterminada)':''}`;
export function Checkout(){const {cart,cartReady,user,refreshCart}=useShop();const router=useRouter();const [error,setError]=useState('');const [placed,setPlaced]=useState(false);
// One quote and one Idempotency-Key per attempt: retrying after a network failure can never create a second order.
const attempt=useRef<{quoteId:string;key:string}|null>(null);
const [rules,setRules]=useState<ShippingRules|null>(null);useEffect(()=>{api.get('/checkout/shipping',shippingRulesSchema).then(setRules,()=>{})},[]);
const form=useForm<CheckoutValues>({resolver:zodResolver(customerSchema),defaultValues:{country:'México',consent:false,line2:''}});
const {register,handleSubmit,reset,watch,setValue,getValues,formState:{errors,isSubmitting,isDirty}}=form;
// Signed-in customers start from their default address, can switch to another saved one and can keep the one they type.
const [saved,setSaved]=useState<SavedAddress[]|null>(null);const [saveDefault,setSaveDefault]=useState(false);
function applyAddress(a:SavedAddress|null){
  const v={street:a?.line1??'',line2:a?.line2??'',neighborhood:a?.neighborhood??'',city:a?.city??'',state:a?.state??'',postalCode:a?.postalCode??''};
  (Object.keys(v) as (keyof typeof v)[]).forEach(k=>setValue(k,v[k],{shouldDirty:true}));
  if(a?.phone&&!getValues('phone'))setValue('phone',a.phone,{shouldDirty:true});
  attempt.current=null;
}
useEffect(()=>{if(user&&!isDirty)reset({country:'México',consent:false,line2:'',firstName:user.firstName,lastName:user.lastName,email:user.email,phone:user.phone||''})},[user]);// eslint-disable-line react-hooks/exhaustive-deps
const userId=user?.id;
useEffect(()=>{setSaved(null);if(!userId)return;let active=true;
  api.get('/me/addresses',savedAddressesSchema).then(list=>{if(!active)return;setSaved(list);setSaveDefault(list.length===0);
    const preferred=list.find(a=>a.isDefault)??list[0];if(preferred&&!getValues('street')&&!getValues('postalCode'))applyAddress(preferred)},()=>{if(active)setSaved([])});
  return()=>{active=false}},[userId]);// eslint-disable-line react-hooks/exhaustive-deps
const state=watch('state');const shipping=rules&&state&&cart?shippingFor(rules,state,cart.subtotal):null;
const matched=matchSaved(saved,watch());const canSave=!!user&&saved!==null&&!matched?.isDefault&&(!!matched||saved.length<MAX_SAVED_ADDRESSES);
if(!cartReady)return <div className="page skeleton"/>;if(placed)return <div className="page skeleton" aria-label="Confirmando pedido"/>;if(!cart?.items.length)return <div className="page"><Empty title="Primero, encuentra tu objeto."><Link className="button" href="/shop">Explorar colección</Link></Empty></div>;
async function persistAddress(data:CheckoutValues){const existing=matchSaved(saved,data);
  if(existing){if(!existing.isDefault)await api.patch(`/me/addresses/${existing.id}/default`,savedAddressSchema);return}
  await api.post('/me/addresses',savedAddressSchema,{label:'',recipientName:`${data.firstName} ${data.lastName}`.trim(),line1:data.street,line2:data.line2,neighborhood:data.neighborhood,city:data.city,state:data.state,postalCode:data.postalCode,country:'MX',phone:data.phone,makeDefault:true});
}
async function submit(data:CheckoutValues){setError('');if(!cart)return;const shouldSave=canSave&&saveDefault;const recipient=matchSaved(saved,data)?.recipientName||`${data.firstName} ${data.lastName}`.trim();try{
  if(!attempt.current){const {data:quote,eventId}=await api.postWithMarketingReceipt('/checkout/quotes',orderSchema,{cartId:cart.id,customer:{email:data.email,firstName:data.firstName,lastName:data.lastName,phone:data.phone},shippingAddress:{recipientName:recipient,line1:data.street,line2:data.line2,neighborhood:data.neighborhood,city:data.city,state:data.state,postalCode:data.postalCode,country:'MX',phone:data.phone}});marketing.checkout(eventId, quote);attempt.current={quoteId:quote.id,key:`checkout-${crypto.randomUUID()}`}}
  const {data:order,eventId}=await api.postWithMarketingReceipt('/orders',orderSchema,{quoteId:attempt.current.quoteId},{'Idempotency-Key':attempt.current.key});
  marketing.order(eventId, order);
  setPlaced(true);
  // Saving the address is a convenience: it never blocks an order that already exists.
  if(shouldSave)await persistAddress(data).catch(()=>{});
  void refreshCart().catch(()=>{});router.push(`/checkout/confirmation/${order.id}`);
}catch(e){
  // 409/422: stock, prices or the quote changed. Start over with a fresh quote from the current cart.
  if(e instanceof ApiError&&(e.status===409||e.status===422||e.status===404)){attempt.current=null;await refreshCart();setError('La disponibilidad o los precios cambiaron. Revisa tu bolsa antes de continuar.')}
  else if(e instanceof ApiError&&e.status===400){attempt.current=null;setError(e.fieldErrors.length?'Revisa los datos del formulario.':e.detail)}
  else setError(errorMessage(e,'No pudimos crear tu pedido. Inténtalo de nuevo.'));
}}
return <div className="page checkout-page"><div className="page-intro"><p className="eyebrow">Bolsa / Datos / Confirmación</p><h1>Un paso <i>más.</i></h1><p>{user?'Completa tu pedido con tu cuenta.':'Completa tu pedido como invitado.'}</p></div><div className="checkout-layout"><form noValidate onSubmit={handleSubmit(submit)} onChange={()=>{attempt.current=null}}><h2>Tus datos</h2><p className="muted">Tus datos se guardan con el pedido. El pago se realiza en el sitio oficial del método que elijas; aquí no se solicitan datos bancarios.</p><div className="form-grid">{fields.map(f=><label key={f.name}>{f.label}
  <input {...register(f.name)} type={f.type||'text'} autoComplete={f.auto}
    aria-invalid={!!errors[f.name]} aria-describedby={errors[f.name]?`${f.name}-error`:undefined}/>
  {errors[f.name]&&<span className="error" id={`${f.name}-error`}>{errors[f.name]?.message}</span>}
</label>)}</div><h2>Dirección de envío</h2>
{saved&&saved.length>0&&<label className="checkout-saved-address">Dirección guardada
  <select value={matched?.id??''} onChange={e=>applyAddress(saved.find(a=>a.id===e.target.value)??null)}>
    {saved.map(a=><option key={a.id} value={a.id}>{savedLabel(a)}</option>)}
    <option value="">Usar otra dirección</option>
  </select>
</label>}
<div className="form-grid"><CheckoutAddress form={form}/></div>
{canSave&&<label className="checkbox-label checkout-save-address"><input type="checkbox" checked={saveDefault} onChange={e=>setSaveDefault(e.target.checked)}/> {matched?'Usar esta dirección como predeterminada en mi cuenta':'Guardar esta dirección como predeterminada en mi cuenta'}</label>}
{rules&&state&&shipping==null&&<p role="alert" className="error">Por ahora no hacemos envíos a {state}.</p>}<div className="demo-notice"><Check size={18}/><div><strong>{LIVE?'Pago seguro':'Pagos · Modo de pruebas'}</strong><p>{LIVE?'Revisarás el total del pedido, con envío gratis, y elegirás cómo pagar: tarjeta, OXXO, transferencia, PayPal o Mercado Pago, según los métodos disponibles. No guardamos datos de tu tarjeta.':'Revisarás el total del pedido y elegirás un método de pago en modo de pruebas. Utiliza únicamente cuentas y medios de pago de prueba. No se enviarán productos.'}</p></div></div><label className="checkbox-label"><input type="checkbox" {...register('consent')}/> {LIVE?<>Acepto los <Link href="/policies/terms" target="_blank">Términos y condiciones</Link> y el <Link href="/policies/privacy" target="_blank">Aviso de privacidad</Link>.</>:'Entiendo que continuaré a un pago de prueba.'}</label>{errors.consent&&<p className="error" role="alert">{errors.consent.message}</p>}{error&&<p role="alert" className="error">{error} <Link href="/cart">Volver a la bolsa</Link></p>}<button className="button full" disabled={isSubmitting||!cart.checkoutReady||!!(rules&&state&&shipping==null)}>{isSubmitting?'Creando pedido…':'Revisar pedido'} <ArrowRight size={17}/></button>{!cart.checkoutReady&&<p className="error" role="alert">Un artículo de tu bolsa ya no está disponible en esa cantidad. <Link href="/cart">Revisar bolsa</Link></p>}</form><aside className="checkout-summary"><h2>Tu selección</h2>{cart.items.map(line=><div className="order-line" key={line.id}><Image src={line.imageUrl||FALLBACK_IMAGE} alt={line.productName} width={65} height={82}/><div><strong>{line.productName}</strong><p>{line.variantName} · {line.quantity} ud.</p></div><span>{money(line.lineTotal)}</span></div>)}<div className="total-row"><span>Subtotal</span><span>{money(cart.subtotal)}</span></div><div className="total-row"><span>Envío</span><span>{rules&&state&&shipping==null?'Sin cobertura':shipping?money(shipping):'Gratis'}</span></div><div className="total-row grand-total"><strong>{rules&&state&&shipping==null?'Subtotal del pedido':'Total'}</strong><strong>{money(cart.subtotal+(shipping||0))} MXN</strong></div><p className="muted">{rules?`Entrega estimada: ${rules.deliveryEstimate}. `:''}Confirmarás el total definitivo antes de pagar.</p></aside></div></div>}

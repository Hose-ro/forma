'use client';
import { BUSINESS, LIVE } from '@/lib/business';
import Image from 'next/image';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import * as Accordion from '@radix-ui/react-accordion';
import { ArrowRight, Plus, Check, Truck } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { request } from '@/lib/api/http';
import { shippingRulesSchema, type ShippingRules } from '@/lib/shipping';
import { useShop } from '@/stores/shop';
import { money } from '@/lib/data';
import type { Product } from '@/types';
import { Quantity } from './ui';
import { ProductCard } from './product-card';
import { FavoriteButton } from './favorite-button';
export function ProductDetail({product:p,related}:{product:Product;related:Product[]}){const {add,buy,cartBusy,cartError}=useShop();const router=useRouter();const [buying,setBuying]=useState(false);const [variantId,setVariantId]=useState(p.defaultVariantId||'');const [quantity,setQuantity]=useState(1);const [photo,setPhoto]=useState('');const [failed,setFailed]=useState(false);const [shipping,setShipping]=useState<ShippingRules|null>(null);const [buyBar,setBuyBar]=useState(true);const actions=useRef<HTMLDivElement>(null);
// Delivery time and coverage come from the same backend rules the checkout enforces, never from fixed copy.
useEffect(()=>{let active=true;request('/api/v1/checkout/shipping',shippingRulesSchema,{credentials:'same-origin'}).then(r=>{if(active)setShipping(r)},()=>{});return()=>{active=false}},[]);
// The phone purchase bar stands in for the main button only while that one is off screen.
useEffect(()=>{const row=actions.current;if(!row)return;const observer=new IntersectionObserver(([entry])=>setBuyBar(!entry.isIntersecting));observer.observe(row);return()=>observer.disconnect()},[]);
const coverage=!shipping?'El plazo y la cobertura se confirman antes de pagar.':shipping.onlyZones?(shipping.zones.length?`Por ahora enviamos a: ${shipping.zones.map(z=>z.state).join(', ')}.`:'Por ahora no tenemos zonas de envío activas.'):'Enviamos a todo México.';const v=p.variants.find(v=>v.id===variantId)||p.variants[0];if(!v)return <div className="page empty"><h1>Este objeto no está <i>disponible.</i></h1><Link className="button" href="/shop">Volver a la colección</Link></div>;const available=p.available&&v.available&&v.stock>0;const images=Array.from(new Set([...p.images,...p.variants.map(v=>v.image)]));const addToBag=async()=>setFailed(!(await add(v.id,quantity)));const buyNow=async()=>{setBuying(true);const ok=await buy(v.id,quantity);setFailed(!ok);if(ok)return router.push('/checkout');setBuying(false);actions.current?.scrollIntoView({block:'center'})};return <><div className="page product-page"><nav className="breadcrumbs" aria-label="Ruta"><Link href="/shop">Colección</Link><span>/</span><Link href={`/shop?category=${p.category}`}>{p.category}</Link><span>/</span><span>{p.name}</span></nav><div className="product-detail"><div className="gallery"><div className="main-product-image"><Image src={photo||v.image} alt={`${p.name}, ${v.name}`} fill priority sizes="(max-width: 800px) 100vw, 55vw"/><FavoriteButton product={p}/></div>{images.length>1&&<div className="thumbnails">{images.map((src,i)=><button className={(photo||v.image)===src?'selected':''} key={src} onClick={()=>setPhoto(src)} aria-label={`Ver imagen ${i+1} de ${p.name}`} aria-pressed={(photo||v.image)===src}><Image src={src} alt={`${p.name}, vista ${i+1}`} width={80} height={100}/></button>)}</div>}<small className="muted">Fotografía ilustrativa.</small></div><div className="product-info"><p className="eyebrow">Olvenio / {p.category}</p><h1>{p.name}</h1><p className="detail-price">{money(v.price)} <small>MXN</small></p><p className="product-description">{p.description}</p><fieldset className="variant-selector"><legend>Edición <span>{v.name}</span></legend><div>{p.variants.map(option=><button key={option.id} className={v.id===option.id?'selected':''} aria-pressed={v.id===option.id} onClick={()=>{setVariantId(option.id);setPhoto(option.image);setQuantity(1);setFailed(false)}}><span className="swatch" style={{background:option.color}}/>{option.name}{!option.stock&&' · Agotado'}</button>)}</div></fieldset><p className={`stock ${!available?'muted':''}`}><Check size={14}/>{available?`${v.stock} disponibles`:'Temporalmente agotado'}</p>
<div className="buy-quantity"><span>Cantidad</span><Quantity value={quantity} max={v.stock} onChange={setQuantity}/></div>
<div className="buy-actions" ref={actions}>
  <button className="button buy-now" disabled={!available||cartBusy||buying} onClick={buyNow}>{buying?'Un momento…':available?'Comprar':'Agotado'}</button>
  <button className="button add-to-cart" disabled={!available||cartBusy||buying} onClick={addToBag}>{cartBusy&&!buying?'Agregando…':'Agregar al carrito'}</button>
</div>
{failed&&cartError&&<p className="error" role="alert">{cartError}</p>}<div className="shipping-note"><Truck size={18}/>{LIVE?<p><strong>Envío gratis</strong>{shipping?`Entrega estimada: ${shipping.deliveryEstimate}, más ${BUSINESS.handlingTime} de preparación.`:'El plazo de entrega se muestra antes de pagar.'}<br/><span>{coverage}</span></p>:<p><strong>Envío gratis</strong>Entrega estimada de ejemplo: {shipping?.deliveryEstimate||'5 a 10 días hábiles'}.</p>}</div><Accordion.Root type="multiple" className="accordions">
  {(p.material || p.supplier?.specifications || p.supplier?.productUrl) && <Accordion.Item value="details">
    <Accordion.Header><Accordion.Trigger>Materiales y características<Plus size={16}/></Accordion.Trigger></Accordion.Header>
    <Accordion.Content>
      {p.material && <p>{p.material}</p>}
      {p.supplier?.specifications && <p className="supplier-text">{p.supplier.specifications}</p>}
      {p.supplier?.productUrl && <a className="text-link" href={p.supplier.productUrl} target="_blank" rel="noopener noreferrer">Ver ficha del proveedor <ArrowRight size={16}/></a>}
    </Accordion.Content>
  </Accordion.Item>}
  <Accordion.Item value="returns">
    <Accordion.Header><Accordion.Trigger>Devoluciones y reembolsos<Plus size={16}/></Accordion.Trigger></Accordion.Header>
    <Accordion.Content>
      {p.supplier?.returnPolicy && <p className="supplier-text">{p.supplier.returnPolicy}</p>}
      {p.supplier?.returnPolicyUrl && <p><a className="text-link" href={p.supplier.returnPolicyUrl} target="_blank" rel="noopener noreferrer">Consultar política del proveedor <ArrowRight size={16}/></a></p>}
      {!p.supplier?.returnPolicy && !p.supplier?.returnPolicyUrl && <p>La política del proveedor aún no está disponible para este producto.</p>}
      <Link className="text-link" href="/policies/shipping">Política de Olvenio <ArrowRight size={16}/></Link>
    </Accordion.Content>
  </Accordion.Item>
  <Accordion.Item value="demo">
    <Accordion.Header><Accordion.Trigger>Sobre esta colección<Plus size={16}/></Accordion.Trigger></Accordion.Header>
    <Accordion.Content>{LIVE?'Las fotografías son de referencia: el color y el acabado pueden variar ligeramente respecto a la pieza física.':'Tienda de demostración: los pedidos se registran pero no se cobran ni se envían. Las imágenes sirven como referencia visual y no garantizan características técnicas.'}</Accordion.Content>
  </Accordion.Item>
</Accordion.Root></div></div><section className="related"><div className="section-heading"><h2>En buena <i>compañía.</i></h2><Link className="text-link" href="/shop">La colección <ArrowRight size={16}/></Link></div><div className="product-grid">{related.map(p=><ProductCard product={p} key={p.id}/>)}</div></section></div>
<aside className="buy-bar" aria-label="Compra rápida" data-hidden={!buyBar}><p className="buy-bar-price"><strong>{money(v.price)} <small>MXN</small></strong><span>Envío gratis</span></p><button className="button buy-now" disabled={!available||cartBusy||buying} onClick={buyNow}>{buying?'Un momento…':available?'Comprar':'Agotado'}</button></aside></>}

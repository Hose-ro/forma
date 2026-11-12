'use client';
import Image from 'next/image';
import Link from 'next/link';
import { Plus, ArrowUpRight } from 'lucide-react';
import { useShop } from '@/stores/shop';
import { money } from '@/lib/data';
import type { Product } from '@/types';
export function ProductCard({product:p}:{product:Product}){const add=useShop(s=>s.add);const busy=useShop(s=>s.cartBusy);const variantId=p.defaultVariantId;return <article className="product-card"><div className="product-image"><Link href={`/product/${p.slug}`} aria-label={`Ver ${p.name}`}><Image src={p.images[0]} alt={p.name} fill sizes="(max-width: 640px) 50vw, (max-width: 1024px) 33vw, 25vw"/>{p.images[1]&&<Image className="secondary-image" src={p.images[1]} alt={`${p.name}, otra vista`} fill sizes="25vw"/>}</Link><span className="product-category">{p.category}</span><button className="quick-add" disabled={!p.available||!variantId||busy} onClick={()=>variantId&&add(variantId)} aria-label={`Añadir ${p.name} al carrito`}><span>{p.available&&variantId?'Añadir':'Agotado'}</span><Plus size={16}/></button></div><Link className="product-caption" href={`/product/${p.slug}`}><div><h3>{p.name}</h3><p>{money(p.price)} <small>MXN</small></p></div><ArrowUpRight size={17}/></Link></article>}

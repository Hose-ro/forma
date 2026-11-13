import { cache } from 'react';
import { notFound } from 'next/navigation';
import { ProductDetail } from '@/components/product-detail';
import type { Metadata } from 'next';
import { catalogRepository } from '@/lib/catalog';
import { pageMetadata, snippet } from '@/lib/seo';
const find=cache((slug:string)=>catalogRepository.find(slug));
export async function generateMetadata({params}:{params:Promise<{slug:string}>}):Promise<Metadata>{
  const {slug}=await params;const product=await find(slug);
  if(!product)return {}; // The page calls notFound(): not-found.tsx sets the title and Next.js adds noindex.
  // Products without copy still get a specific description instead of the site-wide one.
  const fallback=`${product.name}${product.material?` en ${product.material.toLowerCase()}`:''}. Una pieza de ${product.category.toLowerCase()} de la colección FORMA. Tienda de demostración.`;
  return pageMetadata({title:product.name,description:snippet(product.description||fallback),path:`/product/${product.slug}`,image:{url:product.images[0],alt:product.name}});
}
export default async function Page({params}:{params:Promise<{slug:string}>}){const {slug}=await params;const product=await find(slug);if(!product)notFound();
  // Related: same category first, then the rest of the collection.
  const [same,rest]=await Promise.all([catalogRepository.list({category:product.categorySlug,size:5}),catalogRepository.list({size:8})]);
  const related=[...same.items,...rest.items].filter((p,i,all)=>p.id!==product.id&&all.findIndex(x=>x.id===p.id)===i).slice(0,4);
  return <ProductDetail key={product.id} product={product} related={related}/>}

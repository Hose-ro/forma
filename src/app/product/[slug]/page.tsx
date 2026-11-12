import { cache } from 'react';
import { notFound } from 'next/navigation';
import { ProductDetail } from '@/components/product-detail';
import { catalogRepository } from '@/lib/catalog';
const find=cache((slug:string)=>catalogRepository.find(slug));
export async function generateMetadata({params}:{params:Promise<{slug:string}>}){const {slug}=await params;return {title:(await find(slug))?.name||'Objeto de la colección'}}
export default async function Page({params}:{params:Promise<{slug:string}>}){const {slug}=await params;const product=await find(slug);if(!product)notFound();
  // Related: same category first, then the rest of the collection.
  const [same,rest]=await Promise.all([catalogRepository.list({category:product.categorySlug,size:5}),catalogRepository.list({size:8})]);
  const related=[...same.items,...rest.items].filter((p,i,all)=>p.id!==product.id&&all.findIndex(x=>x.id===p.id)===i).slice(0,4);
  return <ProductDetail key={product.id} product={product} related={related}/>}

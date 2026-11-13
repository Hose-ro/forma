import { Catalog } from '@/components/catalog';
import { catalogRepository } from '@/lib/catalog';
import { pageMetadata } from '@/lib/seo';
// Filtered and paginated URLs share the canonical /shop.
export const metadata=pageMetadata({title:'La colección',description:'Explora la colección completa de FORMA.: audio, living y accesorios con carácter. Filtra por categoría, precio y disponibilidad.',path:'/shop'});
type Search=Promise<Record<string,string|string[]|undefined>>;
export default async function Page({searchParams}:{searchParams:Search}){
  const sp=await searchParams;const one=(key:string)=>{const v=sp[key];return (Array.isArray(v)?v[0]:v)||''};
  const categories=await catalogRepository.categories();
  // The URL keeps the readable category name (?category=Audio); the API filters by slug.
  const wanted=one('category').toLowerCase();const category=categories.find(c=>c.name.toLowerCase()===wanted||c.slug===wanted);
  const page=Math.max(1,Number.parseInt(one('page'),10)||1);
  const results=wanted&&!category?{items:[],page:0,totalElements:0,totalPages:0}:await catalogRepository.list({category:category?.slug,q:one('q'),minPrice:one('min'),maxPrice:one('max'),available:'available' in sp,sort:one('sort'),page:page-1});
  return <Catalog results={results} categories={categories}/>;
}

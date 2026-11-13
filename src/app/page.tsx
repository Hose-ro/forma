import { Home } from '@/components/home';
import { catalogRepository } from '@/lib/catalog';
import { SITE_DESCRIPTION, SITE_TAGLINE, pageMetadata } from '@/lib/seo';
export const dynamic='force-dynamic';
export const metadata=pageMetadata({title:SITE_TAGLINE,absoluteTitle:true,description:SITE_DESCRIPTION,path:'/'});
export default async function Page(){const [products,categories]=await Promise.all([catalogRepository.list({sort:'featured',size:8}),catalogRepository.categories()]);return <Home featured={products.items.filter(p=>p.featured).slice(0,4)} categories={categories}/>}

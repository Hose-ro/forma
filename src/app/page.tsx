import { Home } from '@/components/home';
import { catalogRepository } from '@/lib/catalog';
export const dynamic='force-dynamic';
export default async function Page(){const [products,categories]=await Promise.all([catalogRepository.list({sort:'featured',size:8}),catalogRepository.categories()]);return <Home featured={products.items.filter(p=>p.featured).slice(0,4)} categories={categories}/>}

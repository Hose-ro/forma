import { OrderDetail } from '@/components/order';
export const metadata={title:'Detalle del pedido'};
export default async function Page({params}:{params:Promise<{id:string}>}){return <OrderDetail id={(await params).id}/>}

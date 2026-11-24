import { OrderDetail } from '@/components/order';
export const metadata={title:'Detalle del pedido'};
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <OrderDetail id={id} showTracking />;
}

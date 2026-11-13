import Link from 'next/link';
import {OrderDetail} from '@/components/order';
export const metadata={title:'Pago aprobado'};
export default async function PaymentReturn({searchParams}:{searchParams:Promise<{orderId?:string}>}) {
  const {orderId}=await searchParams;
  if(!orderId||!/^[0-9a-f-]{36}$/i.test(orderId))return <div className="page"><h1>Consulta tu pedido</h1><Link className="button" href="/account">Ir a mi cuenta</Link></div>;
  return <OrderDetail id={orderId}/>;
}

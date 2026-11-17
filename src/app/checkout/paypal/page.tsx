import Link from 'next/link';
import {OrderDetail} from '@/components/order';
import {ConfirmPayment} from '@/components/payment';
export const metadata={title:'Confirmando pago'};
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export default async function PayPalReturn({searchParams}:{searchParams:Promise<{orderId?:string;paymentId?:string}>}) {
  const {orderId,paymentId}=await searchParams;
  if(!orderId||!paymentId||!UUID.test(orderId)||!UUID.test(paymentId))return <div className="page"><h1>Consulta tu pedido</h1><Link className="button" href="/account">Ir a mi cuenta</Link></div>;
  return <ConfirmPayment paymentId={paymentId}><OrderDetail id={orderId}/></ConfirmPayment>;
}

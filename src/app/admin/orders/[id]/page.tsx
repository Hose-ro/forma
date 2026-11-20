import type { Metadata } from 'next';
import { notFound, redirect } from 'next/navigation';
import { AdminOrderDetail } from '@/components/admin-order-detail';
import { getSessionUser } from '@/lib/api/server';
import { NO_INDEX } from '@/lib/seo';

export const metadata: Metadata = { title: 'Gestión del pedido', robots: NO_INDEX };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!/^[a-f0-9-]{36}$/i.test(id)) notFound();
  const user = await getSessionUser();
  if (!user) redirect(`/account?next=${encodeURIComponent(`/admin/orders/${id}`)}`);
  if (user.role !== 'ADMIN') notFound();
  return <AdminOrderDetail key={id} id={id} />;
}

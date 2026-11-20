import type { Metadata } from 'next';
import { GuestTracking } from '@/components/guest-tracking';
import { NO_INDEX } from '@/lib/seo';

export const metadata: Metadata = { title: 'Seguimiento de pedido', robots: NO_INDEX, referrer: 'no-referrer' };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  return <GuestTracking id={(await params).id} />;
}

'use client';

import { useLayoutEffect } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { marketingNavigation } from '@/lib/marketing/tracking';

export function MarketingRuntime({ frameOrigin }: { frameOrigin: string }) {
  const path = usePathname();
  const ttclid = useSearchParams().get('ttclid');
  useLayoutEffect(() => marketingNavigation(frameOrigin, path), [frameOrigin, path, ttclid]);
  return null;
}

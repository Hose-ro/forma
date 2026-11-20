import { z } from 'zod';
import { getSessionUser } from '@/lib/api/server';
import { importSupplierPage, supplierPageUrl } from '@/lib/supplier-source';

const input = z.object({ url: z.string().max(1000), kind: z.literal('product') });

export async function POST(request: Request) {
  const user = await getSessionUser();
  if (user?.role !== 'ADMIN') return new Response(null, { status: user ? 403 : 401 });
  if (!/^application\/json\b/i.test(request.headers.get('content-type') ?? '')
      || request.headers.get('sec-fetch-site') === 'cross-site') {
    return new Response(null, { status: 403 });
  }
  const parsed = input.safeParse(await request.json().catch(() => null));
  if (!parsed.success || !supplierPageUrl(parsed.data.url)) {
    return new Response(null, { status: 400 });
  }
  try {
    return Response.json(await importSupplierPage(parsed.data.url, parsed.data.kind));
  } catch {
    return new Response(null, { status: 422 });
  }
}

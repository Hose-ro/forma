import type { z } from 'zod';
import type { productSummarySchema } from './api/schemas';
import { FALLBACK_IMAGE } from './data';
import type { Product } from '@/types';

export function fromSummary(p: z.infer<typeof productSummarySchema>): Product {
  return {
    id: p.id, slug: p.slug, name: p.name, description: '', material: '',
    category: p.category.name, categorySlug: p.category.slug, price: p.price,
    compareAtPrice: p.compareAtPrice ?? undefined, currency: p.currency,
    images: [p.imageUrl || FALLBACK_IMAGE], variants: [], defaultVariantId: p.defaultVariantId ?? null,
    available: p.available, featured: p.featured, createdAt: p.createdAt,
  };
}

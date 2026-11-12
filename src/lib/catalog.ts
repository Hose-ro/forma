import type { z } from 'zod';
import { ApiError } from './api/http';
import { categoriesSchema, productDetailSchema, productPageSchema, type productSummarySchema } from './api/schemas';
import { serverGet } from './api/server';
import { FALLBACK_IMAGE } from './data';
import type { Category, Product, ProductPage } from '@/types';

// Catalog adapter: reads the real catalog from the REST API (server side) and maps it to the storefront model.
export type CatalogQuery = { category?: string; q?: string; minPrice?: string; maxPrice?: string; available?: boolean; sort?: string; page?: number; size?: number };

const SORTS: Record<string, string> = { featured: 'featured', 'price-asc': 'price_asc', 'price-desc': 'price_desc', newest: 'newest' };

function fromSummary(p: z.infer<typeof productSummarySchema>): Product {
  return { id: p.id, slug: p.slug, name: p.name, description: '', material: '', category: p.category.name, categorySlug: p.category.slug, price: p.price, compareAtPrice: p.compareAtPrice ?? undefined, currency: p.currency, images: [p.imageUrl || FALLBACK_IMAGE], variants: [], defaultVariantId: p.defaultVariantId ?? null, available: p.available, featured: p.featured, createdAt: p.createdAt };
}

export const catalogRepository = {
  async list(query: CatalogQuery = {}): Promise<ProductPage> {
    const params = new URLSearchParams();
    if (query.category) params.set('category', query.category);
    if (query.q) params.set('q', query.q.slice(0, 100));
    if (query.minPrice && Number(query.minPrice) >= 0) params.set('minPrice', String(Number(query.minPrice)));
    if (query.maxPrice && Number(query.maxPrice) >= 0) params.set('maxPrice', String(Number(query.maxPrice)));
    if (query.available) params.set('available', 'true');
    params.set('sort', SORTS[query.sort || 'featured'] || 'featured');
    params.set('page', String(Math.max(0, query.page || 0)));
    params.set('size', String(query.size || 12));
    const result = await serverGet(`/products?${params}`, productPageSchema);
    return { items: result.items.map(fromSummary), page: result.page, totalElements: result.totalElements, totalPages: result.totalPages };
  },

  /** Returns null when the product does not exist or is not published. */
  async find(slug: string): Promise<Product | null> {
    try {
      const p = await serverGet(`/products/${encodeURIComponent(slug)}`, productDetailSchema);
      const fallback = p.images[0]?.url || FALLBACK_IMAGE;
      const variants = p.variants.map(v => ({ id: v.id, sku: v.sku, name: v.name, color: v.color || '#b6aa94', price: v.price, stock: v.stock, image: v.imageUrl || fallback, available: v.available }));
      return { id: p.id, slug: p.slug, name: p.name, description: p.description || '', material: p.material || '', category: p.category.name, categorySlug: p.category.slug, price: p.price, compareAtPrice: p.compareAtPrice ?? undefined, currency: p.currency, images: p.images.length ? p.images.map(i => i.url) : [fallback], variants, defaultVariantId: variants.find(v => v.available)?.id ?? null, available: p.available, featured: p.featured, createdAt: p.createdAt };
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return null;
      throw e;
    }
  },

  categories: (): Promise<Category[]> => serverGet('/categories', categoriesSchema),
};

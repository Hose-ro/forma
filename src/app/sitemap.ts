import type { MetadataRoute } from 'next';
import { catalogRepository } from '@/lib/catalog';
import { siteUrl } from '@/lib/seo';

// Built per request: the catalog lives in the backend, which is not reachable at build time.
export const dynamic = 'force-dynamic';

const STATIC_PATHS = ['/', '/shop', '/policies/shipping', '/policies/terms', '/policies/privacy', '/policies/cookies'];
const PAGE_SIZE = 100; // Backend maximum page size.
const MAX_PAGES = 50;

async function productEntries(base: URL): Promise<MetadataRoute.Sitemap> {
  const entries: MetadataRoute.Sitemap = [];
  try {
    for (let page = 0; page < MAX_PAGES; page++) {
      const result = await catalogRepository.list({ sort: 'newest', page, size: PAGE_SIZE });
      for (const p of result.items) {
        entries.push({ url: new URL(`/product/${p.slug}`, base).toString(), lastModified: p.createdAt, changeFrequency: 'weekly', priority: 0.7, images: p.images.map(src => new URL(src, base).toString()) });
      }
      if (page + 1 >= result.totalPages) break;
    }
  } catch (e) {
    // A backend outage must not break the sitemap: serve the static routes plus whatever was read.
    console.error('sitemap: catalog unavailable', e);
  }
  return entries;
}

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const base = siteUrl();
  const pages: MetadataRoute.Sitemap = STATIC_PATHS.map(path => ({
    url: new URL(path, base).toString(),
    changeFrequency: path === '/' || path === '/shop' ? 'daily' : 'yearly',
    priority: path === '/' ? 1 : path === '/shop' ? 0.9 : 0.3,
  }));
  return [...pages, ...(await productEntries(base))];
}
